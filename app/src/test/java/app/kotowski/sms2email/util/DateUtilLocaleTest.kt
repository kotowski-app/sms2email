package app.kotowski.sms2email.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DateUtilLocaleTest {
    private lateinit var context: Context

    // Built in the local zone so the displayed day/hour match the assertions in any timezone.
    private val testTimestamp =
        ZonedDateTime.of(2024, 8, 15, 12, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun contextWithLocale(locale: Locale): Context {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }

    @Test
    fun `appLocale returns the first locale of the context`() {
        val ruContext = contextWithLocale(Locale.forLanguageTag("ru-RU"))
        assertEquals(Locale.forLanguageTag("ru-RU"), DateUtil.appLocale(ruContext))
    }

    @Test
    fun `appLocale degrades to the default locale while the locale list is empty`() {
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList()) }
        val emptyContext = context.createConfigurationContext(config)
        assertEquals(Locale.getDefault(), DateUtil.appLocale(emptyContext))
    }

    @Test
    fun `formatDate renders in the context locale with the genitive month`() {
        val result = DateUtil.formatDate(contextWithLocale(Locale.forLanguageTag("ru-RU")), testTimestamp)
        assertTrue(result.contains("августа"), "Expected the Russian genitive month in: $result")
        assertTrue(result.contains("2024"), "Should contain the year in: $result")
    }

    @Test
    fun `formatDateTime renders in the context locale`() {
        val result = DateUtil.formatDateTime(contextWithLocale(Locale.forLanguageTag("ru-RU")), testTimestamp)
        assertTrue(result.contains("августа"), "Expected the Russian genitive month in: $result")
        assertTrue(result.contains("2024"), "Should contain the year in: $result")
    }
}
