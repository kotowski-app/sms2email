package app.kotowski.sms2email.work

import android.content.Context
import androidx.work.WorkManager
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.SweepIntervalPreset
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.BackgroundFlags
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ForwardingNotification
import app.kotowski.sms2email.system.OpenAppIntentProvider
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// The single owner of "what a background flag means". It collects
// EncryptedPrefs.backgroundFlags (which emits the startup value and then every
// setter change), so the periodic workers, the persistent notification and the
// queue are reconciled on every flag change no matter which code path wrote it
// (toggle, settings import, onboarding, app launch, boot, app update).
//
// Reconciling the off direction is what makes the flag bulletproof: a worker
// that is never cancelled keeps sweeping (and would mark stuck rows FAILED
// after the retry period), the notification keeps claiming "listening", and the
// queue keeps showing rows that will never be sent.
@Singleton
class BackgroundStateReconciler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val prefs: EncryptedPrefs,
        private val smsRepository: SmsRepository,
        private val cleanupWorkerManager: CleanupWorkerManager,
        private val openAppIntentProvider: OpenAppIntentProvider,
        private val exactWakeArmer: ExactWakeArmer,
        // IO (not Main): the off-branch reconcile does Room work that must not run on the main thread.
        private val scope: CoroutineScope,
    ) {
        private companion object {
            const val TAG = "BkgStateObs"
        }

        fun start() {
            scope.launch {
                prefs.backgroundFlags.collect { reconcile(it) }
            }
        }

        private suspend fun reconcile(flags: BackgroundFlags) {
            if (flags.forwardingEnabled) {
                step("reconcile retry sweep") { reconcileRetrySweep(flags.retrySweepIntervalMinutes) }
            } else {
                // Park first, then cancel the sweep (the notification follows in its own
                // step below): a worker that reads a row afterwards sees IGNORED (already
                // skipped) and a mid-send worker loses its claim CAS, so no parked row can
                // be delivered. Same ordering as the manual toggle.
                step("park auto-sendable rows") {
                    val parkedIds = parkAutoSendableRows()
                    // A parked row must not keep a pending wake: disarm the exact-wake-up
                    // tokens of exactly the rows that just went IGNORED.
                    if (parkedIds.isNotEmpty()) {
                        exactWakeArmer.cancelAllRetryAlarms(parkedIds)
                    }
                }
                step("cancel RetryWorker") { cancelRetrySweep() }
                step("park stale sending rows") { parkStaleSendingRows() }
            }
            reconcileNotification(flags)
            if (flags.autoCleanupEnabled) {
                step("enqueue HistoryCleanupWorker") { cleanupWorkerManager.enqueue() }
            } else {
                step("cancel HistoryCleanupWorker") { cleanupWorkerManager.cancel() }
            }
        }

        // The persistent notification follows the forwarding flag AND the sticky-notification
        // setting: a user who turned the indicator off must not get it back on any flag
        // change, and a permission denial is handled by show() itself (a logged skip).
        private suspend fun reconcileNotification(flags: BackgroundFlags) {
            if (flags.forwardingEnabled && flags.showStickyNotification) {
                step("show forwarding notification") {
                    ForwardingNotification.show(context, openAppIntentProvider.create(context))
                }
            } else {
                step("dismiss forwarding notification") { ForwardingNotification.dismiss(context) }
            }
        }

        // Re-syncs the persistent notification from the CURRENT flags without touching the
        // workers: the settings screen calls it when POST_NOTIFICATIONS is granted while the
        // sticky notification is on, so the indicator appears at once instead of waiting for
        // the next flag change.
        suspend fun syncNotification() {
            reconcileNotification(prefs.backgroundFlags.value)
        }

        private suspend fun parkAutoSendableRows(): List<Long> {
            val parkedIds = smsRepository.getAutoSendableIds()
            if (parkedIds.isNotEmpty()) {
                Logger.i(TAG, "forwarding disabled, parking ${parkedIds.size} auto-sendable rows as IGNORED")
                smsRepository.transitionSmsStatusMany(
                    parkedIds,
                    listOf(SmsStatus.PENDING, SmsStatus.RETRYING),
                    SmsStatus.IGNORED,
                    StatusUpdate(statusDetails = context.getString(R.string.sms_status_forwarding_stopped)),
                )
            }
            return parkedIds
        }

        // A row claimed SENDING whose worker died (crash, OOM, force-stop, the WorkManager
        // process reaper) is never finalized: the sweep is the only thing that recovers it,
        // and the cancel step above just removed it. Without this pass such a row would sit
        // SENDING while forwarding is off with no escape (it cannot be sent, cancelled or
        // re-forwarded, and IN_FLIGHT protects it from deletion) and re-enabling would
        // re-send it — a duplicate if the original SMTP 250 already landed. Only the stale
        // ones, past the sweep's own threshold, are parked: a live worker still finishing
        // its send must not lose its claim, it parks itself through the off-branch of
        // SmsRetryScheduler.scheduleRetry. Runs after the cancel so no fresh tick can race
        // the CAS; a tick already running can still win, in which case the row is parked
        // on the next flag change.
        private suspend fun parkStaleSendingRows() {
            val staleSending =
                smsRepository.getStaleSending(System.currentTimeMillis() - AppConfig.RETRY_SENDING_STALE_MS)
            if (staleSending.isNotEmpty()) {
                val ids = staleSending.map { it.id }
                Logger.i(TAG, "forwarding disabled, parking ${ids.size} stale SENDING rows as IGNORED")
                smsRepository.transitionSmsStatusMany(
                    ids,
                    listOf(SmsStatus.SENDING),
                    SmsStatus.IGNORED,
                    StatusUpdate(statusDetails = context.getString(R.string.sms_status_forwarding_stopped)),
                )
            }
        }

        // The sweep's period is the user setting: re-arm the periodic work only when it
        // changed or when WorkManager no longer holds it (a KEEP re-enqueue on every app
        // open would reset the schedule and starve the ticks), the exact-wake-up token on
        // every pass (idempotent, and it does not survive a reboot while the WorkManager
        // work does). WorkManager's own state — not the pref — is the liveness check: a
        // reset of its Room DB (corruption, a prefs-only backup restore) with the pref
        // intact would otherwise match the interval and never re-enqueue, leaving PENDING/
        // RETRYING/SENDING rows unrecoverable until the user changes the sweep interval.
        private suspend fun reconcileRetrySweep(storedMinutes: Int) {
            // Gate off (the toggle off or the "Alarms & reminders" permission revoked):
            // nothing may stay armed from the enabled time. Runs on every pass — including
            // every process start — so a revoke that happens outside the settings screen
            // dies at the next wake; the armer's arm below no-ops while the gate is off.
            if (!exactWakeArmer.exactWakeActive()) {
                exactWakeArmer.cancelAll()
            }
            // A stored value outside the presets (a tampered file; the UI and the import
            // both enforce the presets) degrades to the default: a period outside
            // WorkManager's 15-min..24-h range cannot be enqueued at all.
            val intervalMinutes =
                storedMinutes.takeIf { it in SweepIntervalPreset.minutes }
                    ?: AppSettings.DEFAULT_RETRY_SWEEP_INTERVAL_MINUTES
            if (intervalMinutes <= 0) {
                cancelRetrySweep()
                return
            }
            val workManager = WorkManager.getInstance(context)
            // The pref only tracks the last armed interval (change detection); whether the
            // sweep is alive at all is read from WorkManager itself.
            val sweepAlive = workManager.getWorkInfosForUniqueWork(RetryWorker.WORK_NAME).await().isNotEmpty()
            if (prefs.armedSweepIntervalMinutes != intervalMinutes) {
                workManager.cancelUniqueWork(RetryWorker.WORK_NAME)
                RetryWorker.enqueuePeriodic(context, intervalMinutes.toLong())
                prefs.armedSweepIntervalMinutes = intervalMinutes
            } else if (!sweepAlive) {
                // Same interval, but WorkManager has lost the work (DB reset, prefs-only
                // backup restore): re-arm it; the pref already matches, nothing to cancel.
                Logger.i(TAG, "sweep missing from WorkManager, re-arming at ${intervalMinutes}m")
                RetryWorker.enqueuePeriodic(context, intervalMinutes.toLong())
            }
            exactWakeArmer.armSweepAlarm(System.currentTimeMillis() + intervalMinutes * 60_000L)
        }

        private fun cancelRetrySweep() {
            WorkManager.getInstance(context).cancelUniqueWork(RetryWorker.WORK_NAME)
            exactWakeArmer.cancelSweepAlarm()
            prefs.armedSweepIntervalMinutes = 0
        }

        // Every step is best-effort: one failure (a down WorkManager, a broken DB) must
        // not block the reconciliation of the remaining background state.
        private suspend fun step(
            name: String,
            action: suspend () -> Unit,
        ) {
            try {
                action()
            } catch (e: Exception) {
                Logger.e(TAG, "$name failed: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }
