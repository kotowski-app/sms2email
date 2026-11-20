package app.kotowski.sms2email.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The state of the auto-value field: auto marks the auto mode (the field
// carries the auto value and the UI disables it), value the committed content.
data class AutoValueFieldState(
    val auto: Boolean,
    val value: String,
)

// The shared auto-value field: a persisted text field with an optional auto
// value. While auto is on, the field carries the auto value; typing trims and
// persists, and turns auto off (a manual value is the explicit opt-out);
// committing a value equal to the auto value (both trimmed) turns auto back
// on. The content persists on every accepted change through save(), so a
// half-typed value is never lost. An optional validate() keeps a rejected
// value out of the persisted source: it still lands in the live state (the
// UI shows and flags it) and a reload drops it back to the persisted value.
// load()/save()/autoValue() keep the domain (which pref, what the auto value
// is) on the caller's side.
class AutoValueFieldController(
    private val load: () -> AutoValueFieldState,
    private val save: (AutoValueFieldState) -> Unit,
    private val autoValue: () -> String,
    // The value gate for save(): a rejected value updates the live state but
    // is never persisted, so the source keeps the last accepted value.
    private val validate: (String) -> Boolean = { true },
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AutoValueFieldState> = _state.asStateFlow()

    // Re-seeds from the persisted source (an import, an external refresh): the
    // caller pairs this with its own draft re-read.
    fun reload() {
        _state.value = load()
    }

    // Turning auto on takes the value from the auto source; turning it off
    // keeps the value the user edits from there.
    fun onAutoChanged(auto: Boolean) {
        val current = _state.value
        commit(current.copy(auto = auto, value = if (auto) autoValue() else current.value))
    }

    // An edit trims and persists, and turns auto off (the UI disables the
    // field while auto is on, so this fires from the manual mode; the rule
    // keeps holding for a caller that wires it differently).
    fun onValueChange(value: String) {
        commit(_state.value.copy(auto = false, value = value.trim()))
    }

    // The IME commit: a value equal to the auto value (both trimmed) turns
    // auto back on; the value itself already persisted keystroke by keystroke.
    fun onCommitted(value: String) {
        val current = _state.value
        if (!current.auto && value.trim() == autoValue()) {
            commit(current.copy(auto = true))
        }
    }

    // The live state always takes the change (the UI shows and flags it); the
    // persisted source takes it only when the value passes the validator.
    private fun commit(next: AutoValueFieldState) {
        _state.value = next
        if (validate(next.value)) save(next)
    }
}
