package app.kotowski.sms2email.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoValueFieldControllerTest {
    // The persisted source behind the controller; tests mutate it directly to simulate an
    // external change (an import) before the reload.
    private class FakeSource(
        initial: AutoValueFieldState = AutoValueFieldState(auto = false, value = ""),
    ) {
        var stored: AutoValueFieldState = initial
        val saved = mutableListOf<AutoValueFieldState>()
        var autoValue = "Device"

        fun controller(): AutoValueFieldController =
            AutoValueFieldController(
                load = { stored },
                save = {
                    stored = it
                    saved += it
                },
                autoValue = { autoValue },
            )
    }

    @Test
    fun `the initial state comes from the persisted source`() {
        val source = FakeSource(AutoValueFieldState(auto = true, value = "Device"))
        assertEquals(AutoValueFieldState(true, "Device"), source.controller().state.value)
    }

    @Test
    fun `an edit trims the value and persists it`() {
        val source = FakeSource()
        val field = source.controller()
        field.onValueChange("  hi  ")
        assertEquals("hi", field.state.value.value)
        assertEquals("hi", source.stored.value)
    }

    @Test
    fun `an edit turns auto off`() {
        val source = FakeSource(AutoValueFieldState(auto = true, value = "Device"))
        val field = source.controller()
        field.onValueChange("hi")
        assertFalse(field.state.value.auto)
        assertFalse(source.stored.auto)
    }

    @Test
    fun `an edit keeps auto off when it was off`() {
        val source = FakeSource()
        val field = source.controller()
        field.onValueChange("x")
        assertFalse(field.state.value.auto)
        assertEquals("x", field.state.value.value)
    }

    @Test
    fun `committing a value equal to the auto value turns auto on`() {
        val source = FakeSource(AutoValueFieldState(auto = false, value = "Device"))
        val field = source.controller()
        field.onCommitted("Device")
        assertTrue(field.state.value.auto)
        assertTrue(source.stored.auto)
    }

    @Test
    fun `commit trims the value before comparing`() {
        val source = FakeSource(AutoValueFieldState(auto = false, value = "  Device  "))
        val field = source.controller()
        field.onCommitted("  Device  ")
        assertTrue(field.state.value.auto)
    }

    @Test
    fun `commit does nothing when auto is already on`() {
        val source = FakeSource(AutoValueFieldState(auto = true, value = "Device"))
        val field = source.controller()
        field.onCommitted("Device")
        assertTrue(field.state.value.auto)
        assertEquals("Device", field.state.value.value)
        assertTrue(source.saved.isEmpty())
    }

    @Test
    fun `commit does nothing on a mismatch`() {
        val source = FakeSource(AutoValueFieldState(auto = false, value = "Other"))
        val field = source.controller()
        field.onCommitted("Other")
        assertFalse(field.state.value.auto)
        assertTrue(source.saved.isEmpty())
    }

    @Test
    fun `turning auto on takes the value from the auto source`() {
        val source = FakeSource()
        val field = source.controller()
        field.onAutoChanged(true)
        assertTrue(field.state.value.auto)
        assertEquals("Device", field.state.value.value)
        assertEquals("Device", source.stored.value)
    }

    @Test
    fun `turning auto off keeps the value untouched`() {
        val source = FakeSource(AutoValueFieldState(auto = true, value = "kept"))
        val field = source.controller()
        field.onAutoChanged(false)
        assertFalse(field.state.value.auto)
        assertEquals("kept", field.state.value.value)
        assertEquals("kept", source.stored.value)
    }

    @Test
    fun `every change persists the full state`() {
        val source = FakeSource()
        val field = source.controller()
        field.onValueChange("  a  ")
        field.onAutoChanged(true)
        assertEquals(
            listOf(
                AutoValueFieldState(false, "a"),
                AutoValueFieldState(true, "Device"),
            ),
            source.saved,
        )
    }

    @Test
    fun `reload re-seeds from the persisted source`() {
        val source = FakeSource(AutoValueFieldState(auto = false, value = "old"))
        val field = source.controller()
        source.stored = AutoValueFieldState(auto = true, value = "imported")
        field.reload()
        assertEquals(AutoValueFieldState(true, "imported"), field.state.value)
    }

    // The same source behind a controller with a validator, so the rejected-value behavior
    // is testable like the country field's.
    private class ValidatedSource(
        initial: AutoValueFieldState = AutoValueFieldState(auto = false, value = ""),
    ) {
        var stored: AutoValueFieldState = initial
        val saved = mutableListOf<AutoValueFieldState>()
        var autoValue = "DE"

        fun controller(): AutoValueFieldController =
            AutoValueFieldController(
                load = { stored },
                save = {
                    stored = it
                    saved += it
                },
                autoValue = { autoValue },
                validate = { it.isEmpty() || it in listOf("DE", "FR") },
            )
    }

    @Test
    fun `a rejected edit updates the live state but is not persisted`() {
        val source = ValidatedSource()
        val field = source.controller()
        field.onValueChange("XX")
        assertEquals("XX", field.state.value.value)
        assertFalse(field.state.value.auto)
        assertTrue(source.saved.isEmpty())
        assertEquals("", source.stored.value)
    }

    @Test
    fun `an accepted edit persists`() {
        val source = ValidatedSource()
        val field = source.controller()
        field.onValueChange("FR")
        assertEquals("FR", source.stored.value)
        assertFalse(source.stored.auto)
    }

    @Test
    fun `a rejected value is dropped by the reload`() {
        val source = ValidatedSource()
        val field = source.controller()
        field.onValueChange("XX")
        field.reload()
        assertEquals("", field.state.value.value)
    }

    @Test
    fun `an accepted edit after a rejected one persists the accepted value`() {
        val source = ValidatedSource()
        val field = source.controller()
        field.onValueChange("XX")
        field.onValueChange("FR")
        assertEquals("FR", source.stored.value)
        assertEquals("FR", field.state.value.value)
    }
}
