package app.kotowski.sms2email.util

import android.content.Context
import app.kotowski.sms2email.R
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DateUtilTest {
    private lateinit var mockContext: Context

    @Before
    fun setup() {
        mockContext = mock()
        org.mockito.kotlin
            .doAnswer { invocation ->
                val args = invocation.arguments.toList()
                val resId = args[0] as Int
                val varargs =
                    if (args.size == 2 && args[1] is Array<*>) {
                        (args[1] as Array<*>).map { (it as? Number)?.toInt() ?: 0 }
                    } else {
                        args.drop(1).map { (it as? Number)?.toInt() ?: 0 }
                    }
                when (resId) {
                    R.string.duration_days -> "${varargs[0]}d ${varargs[1]}h ${varargs[2]}m ${varargs[3]}s"
                    R.string.duration_hours -> "${varargs[0]}h ${varargs[1]}m ${varargs[2]}s"
                    R.string.duration_minutes -> "${varargs[0]}m ${varargs[1]}s"
                    else -> "${varargs[0]}s"
                }
            }.whenever(mockContext)
            .getString(any<Int>(), anyVararg())
    }

    @Test
    fun `utcMidnightOfTodayMillis is UTC midnight of the local date of now`() {
        val now = Instant.parse("2026-07-15T14:30:00Z")
        val millis = DateUtil.utcMidnightOfTodayMillis(now)

        val utc = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(0, utc.hour)
        assertEquals(0, utc.minute)
        assertEquals(0, utc.second)
        assertEquals(0, utc.nano)
        assertEquals(now.atZone(ZoneId.systemDefault()).toLocalDate(), utc.toLocalDate())
    }

    @Test
    fun `utcMidnightOfTodayMillis uses the local date when the UTC date differs`() {
        // 01:00–04:59 UTC is the previous day in UTC-5 and the same day in UTC+3; the
        // displayed day must be the local one in both cases.
        for (iso in listOf("2026-07-15T01:00:00Z", "2026-07-15T02:30:00Z", "2026-07-15T04:59:00Z")) {
            val now = Instant.parse(iso)
            val utc = Instant.ofEpochMilli(DateUtil.utcMidnightOfTodayMillis(now)).atZone(ZoneOffset.UTC)
            assertEquals(now.atZone(ZoneId.systemDefault()).toLocalDate(), utc.toLocalDate(), iso)
        }
    }

    @Test
    fun `localMidnightOfUtcCalendarDateMillis returns local midnight of the UTC calendar date`() {
        val utcMidnight = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
        val millis = DateUtil.localMidnightOfUtcCalendarDateMillis(utcMidnight)

        val local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        assertEquals(0, local.hour)
        assertEquals(0, local.minute)
        assertEquals(0, local.second)
        assertEquals(0, local.nano)
        assertEquals(2026, local.year)
        assertEquals(7, local.monthValue)
        assertEquals(1, local.dayOfMonth)
    }

    @Test
    fun `localMidnightOfUtcCalendarDateMillis reads the UTC date of the instant`() {
        // A picker value is a UTC-midnight instant; the applied day is its UTC calendar date.
        val timestamp = Instant.parse("2026-07-15T20:00:00Z").toEpochMilli()
        val local = Instant.ofEpochMilli(DateUtil.localMidnightOfUtcCalendarDateMillis(timestamp)).atZone(ZoneId.systemDefault())
        assertEquals(15, local.dayOfMonth)
        assertEquals(7, local.monthValue)
        assertEquals(2026, local.year)
    }

    @Test
    fun `picker round trip lands on the local midnight of the displayed day`() {
        for (iso in listOf("2026-07-15T02:00:00Z", "2026-07-15T14:30:00Z", "2026-07-15T23:45:00Z")) {
            val now = Instant.parse(iso)
            val picked = DateUtil.utcMidnightOfTodayMillis(now)
            val applied = DateUtil.localMidnightOfUtcCalendarDateMillis(picked)
            val expected =
                now
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            assertEquals(expected, applied, iso)
        }
    }

    @Test
    fun `formatLogTimestamp produces ISO 8601 UTC format independent of the device zone`() {
        val timestamp = Instant.parse("2026-07-15T12:30:45.123Z").toEpochMilli()
        val formatted = DateUtil.formatLogTimestamp(timestamp)

        assertEquals("2026-07-15T12:30:45.123Z", formatted)
    }

    @Test
    fun `formatLogTimestamp ends with the Z suffix`() {
        val timestamp = Instant.parse("2026-07-15T12:30:45.000Z").toEpochMilli()
        val formatted = DateUtil.formatLogTimestamp(timestamp)
        assertTrue(formatted.endsWith("Z"), "Timestamp should end with Z, got: $formatted")
    }

    @Test
    fun `formatDurationMillis formats seconds only`() {
        val result = DateUtil.formatDurationMillis(mockContext, 45000)
        assertEquals("45s", result)
    }

    @Test
    fun `formatDurationMillis formats zero seconds`() {
        val result = DateUtil.formatDurationMillis(mockContext, 0)
        assertEquals("0s", result)
    }

    @Test
    fun `formatDurationMillis formats minutes and seconds`() {
        val result = DateUtil.formatDurationMillis(mockContext, 125000)
        assertEquals("2m 5s", result)
    }

    @Test
    fun `formatDurationMillis formats exact minute`() {
        val result = DateUtil.formatDurationMillis(mockContext, 120000)
        assertEquals("2m 0s", result)
    }

    @Test
    fun `formatDurationMillis formats hours minutes and seconds`() {
        val result = DateUtil.formatDurationMillis(mockContext, 3665000)
        assertEquals("1h 1m 5s", result)
    }

    @Test
    fun `formatDurationMillis formats days hours minutes and seconds`() {
        // 1d + 1h + 1m + 5s = 90_065s
        val result = DateUtil.formatDurationMillis(mockContext, 90_065_000)
        assertEquals("1d 1h 1m 5s", result)
    }

    @Test
    fun `formatDurationMillis uses the days tier at exactly 24 hours`() {
        val result = DateUtil.formatDurationMillis(mockContext, 86_400_000)
        assertEquals("1d 0h 0m 0s", result)
    }

    @Test
    fun `formatDurationMillis stays on the hours tier below a day`() {
        // 23h 59m 59s
        val result = DateUtil.formatDurationMillis(mockContext, 86_399_000)
        assertEquals("23h 59m 59s", result)
    }

    @Test
    fun `formatDurationMillis truncates milliseconds`() {
        val result = DateUtil.formatDurationMillis(mockContext, 59999)
        assertEquals("59s", result)
    }

    @Test
    fun `formatDurationMillis clamps a negative span to zero`() {
        val result = DateUtil.formatDurationMillis(mockContext, -5000)
        assertEquals("0s", result)
    }

    @Test
    fun `MS_PER_DAY is 24 hours in milliseconds`() {
        assertEquals(86_400_000L, DateUtil.MS_PER_DAY)
    }

    @Test
    fun `daysAgoCutoff is the local midnight of the day days before the day of now`() {
        val now = Instant.parse("2026-07-15T12:00:00Z").toEpochMilli()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (days in listOf(0L, 1L, 29L, 1017L)) {
            val local = Instant.ofEpochMilli(DateUtil.daysAgoCutoff(now, days)).atZone(zone)
            assertEquals(0, local.hour)
            assertEquals(0, local.minute)
            assertEquals(0, local.second)
            assertEquals(0, local.nano)
            assertEquals(today.minusDays(days), local.toLocalDate())
        }
    }
}
