package app.kotowski.sms2email.ui

import android.content.res.Configuration
import android.os.LocaleList
import app.kotowski.sms2email.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// The selection-cleared toasts fire off the test thread in the VM tests, so their
// strings are pinned here instead of through the static Toast mock. The texts are
// resolved, not compared to a hardcoded sentence (the wording may change), and the
// ru check also fails on a missing override (it would fall back to the English text).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionClearedStringsTest {
    @Test
    fun `english selection cleared text is shipped`() {
        val text = RuntimeEnvironment.getApplication().getString(R.string.selection_cleared)
        assertTrue(text.isNotBlank(), "the english selection_cleared text must be shipped")
    }

    @Test
    @Config(qualifiers = "ru")
    fun `russian selection cleared text is shipped`() {
        val app = RuntimeEnvironment.getApplication()
        val russian = app.getString(R.string.selection_cleared)
        val enConfig = Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.ENGLISH)) }
        val english = app.createConfigurationContext(enConfig).resources.getString(R.string.selection_cleared)
        assertTrue(russian.isNotBlank(), "the russian selection_cleared text must be shipped")
        assertNotEquals(english, russian, "the russian selection_cleared text must be translated")
    }

    @Test
    fun `english selection cleared search text is shipped`() {
        val text = RuntimeEnvironment.getApplication().getString(R.string.selection_cleared_search)
        assertTrue(text.isNotBlank(), "the english selection_cleared_search text must be shipped")
    }

    @Test
    @Config(qualifiers = "ru")
    fun `russian selection cleared search text is shipped`() {
        val app = RuntimeEnvironment.getApplication()
        val russian = app.getString(R.string.selection_cleared_search)
        val enConfig = Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.ENGLISH)) }
        val english = app.createConfigurationContext(enConfig).resources.getString(R.string.selection_cleared_search)
        assertTrue(russian.isNotBlank(), "the russian selection_cleared_search text must be shipped")
        assertNotEquals(english, russian, "the russian selection_cleared_search text must be translated")
    }
}
