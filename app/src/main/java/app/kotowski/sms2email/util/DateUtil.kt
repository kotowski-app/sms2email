package app.kotowski.sms2email.util

import android.content.Context
import android.text.format.DateFormat
import app.kotowski.sms2email.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateUtil {
    // The unit for the retry period and import-window math.
    const val MS_PER_DAY: Long = 24 * 60 * 60 * 1000L

    private val englishFormatter24 = DateTimeFormatter.ofPattern("MMMM d, yyyy HH:mm", Locale.ENGLISH)
    private val englishFormatter12 = DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a", Locale.ENGLISH)
    private val englishDateOnly = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

    private val logTimestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)

    // The locale the dates render in: the app language picked in Settings (the passed
    // context already carries it) or, while the app follows the system language, the device
    // locale; an empty (theoretical) locale list degrades to the default locale.
    internal fun appLocale(context: Context): Locale {
        val config = context.resources.configuration
        return config.locales[0] ?: Locale.getDefault()
    }

    // The date/datetime pattern from the platform's ICU for the locale (the same data the
    // system's own formatting uses — Russian: the genitive "12 сентября"), with the LLLL ->
    // MMMM repair: older CLDR snapshots synthesize the stand-alone month letter, which is
    // wrong next to a day numeral. The rendering must go through java.text, which the
    // framework backs with that same ICU data — the platform's java.time carries a stale
    // CLDR snapshot that still renders Russian months in the nominative case ("12 сентябрь"),
    // so it must not format the month letters. A lookup failure throws; the caller degrades
    // to its fixed English formatter.
    private fun icuPattern(
        locale: Locale,
        skeleton: String,
    ): String = DateFormat.getBestDateTimePattern(locale, skeleton).replace("LLLL", "MMMM")

    fun formatDurationMillis(
        context: Context,
        millis: Long,
    ): String {
        val safeMillis = if (millis < 0) 0L else millis
        val totalSeconds = safeMillis / 1000
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            days > 0 -> context.getString(R.string.duration_days, days, hours, minutes, seconds)
            hours > 0 -> context.getString(R.string.duration_hours, hours, minutes, seconds)
            minutes > 0 -> context.getString(R.string.duration_minutes, minutes, seconds)
            else -> context.getString(R.string.duration_seconds, seconds)
        }
    }

    // The local midnight of the calendar day `days` before the day of `now`; the cutoff
    // for retention and import windows. Calendar-day based: a "30 days" window starts at
    // the midnight 30 days ago, not at an instant 30*24h before now.
    fun daysAgoCutoff(
        now: Long,
        days: Long,
    ): Long {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val cutoff = day.minusDays(days).atStartOfDay(zone)
        return cutoff.toInstant().toEpochMilli()
    }

    fun formatDateTime(
        context: Context,
        timestamp: Long,
    ): String {
        val locale = appLocale(context)
        return try {
            val is24Hour = DateFormat.is24HourFormat(context)
            val skeleton = if (is24Hour) "yMMMMdHm" else "yMMMMdhma"
            val pattern = icuPattern(locale, skeleton)
            SimpleDateFormat(pattern, locale).format(timestamp)
        } catch (e: Exception) {
            val fallback = if (DateFormat.is24HourFormat(context)) englishFormatter24 else englishFormatter12
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(fallback)
        }
    }

    // See icuPattern for why the rendering goes through java.text.
    fun formatDate(
        context: Context,
        timestamp: Long,
    ): String {
        val locale = appLocale(context)
        return try {
            val pattern = icuPattern(locale, "yMMMMd")
            SimpleDateFormat(pattern, locale).format(timestamp)
        } catch (e: Exception) {
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(englishDateOnly)
        }
    }

    // M3 DatePicker works in UTC-midnight millis: a value is an instant whose UTC calendar
    // date is the displayed day. These two convert between that value and local calendar
    // days; passing raw wall-clock millis (or truncating the value in the local zone) shifts
    // the day by one during the first/last |UTC offset| hours of a local day.
    fun utcMidnightOfTodayMillis(now: Instant = Instant.now()): Long {
        val localDate = now.atZone(ZoneId.systemDefault()).toLocalDate()
        return localDate
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    fun localMidnightOfUtcCalendarDateMillis(utcCalendarDateMillis: Long): Long {
        val utcDate = Instant.ofEpochMilli(utcCalendarDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return utcDate
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    // Log timestamps are UTC (the Z suffix): a device timezone change mid-run must not mix
    // two zones in one log file.
    fun formatLogTimestamp(timestamp: Long): String {
        val instant = Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.UTC)
        return instant.format(logTimestampFormatter)
    }
}
