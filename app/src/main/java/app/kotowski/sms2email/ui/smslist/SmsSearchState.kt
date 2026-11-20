package app.kotowski.sms2email.ui.smslist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

// The search-mode state shared by the History and Queue screens: owns the local
// isSearchMode/rawQuery pair, trims the active query and pushes it to the ViewModel
// (which debounces it). Closing the search (clear) also empties the query, so a dialog
// dismissal can never leave a hidden active filter behind. The pair is saveable so a
// rotation (or process death) restores the typed query instead of silently dropping
// the active filter.
internal class SmsSearchState(
    val isSearchMode: Boolean,
    val query: String,
    val rawQuery: String,
    val toggle: () -> Unit,
    val clear: () -> Unit,
    val onRawQueryChange: (String) -> Unit,
)

@Composable
internal fun rememberSmsSearchState(onQuery: (String) -> Unit): SmsSearchState {
    var isSearchMode by rememberSaveable { mutableStateOf(false) }
    var rawQuery by rememberSaveable { mutableStateOf("") }
    val activeQuery =
        if (isSearchMode) {
            rawQuery.trim()
        } else {
            ""
        }
    LaunchedEffect(activeQuery) {
        onQuery(activeQuery)
    }
    return SmsSearchState(
        isSearchMode = isSearchMode,
        query = activeQuery,
        rawQuery = rawQuery,
        toggle = {
            isSearchMode = !isSearchMode
            if (!isSearchMode) {
                rawQuery = ""
            }
        },
        clear = {
            isSearchMode = false
            rawQuery = ""
        },
        onRawQueryChange = { rawQuery = it },
    )
}
