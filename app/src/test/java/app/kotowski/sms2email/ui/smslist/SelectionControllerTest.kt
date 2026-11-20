package app.kotowski.sms2email.ui.smslist

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

data class FakeSelectionState(
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
)

class SelectionControllerTest {
    private lateinit var state: MutableStateFlow<FakeSelectionState>

    private lateinit var controller: SelectionController<FakeSelectionState>

    @Before
    fun setUp() {
        state = MutableStateFlow(FakeSelectionState())
        controller =
            SelectionController(
                state,
                { it.selectedIds },
                { smsState, mode, ids -> smsState.copy(selectionMode = mode, selectedIds = ids) },
            )
    }

    @Test
    fun `enter enables selection mode keeping the ids`() {
        state.value = state.value.copy(selectedIds = setOf(1L))
        controller.enter()
        assertTrue(state.value.selectionMode)
        assertEquals(setOf(1L), state.value.selectedIds)
    }

    @Test
    fun `exit disables selection mode and clears the ids`() {
        state.value = state.value.copy(selectionMode = true, selectedIds = setOf(1L, 2L))
        controller.exit()
        assertFalse(state.value.selectionMode)
        assertTrue(state.value.selectedIds.isEmpty())
    }

    @Test
    fun `toggle selects an item`() {
        controller.toggle(1L)
        assertTrue(state.value.selectionMode)
        assertEquals(setOf(1L), state.value.selectedIds)
    }

    @Test
    fun `toggle deselects one of two selected items`() {
        controller.toggle(1L)
        controller.toggle(2L)
        controller.toggle(1L)
        assertTrue(state.value.selectionMode)
        assertEquals(setOf(2L), state.value.selectedIds)
    }

    @Test
    fun `toggle exits selection mode when the last item is deselected`() {
        controller.toggle(1L)
        controller.toggle(1L)
        assertFalse(state.value.selectionMode)
        assertTrue(state.value.selectedIds.isEmpty())
    }
}
