package app.kotowski.sms2email.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotIconsTest {
    @Test
    fun `slot 1 icon builds with expected metadata`() {
        assertIcon(Slot1, "Slot1")
    }

    @Test
    fun `slot 2 icon builds with expected metadata`() {
        assertIcon(Slot2, "Slot2")
    }

    @Test
    fun `slot 3 icon builds with expected metadata`() {
        assertIcon(Slot3, "Slot3")
    }

    @Test
    fun `slotIcon maps slots 1 to 3 to the slot icons`() {
        assertSame(Slot1, slotIcon(1))
        assertSame(Slot2, slotIcon(2))
        assertSame(Slot3, slotIcon(3))
    }

    @Test
    fun `slotIcon returns null for slots without an icon`() {
        assertNull(slotIcon(0))
        assertNull(slotIcon(4))
    }

    @Test
    fun `slot icons are stable distinct singletons`() {
        assertSame(Slot1, Slot1)
        assertFalse(Slot1 === Slot2)
        assertFalse(Slot2 === Slot3)
        assertFalse(Slot1 === Slot3)
    }

    private fun assertIcon(
        icon: ImageVector,
        name: String,
    ) {
        assertEquals(name, icon.name)
        assertEquals(24f, icon.viewportWidth)
        assertEquals(24f, icon.viewportHeight)
        assertTrue(icon.root.size >= 1)
        icon.root.forEach { node ->
            assertTrue(node is VectorPath && node.pathData.isNotEmpty())
        }
    }
}
