package app.kotowski.sms2email.service

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.core.net.toUri
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.IngestResult
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.util.normalizeSenderAddress
import app.kotowski.sms2email.util.querySafely
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: SmsRepository,
        private val prefs: EncryptedPrefs,
    ) {
        private val tag = "SmsImporter"

        data class ImportResult(
            val importedCount: Int = 0,
            val skippedCount: Int = 0,
            val errorCount: Int = 0,
        )

        data class ImportProgress(
            val imported: Int = 0,
            val skipped: Int = 0,
            val errors: Int = 0,
            val current: Int = 0,
            val total: Int = 0,
        )

        private enum class RowOutcome {
            Imported,
            Skipped,
            Error,
        }

        // -1 marks a missing column; the guarded read sites degrade to defaults.
        private data class InboxColumns(
            val addressIdx: Int,
            val bodyIdx: Int,
            val dateIdx: Int,
            val simIdx: Int,
        ) {
            val hasRequiredColumns: Boolean
                get() = addressIdx != -1 && dateIdx != -1
        }

        private data class OpenedInbox(
            val cursor: Cursor?,
            val supportsSimId: Boolean,
        )

        private fun hasSimIdColumn(
            uri: Uri,
            columns: Array<String>,
        ): Boolean =
            context
                .querySafely(uri, columns + "sub_id")
                ?.use { it.getColumnIndex("sub_id") >= 0 }
                ?: false

        private fun ImportProgress.bump(
            onProgress: (ImportProgress) -> Unit,
            outcome: RowOutcome,
        ): ImportProgress {
            val current = current + 1
            // The total is the pre-count snapshot taken before the iteration starts: a row
            // arriving after it (an incoming SMS) pushes the current past the total, so the
            // total follows the higher of the two — the UI fraction and the "x of y" label
            // can never report current > total.
            val updated =
                when (outcome) {
                    RowOutcome.Imported -> copy(imported = imported + 1)
                    RowOutcome.Skipped -> copy(skipped = skipped + 1)
                    RowOutcome.Error -> copy(errors = errors + 1)
                }.copy(current = current, total = maxOf(total, current))
            onProgress(updated)
            return updated
        }

        private fun sinceSelection(sinceTimestamp: Long?): Pair<String?, Array<String>?> =
            if (sinceTimestamp != null) {
                "${Telephony.Sms.DATE} >= ?" to arrayOf(sinceTimestamp.toString())
            } else {
                null to null
            }

        private fun openInboxCursor(sinceTimestamp: Long?): OpenedInbox {
            val inboxUri = INBOX_URI.toUri()
            val baseColumns =
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.PERSON,
                )
            // The sub_id column exists based on hardware, not the active SIM count: a
            // single-SIM inbox still carries it, and the stored id feeds the SIM display.
            val supportsSimId = hasSimIdColumn(inboxUri, baseColumns)
            val inboxProjection =
                if (supportsSimId) {
                    baseColumns + "sub_id"
                } else {
                    baseColumns
                }
            val (selection, selectionArgs) = sinceSelection(sinceTimestamp)
            val cursor =
                context.querySafely(
                    inboxUri,
                    inboxProjection,
                    selection,
                    selectionArgs,
                    "${Telephony.Sms.DATE} ASC",
                )
            return OpenedInbox(cursor, supportsSimId)
        }

        private fun resolveInboxColumns(
            cursor: Cursor,
            supportsSimId: Boolean,
        ): InboxColumns =
            InboxColumns(
                addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS),
                bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY),
                dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE),
                simIdx = if (supportsSimId) cursor.getColumnIndex("sub_id") else -1,
            )

        private suspend fun processRow(
            cursor: Cursor,
            columns: InboxColumns,
            dedupLevel: DedupLevel,
        ): RowOutcome =
            try {
                val phoneNumber =
                    normalizeSenderAddress(
                        cursor.getString(columns.addressIdx),
                        context.getString(R.string.sms_unknown_sender),
                    )
                val messageBody = if (columns.bodyIdx != -1) cursor.getString(columns.bodyIdx) ?: "" else ""
                val timestamp = cursor.getLong(columns.dateIdx)
                val simId = if (columns.simIdx >= 0 && !cursor.isNull(columns.simIdx)) cursor.getInt(columns.simIdx) else -1

                if (messageBody.isBlank()) {
                    RowOutcome.Skipped
                } else {
                    try {
                        val ingest =
                            repository.insertIfNew(
                                phoneNumber = phoneNumber,
                                messageBody = messageBody,
                                timestamp = timestamp,
                                simId = simId,
                                status = SmsStatus.IMPORTED,
                                dedupLevel = dedupLevel,
                            )
                        when (ingest) {
                            is IngestResult.Inserted -> RowOutcome.Imported
                            IngestResult.Duplicate -> RowOutcome.Skipped
                        }
                    } catch (e: Exception) {
                        // Warning per row (the UI shows the aggregate): one error with a stack trace
                        // per row would flood a systematically failing import; the import-end
                        // line is the single error-level entry.
                        Logger.w(tag, "insertSms failed: ${e.javaClass.simpleName}: ${e.message}")
                        RowOutcome.Error
                    }
                }
            } catch (e: Exception) {
                Logger.w(tag, "Row processing failed: ${e.javaClass.simpleName}: ${e.message}")
                RowOutcome.Error
            }

        private suspend fun processRows(
            cursor: Cursor,
            columns: InboxColumns,
            dedupLevel: DedupLevel,
            initialProgress: ImportProgress,
            onProgress: (ImportProgress) -> Unit,
        ): ImportResult {
            var progress = initialProgress
            while (cursor.moveToNext()) {
                val outcome = processRow(cursor, columns, dedupLevel)
                progress = progress.bump(onProgress, outcome)
            }
            return ImportResult(progress.imported, progress.skipped, progress.errors)
        }

        private suspend fun importFromInbox(
            dedupLevel: DedupLevel,
            sinceTimestamp: Long? = null,
            initialProgress: ImportProgress = ImportProgress(),
            onProgress: (ImportProgress) -> Unit = {},
        ): ImportResult =
            withContext(Dispatchers.IO) {
                val (cursor, supportsSimId) = openInboxCursor(sinceTimestamp)
                if (cursor == null) {
                    return@withContext ImportResult(errorCount = 1)
                }

                cursor.use { inboxCursor ->
                    if (inboxCursor.count == 0) {
                        ImportResult()
                    } else {
                        val columns = resolveInboxColumns(inboxCursor, supportsSimId)
                        if (!columns.hasRequiredColumns) {
                            // A missing ADDRESS or DATE would make every row throw and the import
                            // report one identical error per row; fail once with an actionable error.
                            Logger.e(
                                tag,
                                "provider cursor lacks the address/date column (addressIdx=${columns.addressIdx} dateIdx=${columns.dateIdx}); aborting import",
                            )
                            return@use ImportResult(errorCount = 1)
                        }
                        val result = processRows(inboxCursor, columns, dedupLevel, initialProgress, onProgress)
                        // The single error-level line for the accumulated per-row failures (the per-row
                        // lines stay warnings); a clean import logs nothing, the abort paths keep their lines.
                        if (result.errorCount > 0) {
                            Logger.e(tag, "import finished with ${result.errorCount} failed rows")
                        }
                        result
                    }
                }
            }

        suspend fun importMessages(
            sinceTimestamp: Long? = null,
            onProgress: (ImportProgress) -> Unit = {},
        ): ImportResult =
            withContext(Dispatchers.IO) {
                // The pre-count is a binder round-trip and the caller (viewModelScope) is
                // the main thread, so the whole entry point runs off the main thread.
                try {
                    // One snapshot for the whole import, so a concurrent settings change
                    // cannot mix dedup levels mid-import.
                    val dedupLevel = prefs.load().dedupLevel
                    val (selection, selectionArgs) = sinceSelection(sinceTimestamp)
                    val totalCount = getCount(INBOX_URI.toUri(), selection, selectionArgs)
                    val seededProgress = ImportProgress(total = totalCount)
                    onProgress(seededProgress)
                    importFromInbox(dedupLevel, sinceTimestamp, seededProgress, onProgress)
                } catch (e: Exception) {
                    Logger.e(tag, "Import failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    ImportResult(errorCount = 1)
                }
            }

        private fun getCount(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Int =
            context
                .querySafely(uri, arrayOf(Telephony.Sms._ID), selection, selectionArgs)
                ?.use { it.count }
                ?: 0

        private companion object {
            const val INBOX_URI = "content://sms/inbox"
        }
    }
