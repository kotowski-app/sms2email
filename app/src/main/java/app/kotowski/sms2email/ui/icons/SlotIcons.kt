package app.kotowski.sms2email.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

internal fun slotIcon(slot: Int): ImageVector? =
    when (slot) {
        1 -> Slot1
        2 -> Slot2
        3 -> Slot3
        else -> null
    }
