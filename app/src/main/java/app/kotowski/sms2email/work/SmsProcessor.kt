package app.kotowski.sms2email.work

import android.telephony.SubscriptionManager
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.IngestResult
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.StatusUpdate
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.NetworkUtil
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsProcessor
    @Inject
    constructor(
        private val prefs: EncryptedPrefs,
        private val smsRepository: SmsRepository,
        @ApplicationContext private val appContext: android.content.Context,
        // The tray-slot lookup of the message filter's slot facet (the same precedent
        // as SmsSendExecutor's SIM-name lookup).
        private val simNameResolver: SimNameResolver,
        // Provided by AppModule (AppConfig.INGEST_RETRY_DELAY_MS); the unit tests pass a
        // short delay so the retry chain runs within the test's lifetime.
        private val ingestRetryDelayMs: Long,
    ) {
        private val tag = "SmsProcessor"

        data class IncomingSms(
            val phoneNumber: String,
            val messageBody: String,
            val timestamp: Long,
            val simId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            // The PDU service-center timestamp of messages[0] (SmsMessage.timestampMillis);
            // 0 when the PDU carries none. Stored as timeReceived when the "Use PDU
            // timestamp" setting is on (trusted as-is, whatever it says relative to the
            // device clock); a PDU carrying no timestamp falls back to timestamp.
            val pduTimestamp: Long = 0,
        )

        private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun processIncomingSms(
            sms: IncomingSms,
            onComplete: () -> Unit = {},
        ) {
            Logger.i(tag, "processIncomingSms: launch coroutine, simId=${sms.simId}, bodyLen=${sms.messageBody.length}")
            coroutineScope.launch {
                try {
                    handleIncomingSms(sms)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(tag, "processIncomingSms failed: ${e.javaClass.simpleName}: ${e.message}", e)
                } finally {
                    onComplete()
                }
            }
            Logger.i(tag, "processIncomingSms: coroutine launched")
        }

        suspend fun handleIncomingSms(
            sms: IncomingSms,
            attempt: Int = 1,
        ) {
            Logger.i(tag, "handleIncomingSms: entry, attempt=$attempt, simId=${sms.simId}, bodyLen=${sms.messageBody.length}")

            if (sms.messageBody.isBlank()) {
                Logger.i(tag, "handleIncomingSms: blank body, skipping without storage or forwarding, simId=${sms.simId}")
                return
            }

            // The stored time: the PDU service-center timestamp when the setting is on and
            // the value is valid, the receive wall clock otherwise. One snapshot for the
            // whole run, so a concurrent import cannot mix settings mid-processing.
            val settings = prefs.load()
            val usePduTimestamp = settings.usePduTimestamp
            val timestamp = resolveTimestamp(sms.pduTimestamp, sms.timestamp, usePduTimestamp)
            val dedupLevel = settings.dedupLevel
            Logger.i(
                tag,
                "handleIncomingSms: timestamp=$timestamp (pdu=${sms.pduTimestamp}, wall=${sms.timestamp}), " +
                    "usePduTimestamp=$usePduTimestamp, dedupLevel=${dedupLevel.name}",
            )

            val ingest: IngestResult =
                try {
                    smsRepository.insertIfNew(
                        phoneNumber = sms.phoneNumber,
                        messageBody = sms.messageBody,
                        timestamp = timestamp,
                        simId = sms.simId,
                        status = SmsStatus.PENDING,
                        dedupLevel = dedupLevel,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(tag, "handleIncomingSms: insert failed, attempt=$attempt: ${e.javaClass.simpleName}: ${e.message}", e)
                    scheduleIngestRetry(sms, attempt)
                    return
                }

            val insertedSmsId =
                when (ingest) {
                    is IngestResult.Inserted -> {
                        ingest.id
                    }

                    IngestResult.Duplicate -> {
                        Logger.i(tag, "handleIncomingSms: DEDUP HIT, skipping")
                        return
                    }
                }

            Logger.i(tag, "handleIncomingSms: inserted as PENDING, id=$insertedSmsId")

            val forwardingEnabled = settings.isForwardingEnabled
            Logger.i(tag, "handleIncomingSms: forwardingEnabled=$forwardingEnabled")

            if (!forwardingEnabled) {
                Logger.i(tag, "handleIncomingSms: forwarding disabled, updating status to IGNORED, id=$insertedSmsId")
                smsRepository.transitionSmsStatus(
                    insertedSmsId,
                    listOf(SmsStatus.PENDING),
                    SmsStatus.IGNORED,
                    StatusUpdate(statusDetails = appContext.getString(R.string.sms_detail_ignored)),
                )
                return
            }

            // The message filter (gated by the explicit "Message filtering" toggle, off
            // by default): after the insert (a repeated spam twin dedups against the
            // parked row instead of piling up) and after the forwarding-off gate, before
            // the offline park — the ordering is load-bearing: a filtered row parked
            // RETRYING would be sent by the sweep once the network returns, the filter
            // bypassed. While the toggle is off the filter is not evaluated at all
            // (every message forwards). The slot facet resolves against the live tray
            // map (a simId without a current slot, or a permission-revoked phone, never
            // hits it); the reason is mode-dependent (what a skip means in each polarity).
            if (settings.filterEnabled) {
                val filter = settings.filter
                val slot = simNameResolver.getSlotFor(sms.simId)
                if (!filter.shouldForward(sms.messageBody, sms.phoneNumber, slot)) {
                    val reasonRes =
                        if (filter.mode == FilterMode.FORWARD) {
                            R.string.settings_filter_status_no_match
                        } else {
                            R.string.settings_filter_status_skipped
                        }
                    Logger.i(
                        tag,
                        "handleIncomingSms: filtered out by the message filter (${filter.mode.name}), marking IGNORED, id=$insertedSmsId",
                    )
                    smsRepository.transitionSmsStatus(
                        insertedSmsId,
                        listOf(SmsStatus.PENDING),
                        SmsStatus.IGNORED,
                        StatusUpdate(statusDetails = appContext.getString(reasonRes)),
                    )
                    return
                }
            }

            val hasInternet = NetworkUtil.hasReliableInternet(appContext)
            Logger.i(tag, "handleIncomingSms: hasReliableInternet=$hasInternet, id=$insertedSmsId")

            if (!hasInternet) {
                Logger.w(tag, "handleIncomingSms: no reliable internet, marking RETRYING, id=$insertedSmsId")
                // No attempt yet: only the waiting detail and the 0-based count — the attempt
                // clocks are stamped by the claim at send time, so the first real failure
                // backs off exactly like an online-arriving twin.
                smsRepository.transitionSmsStatus(
                    insertedSmsId,
                    listOf(SmsStatus.PENDING),
                    SmsStatus.RETRYING,
                    StatusUpdate(
                        statusDetails = appContext.getString(R.string.error_waiting_for_internet),
                        retryCount = 0,
                    ),
                )
            }

            Logger.i(tag, "handleIncomingSms: enqueuing EmailWorker, id=$insertedSmsId")

            try {
                EmailWorker.enqueueSend(appContext, insertedSmsId)
            } catch (e: Exception) {
                Logger.e(tag, "handleIncomingSms: failed to enqueue EmailWorker: ${e.javaClass.simpleName}: ${e.message}", e)
            }

            Logger.i(tag, "handleIncomingSms: DONE, id=$insertedSmsId")
        }

        // A failed live-path insert created no row, so the retry sweep (which only ever
        // sees stored rows) can never recover the SMS: re-run the whole ingest on this
        // process's scope instead. The retries cannot ride the receiver's pendingResult —
        // it is finished when the first attempt returns, and goAsync times out in ~10s,
        // after which the process may be killed along with any in-flight retry. The
        // original sms.timestamp is reused, so the dedup key is stable across attempts
        // and a late twin still dedups. Bounded; a process death inside the window still
        // drops the SMS (no durable spool by design).
        private fun scheduleIngestRetry(
            sms: IncomingSms,
            attempt: Int,
        ) {
            if (attempt >= AppConfig.INGEST_RETRY_MAX_ATTEMPTS) {
                Logger.e(
                    tag,
                    "ingest retries exhausted after $attempt attempts, dropping SMS, simId=${sms.simId}, bodyLen=${sms.messageBody.length}",
                )
                return
            }
            coroutineScope.launch {
                delay(ingestRetryDelayMs)
                try {
                    handleIncomingSms(sms, attempt + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(tag, "ingest retry $attempt failed: ${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }

        companion object {
            // The timestamp selection for the live path. With the setting on the PDU
            // service-center timestamp is trusted as-is, whatever it says relative to the
            // device clock (the setting's contract: forward the carrier's time; the dedup
            // window already guards whatever time is stored). The only fallback is a PDU
            // carrying no timestamp (0 or negative) — there is nothing to store, so the
            // receive wall clock stands in.
            internal fun resolveTimestamp(
                pduTimestamp: Long,
                wallClock: Long,
                usePduTimestamp: Boolean,
            ): Long =
                if (usePduTimestamp && pduTimestamp > 0) {
                    pduTimestamp
                } else {
                    wallClock
                }
        }
    }
