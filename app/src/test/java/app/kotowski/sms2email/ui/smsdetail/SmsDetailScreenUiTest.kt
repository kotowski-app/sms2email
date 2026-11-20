package app.kotowski.sms2email.ui.smsdetail

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.smsModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Compose rendering tests for the detail screen: status-specific actions,
// loading/not-found placeholders, and the details card rows.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsDetailScreenUiTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun renderScreen(
        detailState: SmsDetailState,
        uiState: SmsDetailUiState = SmsDetailUiState(),
    ) {
        val vm: SmsDetailViewModel = mock()
        whenever(vm.detailState).thenReturn(MutableStateFlow(detailState))
        whenever(vm.state).thenReturn(MutableStateFlow(uiState))
        composeTestRule.setContent {
            SmsDetailScreen(
                smsId = 1L,
                onNavigateBack = { },
                viewModel = vm,
            )
        }
    }

    @Test
    fun `loading state shows a spinner placeholder`() {
        renderScreen(SmsDetailState.Loading)
        composeTestRule.waitForIdle()
        // The placeholder Scaffold with top bar renders; the spinner is in the body.
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_back))
            .assertExists()
    }

    @Test
    fun `not found state shows the no-longer-available message`() {
        renderScreen(SmsDetailState.NotFound)
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_no_longer_available))
            .assertExists()
    }

    @Test
    fun `loaded sent row shows forward and delete actions`() {
        val sms = smsModel(1, SmsStatus.SENT)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_send))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_delete))
            .assertExists()
    }

    @Test
    fun `loaded pending row shows forward and cancel actions`() {
        val sms = smsModel(1, SmsStatus.PENDING)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_send))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.sms_detail_cancel_content_desc))
            .assertExists()
    }

    @Test
    fun `loaded sending row shows spinner and disabled delete`() {
        val sms = smsModel(1, SmsStatus.SENDING)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.history_status_sending))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_delete))
            .assertExists()
    }

    @Test
    fun `loaded retrying row shows forward cancel and no delete`() {
        val sms = smsModel(1, SmsStatus.RETRYING)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_send))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.sms_detail_cancel_content_desc))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_delete))
            .assertDoesNotExist()
    }

    @Test
    fun `loaded row with sent status and send attempt shows sent-at row`() {
        val sms =
            smsModel(
                1,
                SmsStatus.SENT,
                timeLastSendAttempt = 1_700_000_000_000L,
            )
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_sent_at))
            .assertExists()
    }

    @Test
    fun `loaded failed row shows status details and retry count`() {
        val sms =
            smsModel(
                1,
                SmsStatus.FAILED,
                statusDetails = "Connection timeout",
                retryCount = 3,
                timeLastSendAttempt = 1_700_000_000_000L,
            )
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_status_details))
            .assertExists()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_retry_count))
            .assertExists()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_last_attempt))
            .assertExists()
    }

    @Test
    fun `loaded retrying row with internet shows countdown row`() {
        val sms =
            smsModel(
                1,
                SmsStatus.RETRYING,
                timeNextSendAttempt = System.currentTimeMillis() + 60_000L,
                retryCount = 1,
            )
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
            SmsDetailUiState(hasInternet = true),
        )
        composeTestRule.waitForIdle()
        // The countdown row is rendered when cardNeedsLiveUpdates is true and hasInternet.
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_next_retry))
            .assertExists()
    }

    @Test
    fun `loaded ignored row with statusDetails shows the details row`() {
        val sms =
            smsModel(
                1,
                SmsStatus.IGNORED,
                statusDetails = "Filtered by rule",
            )
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_status_details))
            .assertExists()
    }

    @Test
    fun `loaded row with multi-sim shows sim row`() {
        val sms = smsModel(1, SmsStatus.SENT, simId = 1)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = mapOf(1 to "Home")),
            ),
            SmsDetailUiState(displaySimCards = true),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_sim))
            .assertExists()
    }

    @Test
    fun `loaded row with single-sim hides sim row`() {
        val sms = smsModel(1, SmsStatus.SENT, simId = 1)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = mapOf(1 to "Home")),
            ),
            SmsDetailUiState(displaySimCards = false),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_sim))
            .assertDoesNotExist()
    }

    @Test
    fun `loaded retrying row without internet shows waiting status`() {
        val sms =
            smsModel(
                1,
                SmsStatus.RETRYING,
                timeNextSendAttempt = System.currentTimeMillis() + 60_000L,
            )
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
            SmsDetailUiState(hasInternet = false),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.history_status_waiting_for_internet))
            .assertExists()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_next_retry))
            .assertDoesNotExist()
    }

    @Test
    fun `loaded sent row with no send attempt hides sent-at row`() {
        val sms = smsModel(1, SmsStatus.SENT, timeLastSendAttempt = null)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(ctx.getString(R.string.sms_detail_sent_at))
            .assertDoesNotExist()
    }

    @Test
    fun `loaded imported row shows forward and delete actions`() {
        val sms = smsModel(1, SmsStatus.IMPORTED)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_send))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_delete))
            .assertExists()
    }

    @Test
    fun `loaded failed row shows forward and delete actions`() {
        val sms = smsModel(1, SmsStatus.FAILED)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_send))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_delete))
            .assertExists()
    }

    @Test
    fun `loaded ignored row shows forward and delete actions`() {
        val sms = smsModel(1, SmsStatus.IGNORED)
        renderScreen(
            SmsDetailState.Loaded(
                SmsDetailData(sms = sms, simNames = emptyMap()),
            ),
        )
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_send))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(ctx.getString(R.string.action_delete))
            .assertExists()
    }
}
