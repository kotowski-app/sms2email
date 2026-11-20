package app.kotowski.sms2email.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// The standard settings-row inset: the vertical gap plus the horizontal margin on top
// of the settings screen's 16dp column padding. Every settings row applies it (the
// dropdown rows pass it as their modifier) so the screen keeps one rhythm.
internal val SettingsRowPadding: Modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)
