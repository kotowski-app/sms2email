package app.kotowski.sms2email.ui.scamwarning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotowski.sms2email.AppConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The screen's countdown owner.
 *
 * The read-the-warning gate ticks down on the live tick interval, floors at 0,
 * and every reset trigger (pause, back, unlock, proceed) restarts it.
 */
@HiltViewModel
class ScamWarningViewModel
    @Inject
    constructor() : ViewModel() {
        private val _secondsRemaining = MutableStateFlow(AppConfig.SCAM_WARNING_TIMER_SECONDS)

        val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

        init {
            viewModelScope.launch {
                while (true) {
                    delay(AppConfig.LIVE_TICK_INTERVAL_MS)
                    _secondsRemaining.update { if (it > 0) it - 1 else it }
                }
            }
        }

        fun reset() {
            _secondsRemaining.value = AppConfig.SCAM_WARNING_TIMER_SECONDS
        }
    }
