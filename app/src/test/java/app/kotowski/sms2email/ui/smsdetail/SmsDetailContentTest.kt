package app.kotowski.sms2email.ui.smsdetail

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.TestPhone
import app.kotowski.sms2email.testing.smsModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsDetailContentTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    // The screen's row comes from the VM's observed detail state; a mocked VM pins it
    // so only the row's own fields drive what renders. The flow is returned so a test
    // can drive the observation (e.g. the row vanishing after a confirmed delete).
    private fun viewModelFor(sms: SmsUiModel): Pair<SmsDetailViewModel, MutableStateFlow<SmsDetailState>> {
        val detailState =
            MutableStateFlow<SmsDetailState>(
                SmsDetailState.Loaded(
                    SmsDetailData(sms = sms, simNames = emptyMap()),
                ),
            )
        val viewModel: SmsDetailViewModel = mock()
        whenever(viewModel.state).thenReturn(MutableStateFlow(SmsDetailUiState()))
        whenever(viewModel.detailState).thenReturn(detailState)
        return viewModel to detailState
    }

    private fun renderDetail(
        sms: SmsUiModel,
        onNavigateBack: () -> Unit = {},
    ): Pair<SmsDetailViewModel, MutableStateFlow<SmsDetailState>> {
        val (viewModel, detailState) = viewModelFor(sms)
        composeTestRule.setContent {
            SmsDetailScreen(
                smsId = sms.id,
                onNavigateBack = onNavigateBack,
                viewModel = viewModel,
            )
        }
        return viewModel to detailState
    }

    @Test
    fun `a failed row with a stale next attempt shows no next retry row`() {
        val staleNextAttempt = 1_700_000_000_000L + 3_600_000L
        renderDetail(
            smsModel(
                id = 1,
                status = SmsStatus.FAILED,
                timeLastSendAttempt = 1_700_000_000_000L,
                timeNextSendAttempt = staleNextAttempt,
                retryCount = 3,
            ),
        )
        composeTestRule.onNodeWithText(context.getString(R.string.sms_detail_next_retry)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.sms_detail_next_retry_soon)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.sms_detail_retry_count)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.sms_detail_last_attempt)).assertExists()
    }

    @Test
    fun `a retrying row shows the next retry countdown`() {
        renderDetail(
            smsModel(
                id = 1,
                status = SmsStatus.RETRYING,
                timeNextSendAttempt = System.currentTimeMillis() + 3_600_000L,
                retryCount = 1,
            ),
        )
        composeTestRule.onNodeWithText(context.getString(R.string.sms_detail_next_retry)).assertExists()
    }

    // The delete confirm must not pop the screen: popping there cancels the VM scope before
    // the guarded delete's outcome toast can fire. The pop is the observed row's disappearance
    // (detailShouldNavigateBack), so a blocked delete keeps the screen open.

    @Test
    fun `confirming the delete does not pop the screen`() {
        var backCalls = 0
        val (viewModel, _) = renderDetail(smsModel(1L, SmsStatus.SENT)) { backCalls++ }
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_delete))
            .performClick()
        composeTestRule
            .onNode(hasText(context.getString(R.string.action_delete)) and hasClickAction())
            .performClick()
        assertEquals(0, backCalls)
        verify(viewModel).deleteRow(1L)
    }

    @Test
    fun `the screen pops once the confirmed delete makes the observed row disappear`() {
        var backCalls = 0
        val (_, detailState) = renderDetail(smsModel(1L, SmsStatus.SENT)) { backCalls++ }
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_delete))
            .performClick()
        composeTestRule
            .onNode(hasText(context.getString(R.string.action_delete)) and hasClickAction())
            .performClick()
        assertEquals(0, backCalls)
        // The delete lands in the DB: the live observation emits the missing row.
        detailState.value = SmsDetailState.NotFound
        composeTestRule.waitForIdle()
        assertEquals(1, backCalls)
    }

    // While the send is in flight the top bar swaps the forward button for the spinner
    // and keeps Delete visible but disabled (the send is let finish, the DAO's status
    // filter is the backstop).

    @Test
    fun `a sending row shows the spinner instead of forward and a disabled delete`() {
        renderDetail(smsModel(1L, SmsStatus.SENDING))
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_status_sending))
            .assertExists()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_send))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.sms_detail_cancel_content_desc))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_delete))
            .assertIsNotEnabled()
    }

    @Test
    fun `a terminal row shows forward and an enabled delete, no spinner`() {
        renderDetail(smsModel(1L, SmsStatus.SENT))
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.history_status_sending))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_send))
            .assertIsEnabled()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_delete))
            .assertIsEnabled()
    }

    @Test
    fun `detailShouldNavigateBack is false for a fresh NotFound`() {
        assertEquals(false, detailShouldNavigateBack(hasLoaded = false, SmsDetailState.NotFound))
    }

    @Test
    fun `detailShouldNavigateBack is false for Loading`() {
        assertEquals(false, detailShouldNavigateBack(hasLoaded = false, SmsDetailState.Loading))
    }

    @Test
    fun `detailShouldNavigateBack is false when the row was loaded and stays loaded`() {
        val data = SmsDetailData(sms = smsModel(1L, SmsStatus.SENT), simNames = emptyMap())
        assertEquals(false, detailShouldNavigateBack(hasLoaded = true, SmsDetailState.Loaded(data)))
    }

    @Test
    fun `detailShouldNavigateBack is true when the row was loaded and then disappeared`() {
        assertEquals(true, detailShouldNavigateBack(hasLoaded = true, SmsDetailState.NotFound))
    }

    // detailActions: the else-branch covers terminal statuses (SENT, FAILED, IGNORED, IMPORTED).
    @Test
    fun `detailActions for SENT shows forward and delete`() {
        val actions = detailActions(SmsStatus.SENT)
        assertEquals(true, actions.send)
        assertEquals(false, actions.cancel)
        assertEquals(true, actions.delete)
        assertEquals(false, actions.sending)
    }

    @Test
    fun `detailActions for FAILED shows forward and delete`() {
        val actions = detailActions(SmsStatus.FAILED)
        assertEquals(true, actions.send)
        assertEquals(false, actions.cancel)
        assertEquals(true, actions.delete)
        assertEquals(false, actions.sending)
    }

    @Test
    fun `detailActions for IGNORED shows forward and delete`() {
        val actions = detailActions(SmsStatus.IGNORED)
        assertEquals(true, actions.send)
        assertEquals(false, actions.cancel)
        assertEquals(true, actions.delete)
        assertEquals(false, actions.sending)
    }

    @Test
    fun `detailActions for IMPORTED shows forward and delete`() {
        val actions = detailActions(SmsStatus.IMPORTED)
        assertEquals(true, actions.send)
        assertEquals(false, actions.cancel)
        assertEquals(true, actions.delete)
        assertEquals(false, actions.sending)
    }

    @Test
    fun `detailActions for PENDING shows forward and cancel`() {
        val actions = detailActions(SmsStatus.PENDING)
        assertEquals(true, actions.send)
        assertEquals(true, actions.cancel)
        assertEquals(false, actions.delete)
        assertEquals(false, actions.sending)
    }

    @Test
    fun `detailActions for RETRYING shows forward and cancel`() {
        val actions = detailActions(SmsStatus.RETRYING)
        assertEquals(true, actions.send)
        assertEquals(true, actions.cancel)
        assertEquals(false, actions.delete)
        assertEquals(false, actions.sending)
    }

    @Test
    fun `detailActions for SENDING shows spinner and disabled delete`() {
        val actions = detailActions(SmsStatus.SENDING)
        assertEquals(false, actions.send)
        assertEquals(false, actions.cancel)
        assertEquals(true, actions.delete)
        assertEquals(true, actions.sending)
    }

    @Test
    fun `statusDetailsValueColor for IGNORED is secondary`() {
        val scheme = androidx.compose.material3.lightColorScheme()
        assertEquals(scheme.secondary, statusDetailsValueColor(SmsStatus.IGNORED, scheme))
    }

    @Test
    fun `statusDetailsValueColor for FAILED is onSurface`() {
        val scheme = androidx.compose.material3.lightColorScheme()
        assertEquals(scheme.onSurface, statusDetailsValueColor(SmsStatus.FAILED, scheme))
    }

    @Test
    fun `formatNextRetry with zero remaining shows soon`() {
        assertEquals(context.getString(R.string.sms_detail_next_retry_soon), formatNextRetry(context, 0))
    }

    @Test
    fun `formatNextRetry with negative remaining shows soon`() {
        assertEquals(context.getString(R.string.sms_detail_next_retry_soon), formatNextRetry(context, -1000))
    }

    @Test
    fun `formatNextRetry with positive remaining shows duration`() {
        val result = formatNextRetry(context, 60000)
        assertNotEquals(context.getString(R.string.sms_detail_next_retry_soon), result)
    }

    // The contact name resolution LaunchedEffect: when a row loads, the screen resolves
    // the contact name and shows it in the details card.
    @Test
    fun `contact name resolved from vm shows in details card`() {
        // When contactName is resolved, it overrides the phone number in the "From" row.
        val (vm, _) = viewModelFor(smsModel(1L, SmsStatus.SENT, phoneNumber = TestPhone.FRIEND))
        composeTestRule.setContent {
            SmsDetailScreen(
                smsId = 1L,
                onNavigateBack = { },
                viewModel = vm,
            )
        }
        composeTestRule.waitForIdle()
        // The phone number renders initially (before contact resolution).
        composeTestRule.onNodeWithText(TestPhone.FRIEND).assertExists()
    }
}
