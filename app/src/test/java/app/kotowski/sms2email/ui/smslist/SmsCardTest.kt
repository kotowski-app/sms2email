package app.kotowski.sms2email.ui.smslist

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.smsModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The list card's 1s ticker (retry countdown) must only run for rows that actually
// render a per-second value; every other status would just burn battery.
class SmsCardTest {
    @Test
    fun `retrying rows need the live ticker`() {
        assertTrue(cardNeedsLiveUpdates(SmsStatus.RETRYING))
    }

    @Test
    fun `sent rows do not need the live ticker`() {
        assertFalse(cardNeedsLiveUpdates(SmsStatus.SENT))
    }

    @Test
    fun `pending rows do not need the live ticker`() {
        assertFalse(cardNeedsLiveUpdates(SmsStatus.PENDING))
    }

    @Test
    fun `sending rows do not need the live ticker`() {
        assertFalse(cardNeedsLiveUpdates(SmsStatus.SENDING))
    }

    @Test
    fun `failed rows do not need the live ticker`() {
        assertFalse(cardNeedsLiveUpdates(SmsStatus.FAILED))
    }

    @Test
    fun `ignored rows do not need the live ticker`() {
        assertFalse(cardNeedsLiveUpdates(SmsStatus.IGNORED))
    }

    @Test
    fun `imported rows do not need the live ticker`() {
        assertFalse(cardNeedsLiveUpdates(SmsStatus.IMPORTED))
    }
}

// Compose rendering tests for the card: the gesture handler branches (selection mode
// uses clickable, non-selection uses detectTapGestures) and the live ticker.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsCardUiTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    @Test
    fun `card renders with phone number and message body`() {
        val sms = smsModel(1, SmsStatus.SENT, messageBody = "hello", phoneNumber = "+123")
        composeTestRule.setContent {
            SmsListCard(
                sms = sms,
                selectionMode = false,
                isSelected = false,
                isSingleSim = true,
                simNames = emptyMap(),
                simSlots = emptyMap(),
                contactNames = emptyMap(),
                onClick = { },
                onLongPress = { },
                onToggleSelection = { },
                hasInternet = true,
            )
        }
        composeTestRule.onNodeWithText("+123").assertExists()
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    // The selection-mode branch uses clickable instead of detectTapGestures: both
    // paths render the card, but the modifier chain differs. The selected color
    // override is the visible branch.
    @Test
    fun `selected card in selection mode renders`() {
        val sms = smsModel(1, SmsStatus.SENT, messageBody = "hello")
        composeTestRule.setContent {
            SmsListCard(
                sms = sms,
                selectionMode = true,
                isSelected = true,
                isSingleSim = true,
                simNames = emptyMap(),
                simSlots = emptyMap(),
                contactNames = emptyMap(),
                onClick = { },
                onLongPress = { },
                onToggleSelection = { },
                hasInternet = true,
            )
        }
        composeTestRule.onNodeWithText("hello").assertExists()
    }

    // The contact name from the resolved map overrides the raw phone number.
    @Test
    fun `card shows resolved contact name`() {
        val sms = smsModel(1, SmsStatus.SENT, messageBody = "hello", phoneNumber = "+123")
        composeTestRule.setContent {
            SmsListCard(
                sms = sms,
                selectionMode = false,
                isSelected = false,
                isSingleSim = true,
                simNames = emptyMap(),
                simSlots = emptyMap(),
                contactNames = mapOf("+123" to "John"),
                onClick = { },
                onLongPress = { },
                onToggleSelection = { },
                hasInternet = true,
            )
        }
        composeTestRule.onNodeWithText("John").assertExists()
    }

    // The multi-SIM branch renders the SIM name row with the card icon and slot badge.
    @Test
    fun `card shows sim name on dual-sim device`() {
        val sms = smsModel(1, SmsStatus.SENT, messageBody = "hello", simId = 1)
        composeTestRule.setContent {
            SmsListCard(
                sms = sms,
                selectionMode = false,
                isSelected = false,
                isSingleSim = false,
                simNames = mapOf(1 to "Home"),
                simSlots = mapOf(1 to 1),
                contactNames = emptyMap(),
                onClick = { },
                onLongPress = { },
                onToggleSelection = { },
                hasInternet = true,
            )
        }
        composeTestRule.onNodeWithText("Home").assertExists()
        composeTestRule.onNodeWithTag("sms_sim_icon").assertExists()
        composeTestRule.onNodeWithTag("sms_slot_icon").assertExists()
    }

    // The rememberNowTicker's LaunchedEffect only runs when active=true (RETRYING rows).
    // This test confirms the ticker composes and the LaunchedEffect body is reachable.
    @Test
    fun `rememberNowTicker composes with active true`() {
        composeTestRule.setContent {
            val tickerActive = remember { mutableStateOf(true) }
            val now = rememberNowTicker(tickerActive.value)
            Text("active:$now")
        }
        // The composable rendered with the active ticker: the LaunchedEffect body for
        // active=true is covered by the composition. The actual delay loop is not waited on.
    }

    // When active=false, the LaunchedEffect returns early (the non-retrying branch).
    @Test
    fun `rememberNowTicker composes with active false`() {
        composeTestRule.setContent {
            val now = rememberNowTicker(false)
            Text("idle:$now")
        }
        // The composable renders; the LaunchedEffect's early return branch is exercised.
    }
}
