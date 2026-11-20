package app.kotowski.sms2email.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.receiver.RetryAlarmReceiver
import app.kotowski.sms2email.receiver.SweepAlarmReceiver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// The exact-wake-up tokens: additional exact alarms that wake the device at the same
// time the WorkManager schedules would run — never a replacement, the deferred
// scheduler stays active as a backup. Aggressive OEM battery optimization delays the
// deferred scheduler, an exact alarm is more likely to fire on time; where it does not
// (permission revoked, no permission below API 31) the WorkManager path carries on
// alone.
@Singleton
class ExactWakeArmer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: SmsRepository,
        private val prefs: EncryptedPrefs,
        private val permissionManager: PermissionManager,
    ) {
        private val tag = "ExactWakeArmer"

        private val alarmManager: AlarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // The single gate of the exact wake-up: the feature is on AND the "Alarms &
        // reminders" permission is granted (PermissionManager guards against throwing
        // ROMs and is unconditionally true below API 31).
        fun exactWakeActive(): Boolean = prefs.load().exactWakeEnabled && permissionManager.canScheduleExactAlarms()

        // The sweep's next tick: one alarm, re-armed from a fresh read on every arm (the
        // re-arm overwrites the same PendingIntent). A re-arm while the gate is off is a
        // no-op, so callers do not re-check the gate.
        fun armSweepAlarm(atMillis: Long) {
            if (!exactWakeActive()) return
            // Clamp past times to now — stock AOSP fires them immediately, but a ROM
            // that drops past alarms loses the wake token.
            val triggeredTime = atMillis.coerceAtLeast(System.currentTimeMillis())
            // The "Alarms & reminders" permission revoked between the gate check and the set
            // (very unlikely) throws SecurityException: a missed arm is safe — the WorkManager
            // scheduler stays the primary wake, so degrade to a log instead of crashing the caller.
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggeredTime,
                    sweepAlarmPendingIntent(),
                )
                Logger.i(tag, "sweep exact alarm armed for $triggeredTime")
            } catch (e: Exception) {
                Logger.w(
                    tag,
                    "sweep exact alarm arm failed, the WorkManager wake carries on: ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        }

        // Ungated on purpose: an alarm set while the exact wake-up was on must die with
        // the turn-off (or the permission revoke) even if the feature is off by now —
        // cancelling a never-set alarm is a no-op.
        fun cancelSweepAlarm() {
            alarmManager.cancel(sweepAlarmPendingIntent())
            Logger.i(tag, "sweep exact alarm cancelled")
        }

        // One row's next attempt: the cancel always runs (a row whose next attempt moved
        // or went terminal must not keep a stale token), the arm only past the threshold
        // — shorter delays fire while the process is still alive from the failed send.
        fun rearmRetryAlarm(
            smsId: Long,
            delayMillis: Long,
        ) {
            cancelRetryAlarm(smsId)
            if (delayMillis < AppConfig.EXACT_WAKE_ARM_THRESHOLD_MS) return
            if (!exactWakeActive()) return
            val triggeredTime = (System.currentTimeMillis() + delayMillis).coerceAtLeast(System.currentTimeMillis())
            // The same very-unlikely revoke race as armSweepAlarm: a missed arm degrades to a log.
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggeredTime,
                    retryAlarmPendingIntent(smsId),
                )
                Logger.i(tag, "retry exact alarm armed smsId=$smsId for $triggeredTime")
            } catch (e: Exception) {
                Logger.w(
                    tag,
                    "retry exact alarm arm failed smsId=$smsId, the WorkManager wake carries on: ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        }

        fun cancelRetryAlarm(smsId: Long) {
            alarmManager.cancel(retryAlarmPendingIntent(smsId))
            Logger.i(tag, "retry exact alarm cancelled smsId=$smsId")
        }

        // Cancels every pending retry token the caller names: a turn-off (or a permission
        // revoke) must not leave alarms armed from the enabled time — cancelling a
        // never-set alarm is a no-op.
        fun cancelAllRetryAlarms(ids: List<Long>) {
            ids.forEach { cancelRetryAlarm(it) }
        }

        // Disarms everything: the sweep token and every pending retry token (the RETRYING
        // rows' stored state names them). The gate-off cleanup (a toggle-off or a
        // permission revoke must not leave tokens armed) — cancelling a never-set alarm
        // is a no-op.
        suspend fun cancelAll() {
            cancelSweepAlarm()
            cancelAllRetryAlarms(repository.getRetryingIds())
        }

        // Re-arms every pending retry token from the stored due time: the permission (or
        // the setting) came back while rows were waiting, so the tokens return without a
        // recompute. Rows past their due time or without one get no token — their
        // per-row work already is (or will be) the wake.
        suspend fun rearmAllRetryAlarms() {
            if (!exactWakeActive()) return
            val now = System.currentTimeMillis()
            for (sms in repository.getRetryingSms()) {
                val due = sms.timeNextSendAttempt ?: continue
                if (due > now) {
                    rearmRetryAlarm(sms.id, due - now)
                }
            }
        }

        private fun sweepAlarmPendingIntent(): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                SWEEP_ALARM_REQUEST_CODE,
                Intent(context, SweepAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // One request code per row, offset into a range reserved for these intents.
        private fun retryAlarmPendingIntent(smsId: Long): PendingIntent {
            val intent =
                Intent(context, RetryAlarmReceiver::class.java)
                    .putExtra(RetryAlarmReceiver.SMS_ID_KEY, smsId)
            return PendingIntent.getBroadcast(
                context,
                RETRY_ALARM_REQUEST_CODE_BASE + smsId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        companion object {
            private const val SWEEP_ALARM_REQUEST_CODE = 9_999
            private const val RETRY_ALARM_REQUEST_CODE_BASE = 10_000
        }
    }
