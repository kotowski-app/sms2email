package app.kotowski.sms2email.ui.smslist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// The multi-select state shared by the History and Queue screens: entering/exiting the
// mode and toggling rows. The selection's actions (send, cancel, delete) live in the
// ViewModels — they need the application context for toasts, and each screen decides
// what its second action does.
internal class SelectionController<T>(
    private val state: MutableStateFlow<T>,
    private val getSelectedIds: (T) -> Set<Long>,
    private val withSelection: (T, mode: Boolean, ids: Set<Long>) -> T,
) {
    fun enter() {
        state.update { withSelection(it, true, getSelectedIds(it)) }
    }

    fun exit() {
        state.update { withSelection(it, false, emptySet()) }
    }

    fun toggle(smsId: Long) {
        val ids = getSelectedIds(state.value)
        if (smsId in ids && ids.size == 1) {
            exit()
            return
        }
        state.update {
            val current = getSelectedIds(it)
            val next =
                if (smsId in current) {
                    current - smsId
                } else {
                    current + smsId
                }
            withSelection(it, true, next)
        }
    }
}
