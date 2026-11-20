package app.kotowski.sms2email.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import app.kotowski.sms2email.util.AndroidVersion
import java.util.Locale

object LanguageHelper {
    private const val PREFS_FILE_NAME = "app_language"
    private const val PREFS_KEY_TAG = "app_language_tag"

    fun setAppLanguage(
        context: Context,
        languageTag: String?,
    ) {
        val localeList =
            if (languageTag.isNullOrEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(languageTag)
            }
        AppCompatDelegate.setApplicationLocales(localeList)

        // Persist for attachBaseContext to read on cold start
        context
            .getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit { putString(PREFS_KEY_TAG, languageTag) }

        if (!languageTag.isNullOrEmpty()) {
            // Re-pin the app-wide resources right away: below API 33 AppCompat recreates only
            // the activities, so the Application context would otherwise keep the stale pin.
            pinAppResources(context, languageTag)
        } else {
            // Undo the pinning of the app-wide resources (shared with the activities)
            // and of the JVM default locale that a stored pick applies at process
            // start, otherwise a recreated activity falls back to the stale locale.
            val systemLocale = Resources.getSystem().configuration.locales[0]
            pinAppResources(context, systemLocale.toLanguageTag())
        }
    }

    // Below API 33 AppCompat recreates the activity for a non-empty locale list, but not for
    // the empty one, so a switch to the system language needs an explicit activity recreate.
    fun needsActivityRecreate(languageTag: String?): Boolean = languageTag.isNullOrEmpty() && !AndroidVersion.is13Plus()

    fun applyLocale(context: Context): Context {
        val tag = resolvedTag(context)

        return if (!tag.isNullOrEmpty()) {
            ContextWrapper(context.createConfigurationContext(buildConfiguration(context, tag)))
        } else {
            context
        }
    }

    // Restores the persisted app language pick into the AppCompat process-local state at
    // process start (called from Application.onCreate): a fresh background process below
    // API 33 starts with the state empty until the first activity attach, and without this
    // re-apply the background strings would render in the system language. No-op from API
    // 33 (the platform owns the per-app locale process-wide there) and while the stored
    // tag is empty.
    fun applyStoredAppLanguage(context: Context) {
        if (AndroidVersion.is13Plus()) return
        val tag =
            context
                .getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_KEY_TAG, null)
        if (tag.isNullOrEmpty()) return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun applyLocaleToApplicationSafely(baseContext: Context) {
        val tag = resolvedTag(baseContext)

        if (!tag.isNullOrEmpty()) {
            pinAppResources(baseContext, tag)
        }
    }

    // AppCompatDelegate is the source of truth at runtime; SharedPreferences is fallback for cold start
    private fun resolvedTag(context: Context): String? {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        return if (!appLocales.isEmpty()) {
            appLocales[0]?.toLanguageTag()
        } else {
            context
                .getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_KEY_TAG, null)
        }
    }

    private fun buildConfiguration(
        context: Context,
        tag: String,
    ): Configuration {
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
    }

    private fun pinAppResources(
        context: Context,
        tag: String,
    ) {
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(
            buildConfiguration(context, tag),
            context.resources.displayMetrics,
        )
    }
}
