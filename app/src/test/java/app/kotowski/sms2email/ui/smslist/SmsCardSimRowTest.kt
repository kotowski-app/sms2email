package app.kotowski.sms2email.ui.smslist

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.smsModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The list card's SIM row: the SIM icon (the no-SIM icon for a missing SIM) plus the
// numbered tray badge from the resolved slot map, only for rows with a subscription.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsCardSimRowTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun render(
        sms: SmsUiModel,
        displaySimCards: Boolean,
        simNames: Map<Int, String>,
        simSlots: Map<Int, Int>,
    ) {
        val paged =
            PagedSmsListState(
                isLoading = false,
                smsList = listOf(sms),
                simNames = simNames,
                simSlots = simSlots,
            )
        composeTestRule.setContent {
            SmsListContent(
                list = SmsListUiState(paged = paged, displaySimCards = displaySimCards),
                searchQuery = "",
                onSmsClicked = { },
                onLongPress = { },
                onToggleSelection = { },
                onLoadMore = { },
            ) { Text("empty slot") }
        }
    }

    @Test
    fun `a resolved slot shows the sim icon and the slot badge next to the name`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 2),
            displaySimCards = true,
            simNames = mapOf(2 to "SIM 2"),
            simSlots = mapOf(2 to 2),
        )
        composeTestRule.onNodeWithTag("sms_sim_icon").assertExists()
        composeTestRule.onNodeWithTag("sms_slot_icon").assertExists()
        composeTestRule.onNodeWithText("SIM 2").assertExists()
    }

    @Test
    fun `an active sim without a resolvable slot shows the sim icon without the slot badge`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 4),
            displaySimCards = true,
            simNames = mapOf(4 to "SIM 4"),
            simSlots = emptyMap(),
        )
        composeTestRule.onNodeWithTag("sms_sim_icon").assertExists()
        composeTestRule.onNodeWithTag("sms_slot_icon").assertDoesNotExist()
        composeTestRule.onNodeWithText("SIM 4").assertExists()
    }

    @Test
    fun `a missing sim shows the no-sim icon and the unknown label without a slot badge`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 2),
            displaySimCards = true,
            simNames = mapOf(2 to context.getString(R.string.history_unknown_sim)),
            simSlots = emptyMap(),
        )
        composeTestRule.onNodeWithTag("sms_sim_icon").assertExists()
        composeTestRule.onNodeWithTag("sms_slot_icon").assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.history_unknown_sim)).assertExists()
    }

    @Test
    fun `a missing sim without a name entry renders the unknown label without a slot badge`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 2),
            displaySimCards = true,
            simNames = emptyMap(),
            simSlots = emptyMap(),
        )
        composeTestRule.onNodeWithTag("sms_sim_icon").assertExists()
        composeTestRule.onNodeWithTag("sms_slot_icon").assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.history_unknown_sim)).assertExists()
    }

    @Test
    fun `an unknown slot hides the whole sim row`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = -1),
            displaySimCards = true,
            simNames = emptyMap(),
            simSlots = emptyMap(),
        )
        composeTestRule.onNodeWithTag("sms_sim_icon").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sms_slot_icon").assertDoesNotExist()
    }

    @Test
    fun `sub id 0 renders the sim row with the resolved slot badge`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 0),
            displaySimCards = true,
            simNames = mapOf(0 to "SIM 0"),
            simSlots = mapOf(0 to 1),
        )
        composeTestRule.onNodeWithTag("sms_sim_icon").assertExists()
        composeTestRule.onNodeWithTag("sms_slot_icon").assertExists()
        composeTestRule.onNodeWithText("SIM 0").assertExists()
    }

    @Test
    fun `a single sim device hides the sim row entirely`() {
        render(
            smsModel(1, SmsStatus.SENT, simId = 2),
            displaySimCards = false,
            simNames = mapOf(2 to "SIM 2"),
            simSlots = mapOf(2 to 2),
        )
        composeTestRule.onNodeWithTag("sms_sim_icon").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sms_slot_icon").assertDoesNotExist()
        composeTestRule.onNodeWithText("SIM 2").assertDoesNotExist()
    }
}
