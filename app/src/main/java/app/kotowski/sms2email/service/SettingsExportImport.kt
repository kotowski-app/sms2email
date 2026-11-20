package app.kotowski.sms2email.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.LogSizePreset
import app.kotowski.sms2email.PeriodPreset
import app.kotowski.sms2email.SweepIntervalPreset
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.util.EmailUtil
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.util.querySafely
import app.kotowski.sms2email.util.sanitizeHeaderValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

// The outcome of the import's I/O phase (the size check and the capped read); only
// [Json] is handed to the caller's onJson.
sealed interface ImportReadOutcome {
    data class Json(
        val json: String,
    ) : ImportReadOutcome

    object TooLarge : ImportReadOutcome

    object Failed : ImportReadOutcome
}

object SettingsExportImport {
    private const val VERSION = 1

    // The JSON type a field's value must carry; a wrong type (0/1 for a flag, "30 days"
    // for a number) fails the import like any other bad value instead of degrading to the
    // product default. A scalar quoted as a string ("465", "true") still imports, mirroring
    // the org.json parser's leniency. The enum kinds carry the value as the enum name.
    private enum class Kind { STRING, INT, BOOL, ENUM }

    // The single list driving both directions: the keys the export writes, the keys the
    // import requires (the core config keys, plus the version; the optional ones degrade
    // to the product default while absent), each field's type, its value rule (a present
    // value failing it fails the import like any other bad value), and its mapping onto
    // the settings (the free-text fields sanitized on the way in, the enum fields resolved
    // to the level/protocol or failing the import), so the file's shape can never drift
    // from the stored settings.
    private class Field(
        val key: String,
        val kind: Kind,
        val get: (AppSettings) -> Any,
        val put: (AppSettings, Any) -> AppSettings,
        val resolve: ((String) -> Any)? = null,
        val optional: Boolean = false,
        val validate: ((Any) -> Unit)? = null,
    )

    private val FIELDS =
        listOf(
            Field(
                EncryptedPrefs.KEY_SMTP_HOST,
                Kind.STRING,
                { it.smtpHost },
                { s, v -> s.copy(smtpHost = v as String) },
                // A blank host can never connect and would fail every send permanently;
                // reject the file here like any other bad value, before anything is written.
                validate = { if ((it as String).isBlank()) throw JSONException("Invalid smtp_host") },
            ),
            Field(
                EncryptedPrefs.KEY_SMTP_PORT,
                Kind.INT,
                { it.smtpPort },
                { s, v -> s.copy(smtpPort = v as Int) },
                // A port outside the TCP range can never connect and would fail every send
                // permanently; reject the file here like any other bad value, before
                // anything is written.
                validate = { if (it as Int !in AppConfig.VALID_PORT_RANGE) throw JSONException("Invalid smtp_port") },
            ),
            Field(
                EncryptedPrefs.KEY_SMTP_PROTOCOL,
                Kind.ENUM,
                { it.smtpProtocol.name },
                { s, v -> s.copy(smtpProtocol = v as SmtpProtocol) },
                { name ->
                    SmtpProtocol.entries.firstOrNull { it.name == name }
                        ?: throw JSONException("Invalid smtp_protocol")
                },
            ),
            Field(
                EncryptedPrefs.KEY_SMTP_USERNAME,
                Kind.STRING,
                { it.smtpUsername },
                { s, v -> s.copy(smtpUsername = sanitizeHeaderValue(v as String)) },
                // A blank username fails AppSettings.isComplete, so every send would fail
                // permanently with the opaque "SMTP is not configured"; isBlank, not
                // isEmpty: sanitizeHeaderValue strips control chars but not spaces, so "   "
                // must fail too.
                validate = { if ((it as String).isBlank()) throw JSONException("Invalid smtp_username") },
            ),
            Field(
                EncryptedPrefs.KEY_DEST_EMAIL,
                Kind.STRING,
                { it.destEmail },
                { s, v -> s.copy(destEmail = v as String) },
                // A bad address stored here breaks every send with an opaque error; fail
                // the import instead (same leniency as the UI, which validates with
                // EmailUtil.isValidEmail).
                validate = { requireEmail(EncryptedPrefs.KEY_DEST_EMAIL, it as String) },
            ),
            Field(
                EncryptedPrefs.KEY_DEST_NAME,
                Kind.STRING,
                { it.destName },
                { s, v -> s.copy(destName = sanitizeHeaderValue(v as String)) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_SENDER_EMAIL,
                Kind.STRING,
                { it.senderEmail },
                { s, v -> s.copy(senderEmail = v as String) },
                // Same rule as the destination address: a bad address breaks every send
                // with an opaque error, so fail the import instead.
                validate = { requireEmail(EncryptedPrefs.KEY_SENDER_EMAIL, it as String) },
            ),
            Field(
                EncryptedPrefs.KEY_SENDER_NAME,
                Kind.STRING,
                { it.senderName },
                { s, v -> s.copy(senderName = sanitizeHeaderValue(v as String)) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_SUBJECT_PREFIX,
                Kind.STRING,
                { it.subjectPrefix },
                // The cap mirrors the form's: an over-long imported prefix must not reach
                // the send path (the composed subject must stay within the providers' bounds).
                { s, v -> s.copy(subjectPrefix = sanitizeHeaderValue(v as String).take(AppConfig.SUBJECT_PREFIX_MAX_LENGTH)) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_AUTO_SUBJECT_PREFIX,
                Kind.BOOL,
                { it.autoSubjectPrefix },
                { s, v -> s.copy(autoSubjectPrefix = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_COUNTRY_CODE,
                Kind.STRING,
                { it.countryCode },
                { s, v -> s.copy(countryCode = v as String) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_AUTO_COUNTRY,
                Kind.BOOL,
                { it.autoCountry },
                { s, v -> s.copy(autoCountry = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_FORWARDING_ENABLED,
                Kind.BOOL,
                { it.isForwardingEnabled },
                { s, v -> s.copy(isForwardingEnabled = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_CONTACT_RESOLUTION,
                Kind.BOOL,
                { it.useContactResolution },
                { s, v -> s.copy(useContactResolution = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_DISPLAY_SIM_CARDS,
                Kind.BOOL,
                { it.displaySimCards },
                { s, v -> s.copy(displaySimCards = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_AUTO_CLEANUP_RETENTION_DAYS,
                Kind.INT,
                { it.autoCleanupRetentionDays },
                { s, v -> s.copy(autoCleanupRetentionDays = v as Int) },
                optional = true,
                // The periods the editor offers are the presets only, plus 0 — the off
                // state the app itself stores when the cleanup is turned off; a non-preset
                // value (1 makes the daily cleanup delete all history older than one day)
                // is rejected like any other bad value, before anything is written.
                validate = {
                    val days = it as Int
                    if (days != 0 && days !in PeriodPreset.days) {
                        throw JSONException("Invalid auto_cleanup_retention_days")
                    }
                },
            ),
            Field(
                EncryptedPrefs.KEY_JSON_EMAIL_FORMAT,
                Kind.BOOL,
                { it.useJsonEmailFormat },
                { s, v -> s.copy(useJsonEmailFormat = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_INCLUDE_TIME_IN_BODY,
                Kind.BOOL,
                { it.includeTimeInBody },
                { s, v -> s.copy(includeTimeInBody = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_USE_PDU_TIMESTAMP,
                Kind.BOOL,
                { it.usePduTimestamp },
                { s, v -> s.copy(usePduTimestamp = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_DEDUP_LEVEL,
                Kind.ENUM,
                { it.dedupLevel.name },
                { s, v -> s.copy(dedupLevel = v as DedupLevel) },
                { name ->
                    DedupLevel.entries.firstOrNull { it.name == name }
                        ?: throw JSONException("Invalid dedup_level")
                },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_SHOW_STICKY_NOTIFICATION,
                Kind.BOOL,
                { it.showStickyNotification },
                { s, v -> s.copy(showStickyNotification = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_LOG_TO_FILE_ENABLED,
                Kind.BOOL,
                { it.logToFileEnabled },
                { s, v -> s.copy(logToFileEnabled = v as Boolean) },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_LOG_FILE_SIZE_BYTES,
                Kind.INT,
                { it.logFileSizeBytes },
                { s, v -> s.copy(logFileSizeBytes = v as Int) },
                optional = true,
                // The caps the editor offers are the presets only (the UI is the other
                // write path and enforces the same); a non-preset cap (a non-positive one
                // makes startup rewrite the log file on every line, a huge one lets it grow
                // to ~2 GB) is rejected like any other bad value, before anything is written.
                validate = {
                    if (it as Int !in LogSizePreset.bytes) {
                        throw JSONException("Invalid log_file_size_bytes")
                    }
                },
            ),
            Field(
                EncryptedPrefs.KEY_LOG_LEVEL,
                Kind.ENUM,
                { it.logLevel.name },
                { s, v -> s.copy(logLevel = v as LogLevel) },
                { name ->
                    LogLevel.entries.firstOrNull { it.name == name }
                        ?: throw JSONException("Invalid log_level")
                },
                optional = true,
            ),
            Field(
                EncryptedPrefs.KEY_RETRY_SWEEP_INTERVAL_MINUTES,
                Kind.INT,
                { it.retrySweepIntervalMinutes },
                { s, v -> s.copy(retrySweepIntervalMinutes = v as Int) },
                optional = true,
                // The periods the editor offers are the presets only (the UI is the other
                // write path and enforces the same); a non-preset period (7 minutes is below
                // WorkManager's periodic minimum and would break the sweep's enqueue) is
                // rejected like any other bad value, before anything is written.
                validate = {
                    if (it as Int !in SweepIntervalPreset.minutes) {
                        throw JSONException("Invalid retry_sweep_interval_minutes")
                    }
                },
            ),
            Field(
                EncryptedPrefs.KEY_EXACT_WAKE_ENABLED,
                Kind.BOOL,
                { it.exactWakeEnabled },
                { s, v -> s.copy(exactWakeEnabled = v as Boolean) },
                optional = true,
            ),
            // The message filter as one escaped string under its key (uniform with every
            // other scalar; a native nested object would need a new Kind and
            // special-casing in the type-check/fold machinery). Kind.ENUM, not
            // Kind.STRING: readValue invokes resolve only for the ENUM kind (the STRING
            // branch would skip the strict validation and crash in the fold with a
            // ClassCastException instead of rejecting the file).
            Field(
                EncryptedPrefs.KEY_FILTER_CONFIG,
                Kind.ENUM,
                { it.filter.toJson() },
                { s, v -> s.copy(filter = v as SmsFilter) },
                { json -> SmsFilter.parseStrict(json) },
                optional = true,
            ),
            // The message-filter feature toggle (the explicit opt-in): a plain optional
            // boolean, the product default (off) while absent.
            Field(
                EncryptedPrefs.KEY_FILTER_ENABLED,
                Kind.BOOL,
                { it.filterEnabled },
                { s, v -> s.copy(filterEnabled = v as Boolean) },
                optional = true,
            ),
        )

    private fun requireKeyType(
        obj: JSONObject,
        field: Field,
    ) {
        val value = obj.get(field.key)
        val isRightType =
            when (field.kind) {
                Kind.INT -> value is Number || (value is String && value.toIntOrNull() != null)
                Kind.BOOL -> value is Boolean || (value is String && (value.equals("true", true) || value.equals("false", true)))
                else -> value is String
            }
        if (!isRightType) {
            throw JSONException("Invalid type for ${field.key}")
        }
    }

    // The typed value of a present, right-typed key: the lenient reads above become the
    // concrete type here (a quoted scalar is parsed, the enum name resolved).
    private fun readValue(
        obj: JSONObject,
        field: Field,
    ): Any =
        when (field.kind) {
            Kind.STRING -> {
                obj.getString(field.key)
            }

            Kind.INT -> {
                val value = obj.get(field.key)
                if (value is Number) value.toInt() else (value as String).toInt()
            }

            Kind.BOOL -> {
                val value = obj.get(field.key)
                if (value is Boolean) value else (value as String).toBoolean()
            }

            Kind.ENUM -> {
                field.resolve?.invoke(obj.getString(field.key))
                    ?: throw JSONException("Invalid ${field.key}")
            }
        }

    fun exportToJson(prefs: EncryptedPrefs): String {
        val settings = prefs.load()
        val json = JSONObject().put("version", VERSION)
        FIELDS.forEach { field -> json.put(field.key, field.get(settings)) }
        return json.toString(2)
    }

    // The version must match and the six core keys (the SMTP connection and the
    // sender/destination addresses) must be present with the right type, otherwise
    // the import throws; the optional keys may be absent (a JSON null counts as
    // absent) and degrade to the product default, and a present value is always
    // type-checked and value-validated, required or optional.
    fun importFromJson(
        prefs: EncryptedPrefs,
        json: String,
    ) {
        val obj = JSONObject(json)
        if (obj.optInt("version", -1) != VERSION) {
            throw JSONException("Unsupported settings version")
        }
        FIELDS.forEach { field ->
            if (!obj.has(field.key) || obj.isNull(field.key)) {
                if (!field.optional) {
                    throw JSONException("Missing required key: ${field.key}")
                }
                return@forEach
            }
            requireKeyType(obj, field)
        }
        // Each present key is resolved, value-validated by its own rule and folded into
        // the settings in one pass; an unknown enum name or a value failing the rule
        // fails the import like any other bad value, before anything is written. The
        // absent optional keys keep the AppSettings default (the constructor IS the
        // product default).
        val settings =
            FIELDS.fold(AppSettings()) { acc, field ->
                if (obj.has(field.key) && !obj.isNull(field.key)) {
                    val value = readValue(obj, field)
                    field.validate?.invoke(value)
                    field.put(acc, value)
                } else {
                    acc
                }
            }
        prefs.save(settings)
        // Every key present in the file is a deliberate choice: none of them may later be
        // overridden by a permission-derived default (the SENDER step's ensure*); an
        // absent optional key keeps the product default and stays overridable.
        prefs.markExplicit(*FIELDS.filter { obj.has(it.key) && !obj.isNull(it.key) }.map { it.key }.toTypedArray())
    }

    // Thrown by [readImportJson] when the picked stream exceeds the size cap (the second
    // guard, for providers that report no size); [readSettingsImport] turns it into
    // [ImportReadOutcome.TooLarge].
    internal class ImportFileTooLargeException : Exception()

    // Runs the import's I/O phase on Dispatchers.IO: the size check first, then the
    // capped read (the guard for providers that report no size); a cancellation is
    // rethrown, a too-large file becomes [ImportReadOutcome.TooLarge], any other error
    // [ImportReadOutcome.Failed].
    suspend fun readSettingsImport(
        context: Context,
        uri: Uri,
    ): ImportReadOutcome =
        withContext(Dispatchers.IO) {
            try {
                val size = queryImportFileSize(context, uri)
                if (isImportFileTooLarge(size)) {
                    // User input validation, not a fault: the UI shows the failure.
                    Logger.w("SettingsImport", "Import aborted: file too large ($size bytes)")
                    ImportReadOutcome.TooLarge
                } else {
                    ImportReadOutcome.Json(readImportJson(context, uri))
                }
            } catch (e: CancellationException) {
                // A cancellation is never a failed import, even if a future
                // suspension point inside this block throws it.
                throw e
            } catch (e: ImportFileTooLargeException) {
                Logger.w(
                    "SettingsImport",
                    "Import aborted: file too large (over ${AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE} bytes)",
                )
                ImportReadOutcome.TooLarge
            } catch (e: Exception) {
                // No e.message: an I/O error can carry the picked file name.
                Logger.e("SettingsImport", "Import failed: ${e.javaClass.simpleName}")
                ImportReadOutcome.Failed
            }
        }

    // Reads the picked file in chunks with the size cap as a hard limit: providers that
    // report no size can still hand over a huge file, which must never be fully loaded
    // (OOM) or parsed (ANR). Decoding is strict UTF-8, so bad bytes fail the import
    // instead of being replaced; a missing stream yields an empty string, which the
    // caller's onJson rejects like any other bad file.
    internal fun readImportJson(
        context: Context,
        uri: Uri,
    ): String {
        val stream = context.contentResolver.openInputStream(uri) ?: return ""
        return stream.use { input ->
            val bytes = ByteArrayOutputStream()
            val chunk = ByteArray(AppConfig.SETTINGS_IMPORT_READ_CHUNK_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE) {
                    throw ImportFileTooLargeException()
                }
                bytes.write(chunk, 0, read)
            }
            Charsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        }
    }

    // Providers may report the size as null, 0 or -1; all of those mean "unknown" and the
    // capped read is attempted anyway. A valid export is a few KB at most; anything bigger
    // can only be a wrong pick, and reading it risks an ANR/OOM, so it is rejected before
    // any read (the cap lives in AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE).
    internal fun isImportFileTooLarge(size: Long?): Boolean = size != null && size > AppConfig.SETTINGS_IMPORT_MAX_FILE_SIZE

    internal fun queryImportFileSize(
        context: Context,
        uri: Uri,
    ): Long? {
        val cursor = context.querySafely(uri, arrayOf(OpenableColumns.SIZE)) ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            val index = c.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || c.isNull(index)) return null
            return c.getLong(index)
        }
    }

    private fun requireEmail(
        key: String,
        value: String,
    ) {
        if (!EmailUtil.isValidEmail(value)) {
            throw JSONException("Invalid email: $key")
        }
    }
}
