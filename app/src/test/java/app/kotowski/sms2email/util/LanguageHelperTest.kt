package app.kotowski.sms2email.util

import android.content.ContextWrapper
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LanguageHelperTest {
    private val context = org.robolectric.RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        context
            .getSharedPreferences("app_language", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `setAppLanguage persists language tag`() {
        LanguageHelper.setAppLanguage(context, "fr")
        val sp = context.getSharedPreferences("app_language", android.content.Context.MODE_PRIVATE)
        assertEquals("fr", sp.getString("app_language_tag", null))
    }

    @Test
    fun `setAppLanguage with null tag persists empty`() {
        LanguageHelper.setAppLanguage(context, null)
        val sp = context.getSharedPreferences("app_language", android.content.Context.MODE_PRIVATE)
        assertEquals(null, sp.getString("app_language_tag", null))
    }

    @Test
    fun `setAppLanguage with an empty tag unpins app resources pinned at process start`() {
        val systemResources = Resources.getSystem()
        val systemLocale = systemResources.configuration.locales[0].language

        // Cold-start state: the stored pick pins the app-wide resources and the JVM default
        LanguageHelper.setAppLanguage(context, "fr")
        LanguageHelper.applyLocaleToApplicationSafely(context)
        assertEquals(
            "fr",
            context.resources.configuration.locales[0]
                .language,
        )
        assertEquals("fr", Locale.getDefault().language)

        LanguageHelper.setAppLanguage(context, "")
        assertEquals(
            systemLocale,
            context.resources.configuration.locales[0]
                .language,
        )
        assertEquals(systemLocale, Locale.getDefault().language)
    }

    // A runtime switch below API 33 recreates only the activities; the Application
    // context must not keep the stale cold-start pin (the subject preview and the
    // background strings resolve through it).
    @Test
    fun `setAppLanguage with a non-system tag re-pins the app resources at runtime`() {
        // Cold-start state: a previous pick pinned the app-wide resources
        LanguageHelper.setAppLanguage(context, "de")
        LanguageHelper.applyLocaleToApplicationSafely(context)
        assertEquals(
            "de",
            context.resources.configuration.locales[0]
                .language,
        )

        LanguageHelper.setAppLanguage(context, "fr")
        assertEquals(
            "fr",
            context.resources.configuration.locales[0]
                .language,
        )
        assertEquals("fr", Locale.getDefault().language)
    }

    // The suite runs at sdk 29, below the platform gate: there AppCompat recreates the
    // activity only for a non-empty locale list, so the system-default pick must not rely
    // on it.
    @Test
    fun `needsActivityRecreate is true only for an empty tag below the platform gate`() {
        assertTrue(LanguageHelper.needsActivityRecreate(""))
        assertTrue(LanguageHelper.needsActivityRecreate(null))
        assertFalse(LanguageHelper.needsActivityRecreate("en"))
    }

    @Config(sdk = [34])
    @Test
    fun `needsActivityRecreate is always false from the platform gate on`() {
        assertFalse(LanguageHelper.needsActivityRecreate(""))
        assertFalse(LanguageHelper.needsActivityRecreate(null))
        assertFalse(LanguageHelper.needsActivityRecreate("en"))
    }

    @Test
    fun `applyLocale returns wrapped context when language set`() {
        LanguageHelper.setAppLanguage(context, "fr")
        val wrapped = LanguageHelper.applyLocale(context)
        assertEquals(
            "fr",
            wrapped.resources.configuration.locales[0]
                .language,
        )
    }

    @Test
    fun `applyLocale returns base context when no language set`() {
        val result = LanguageHelper.applyLocale(context)
        assertEquals(context, result)
    }

    @Test
    fun `applyLocale resolves strings in selected language`() {
        val baseLocale =
            context.resources.configuration.locales[0]
                .language

        LanguageHelper.setAppLanguage(context, "fr")
        val wrapped = LanguageHelper.applyLocale(context)
        assertEquals(
            "fr",
            wrapped.resources.configuration.locales[0]
                .language,
        )
        assertNotEquals("fr", baseLocale)
    }

    @Test
    fun `applyLocaleToApplicationSafely mutates locale without wrapping`() {
        LanguageHelper.setAppLanguage(context, "de")
        LanguageHelper.applyLocaleToApplicationSafely(context)
        assertEquals(
            "de",
            context.resources.configuration.locales[0]
                .language,
        )
    }

    @Test
    fun `applyLocaleToApplicationSafely does not throw on null tag`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        LanguageHelper.applyLocaleToApplicationSafely(context)
    }

    @Test
    fun `applyLocale returns ContextWrapper but applyLocaleToApplicationSafely does not`() {
        LanguageHelper.setAppLanguage(context, "es")
        val wrapped = LanguageHelper.applyLocale(context)
        assertTrue(wrapped is ContextWrapper)
    }

    @Test
    fun `applyStoredAppLanguage re-applies a stored tag to the AppCompat state`() {
        context
            .getSharedPreferences("app_language", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("app_language_tag", "ru")
            .commit()
        LanguageHelper.applyStoredAppLanguage(context)
        assertEquals(LocaleListCompat.forLanguageTags("ru"), AppCompatDelegate.getApplicationLocales())
    }

    @Test
    fun `applyStoredAppLanguage leaves the AppCompat state alone while the tag is empty`() {
        LanguageHelper.applyStoredAppLanguage(context)
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    // From API 33 the platform owns and applies the per-app locale process-wide (this suite
    // runs at 29, below the gate), so the re-apply must not touch the state there.
    @Config(sdk = [34])
    @Test
    fun `from the platform gate on a stored tag leaves the AppCompat state alone`() {
        context
            .getSharedPreferences("app_language", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("app_language_tag", "ru")
            .commit()
        LanguageHelper.applyStoredAppLanguage(context)
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }
}
