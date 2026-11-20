package app.kotowski.sms2email.ui.scamwarning

import app.kotowski.sms2email.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScamWarningViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun tick() {
        testDispatcher.scheduler.advanceTimeBy(AppConfig.LIVE_TICK_INTERVAL_MS + 1)
    }

    @Test
    fun `starts at the full warning timer`() {
        val viewModel = ScamWarningViewModel()

        assertEquals(AppConfig.SCAM_WARNING_TIMER_SECONDS, viewModel.secondsRemaining.value)
    }

    @Test
    fun `decrements once per tick`() {
        val viewModel = ScamWarningViewModel()

        tick()
        tick()

        assertEquals(AppConfig.SCAM_WARNING_TIMER_SECONDS - 2, viewModel.secondsRemaining.value)
    }

    @Test
    fun `floors at zero and never goes negative`() {
        val viewModel = ScamWarningViewModel()

        repeat(AppConfig.SCAM_WARNING_TIMER_SECONDS + 5) { tick() }

        assertEquals(0, viewModel.secondsRemaining.value)
    }

    @Test
    fun `reset restores the full timer and the countdown continues`() {
        val viewModel = ScamWarningViewModel()
        tick()
        tick()

        viewModel.reset()

        assertEquals(AppConfig.SCAM_WARNING_TIMER_SECONDS, viewModel.secondsRemaining.value)
        tick()
        assertEquals(AppConfig.SCAM_WARNING_TIMER_SECONDS - 1, viewModel.secondsRemaining.value)
    }
}
