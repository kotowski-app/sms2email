package app.kotowski.sms2email.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.kotowski.sms2email.LanguageOption
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ui.findActivity
import app.kotowski.sms2email.util.LanguageHelper

// The dropdown label of the app language (a stored tag outside the shipped languages
// degrades to the system-default label, like the state does).
internal fun languageLabelRes(tag: String): Int =
    LanguageOption.entries.firstOrNull { it.tag == tag }?.labelRes ?: R.string.settings_system_default

// The language picker's menu options (shared by the settings and onboarding pickers): a
// pick stores the tag, and below API 33 a switch to the system default needs an explicit
// activity recreate (AppCompat only recreates for a non-empty locale list there).
@Composable
internal fun languagePickerItems(onPick: (String) -> Unit): List<Pair<String, () -> Unit>> {
    val context = LocalContext.current
    return LanguageOption.entries.map {
        stringResource(it.labelRes) to {
            onPick(it.tag)
            if (LanguageHelper.needsActivityRecreate(it.tag)) context.findActivity()?.recreate()
        }
    }
}
