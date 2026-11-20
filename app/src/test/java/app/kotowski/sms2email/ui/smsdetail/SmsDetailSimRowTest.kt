package app.kotowski.sms2email.ui.smsdetail

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsDetailSimRowTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun render(
        sms: SmsUiModel,
        displaySimCards: Boolean,
        simNames: Map<Int, String>,
        simSlots: Map<Int, Int>,
    ) {
        val viewModel: SmsDetailViewModel = mock()
        whenever(viewModel.state)
            .thenReturn(MutableStateFlow(SmsDetailUiState(displaySimCards = displaySimCards)))
        whenever(viewModel.detailState)
            .thenReturn(
                MutableStateFlow(
                    SmsDetailState.Loaded(SmsDetailData(sms = sms, simNames = simNames, simSlots = simSlots)),
                ),
            )
        composeTestRule.setContent {
            SmsDetailScreen(
                smsId = sms.id,
                onNavigateBack = { },
                viewModel = viewModel,
            )
        }
    }

    @Test
    fun `a resolved slot shows the sim name with the slot in brackets`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 2),
            displaySimCards = true,
            simNames = mapOf(2 to "MySIM"),
            simSlots = mapOf(2 to 2),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.sms_detail_sim_with_slot, "MySIM", 2))
            .assertExists()
    }

    @Test
    fun `the bracketed slot is the resolved tray, not the sub id`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 2),
            displaySimCards = true,
            simNames = mapOf(2 to "MySIM"),
            simSlots = mapOf(2 to 1),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.sms_detail_sim_with_slot, "MySIM", 1))
            .assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.sms_detail_sim_with_slot, "MySIM", 2))
            .assertDoesNotExist()
    }

    @Test
    fun `a resolved name without a slot shows the name plain`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 2),
            displaySimCards = true,
            simNames = mapOf(2 to "MySIM"),
            simSlots = emptyMap(),
        )
        composeTestRule.onNodeWithText("MySIM").assertExists()
    }

    @Test
    fun `an unresolved sim name shows the unknown label without a slot`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 2),
            displaySimCards = true,
            simNames = mapOf(2 to context.getString(R.string.history_unknown_sim)),
            simSlots = emptyMap(),
        )
        composeTestRule.onNodeWithText(context.getString(R.string.history_unknown_sim)).assertExists()
    }

    @Test
    fun `an unknown slot shows the unknown label`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = -1),
            displaySimCards = true,
            simNames = emptyMap(),
            simSlots = emptyMap(),
        )
        composeTestRule.onNodeWithText(context.getString(R.string.history_unknown_sim)).assertExists()
    }

    @Test
    fun `sub id 0 shows the name with the resolved slot`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 0),
            displaySimCards = true,
            simNames = mapOf(0 to "SIM 0"),
            simSlots = mapOf(0 to 1),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.sms_detail_sim_with_slot, "SIM 0", 1))
            .assertExists()
    }

    @Test
    fun `a single sim device hides the sim row entirely`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 2),
            displaySimCards = false,
            simNames = mapOf(2 to "MySIM"),
            simSlots = mapOf(2 to 2),
        )
        composeTestRule.onNodeWithText(context.getString(R.string.sms_detail_sim)).assertDoesNotExist()
    }
}
