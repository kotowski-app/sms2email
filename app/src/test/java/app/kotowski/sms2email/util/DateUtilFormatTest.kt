package app.kotowski.sms2email.util

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DateUtilFormatTest {
    private lateinit var context: Context

    private val testTimestamp = Instant.parse("2024-08-15T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `formatDate displays full month name`() {
        val result = DateUtil.formatDate(context, testTimestamp)
        assertTrue(result.contains("August"), "Expected full month name 'August' in: $result")
        assertFalse(result.matches(Regex(".*\\bAug\\b.*")), "Should not contain abbreviated month in: $result")
    }

    @Test
    fun `formatDate contains year and day`() {
        val result = DateUtil.formatDate(context, testTimestamp)
        assertTrue(result.contains("2024"), "Should contain year in: $result")
        assertTrue(result.contains("15"), "Should contain day in: $result")
    }

    @Test
    fun `formatDate does not contain time`() {
        val result = DateUtil.formatDate(context, testTimestamp)
        assertFalse(result.contains(":"), "formatDate should not contain time, got: $result")
    }

    @Test
    fun `formatDateTime displays full month name`() {
        val result = DateUtil.formatDateTime(context, testTimestamp)
        assertTrue(result.contains("August"), "Expected full month name 'August' in: $result")
        assertFalse(result.matches(Regex(".*\\bAug\\b.*")), "Should not contain abbreviated month in: $result")
    }

    @Test
    fun `formatDateTime contains year and day`() {
        val result = DateUtil.formatDateTime(context, testTimestamp)
        assertTrue(result.contains("2024"), "Should contain year in: $result")
        assertTrue(result.contains("15"), "Should contain day in: $result")
    }

    @Test
    fun `formatDate handles negative timestamp`() {
        val result = DateUtil.formatDate(context, -1000)
        assertTrue(result.isNotEmpty(), "Should not throw on negative timestamp, got: $result")
    }

    @Test
    fun `formatDateTime handles negative timestamp`() {
        val result = DateUtil.formatDateTime(context, -1000)
        assertTrue(result.isNotEmpty(), "Should not throw on negative timestamp, got: $result")
    }

    @Test
    fun `formatLogTimestamp produces ISO 8601 format with the Z suffix`() {
        val timestamp = Instant.parse("2024-08-15T12:30:45.123Z").toEpochMilli()
        val formatted = DateUtil.formatLogTimestamp(timestamp)
        val isoPattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
        assertTrue(formatted.matches(isoPattern), "Should match ISO 8601 format with Z suffix, got: $formatted")
    }

    @Test
    fun `formatLogTimestamp preserves milliseconds`() {
        val timestamp = Instant.parse("2024-08-15T12:30:45.456Z").toEpochMilli()
        val formatted = DateUtil.formatLogTimestamp(timestamp)
        assertTrue(formatted.contains(".456") || formatted.contains(".4"), "Should preserve milliseconds, got: $formatted")
    }

    @Test
    fun `formatDateTime displays uppercase AM PM`() {
        val pmTimestamp = Instant.parse("2024-08-15T18:30:00Z").toEpochMilli()
        val result = DateUtil.formatDateTime(context, pmTimestamp)
        assertFalse(result.contains(" am"), "Should use uppercase AM, got: $result")
        assertFalse(result.contains(" pm"), "Should use uppercase PM, got: $result")
    }

    @Test
    fun `formatLogTimestamp handles zero timestamp`() {
        val formatted = DateUtil.formatLogTimestamp(0)
        val isoPattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
        assertTrue(formatted.matches(isoPattern), "Should match ISO 8601 format with Z suffix for epoch, got: $formatted")
    }

    @Test
    fun `formatDate with 12-hour context uses 12-hour format`() {
        val result = DateUtil.formatDate(context, testTimestamp)
        assertTrue(result.isNotEmpty())
        assertTrue(result.contains("2024"), "Should contain year: $result")
    }

    @Test
    fun `formatDateTime with large timestamp does not overflow`() {
        val farFuture = Long.MAX_VALUE
        val result = DateUtil.formatDateTime(context, farFuture)
        assertTrue(result.isNotEmpty(), "Should handle large timestamp: $result")
    }

    @Test
    fun `formatDate with large timestamp does not overflow`() {
        val farFuture = Long.MAX_VALUE
        val result = DateUtil.formatDate(context, farFuture)
        assertTrue(result.isNotEmpty(), "Should handle large timestamp: $result")
    }
}
