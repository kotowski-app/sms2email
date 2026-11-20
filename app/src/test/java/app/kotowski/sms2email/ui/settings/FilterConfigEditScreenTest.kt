package app.kotowski.sms2email.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isNotFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.SimDetector
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.stubContextGetString
import app.kotowski.sms2email.testing.withMockedToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilterConfigEditScreenTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var simDetector: SimDetector

    @Mock private lateinit var mockContext: Context

    // The real permission manager on the mocked context: the stubbed checkPermission
    // answers the phone-permission read (the SimNameResolverTest pattern — a Mockito
    // mock's phonePermission getter returns null, which no stub can match).
    private lateinit var permissionManager: PermissionManager

    private var backCalled = false
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        permissionManager = PermissionManager(mockContext)
        Dispatchers.setMain(testDispatcher)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        // The VM resolves the gate's toast strings through the app context.
        stubContextGetString(appContext, context)
        backCalled = false
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Dispatchers.resetMain()
    }

    // The VM seeds the draft from the stored filter at construction, so the stubs
    // are in place before the VM is built. The defaults are the dual-modem phone
    // with the phone permission (the facet's visible state).
    private fun showScreen(
        filter: SmsFilter = SmsFilter(),
        supportedModemCount: Int = 2,
        phonePermissionGranted: Boolean = true,
    ): FilterConfigViewModel {
        whenever(prefs.load()).thenReturn(AppSettings(filter = filter))
        whenever(simDetector.getSupportedModemCount()).thenReturn(supportedModemCount)
        whenever(
            mockContext.checkPermission(
                eq(Manifest.permission.READ_PHONE_STATE),
                anyInt(),
                anyInt(),
            ),
        ).thenReturn(
            if (phonePermissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED,
        )
        val viewModel = FilterConfigViewModel(appContext, prefs, simDetector, permissionManager)
        composeTestRule.setContent {
            FilterConfigEditScreen(
                onNavigateBack = { backCalled = true },
                viewModel = viewModel,
            )
        }
        return viewModel
    }

    // The widget's role as a matcher (this BOM's ui-test has no hasRole): material3's
    // RadioButton/Checkbox expose the role in the node's own semantics (the user's and
    // the selectable/toggleable modifiers chain onto the same layout node).
    private fun hasRole(role: Role): SemanticsMatcher =
        SemanticsMatcher("has role $role") {
            it.config[SemanticsProperties.Role] == role
        }

    @Test
    fun `the screen shows the mode pick, the rule cards and the save cancel bar`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_mode_skip)).assertIsDisplayed()
        // No rule number: the card is identified by its fields only, the remove
        // button (its accessibility label) tops the card.
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_body)).assertExists()
        composeTestRule.onNodeWithText("vip").assertExists()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.settings_filter_remove_rule)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_add_rule)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).assertIsDisplayed()
    }

    @Test
    fun `the regex caption states the facet fields are regular expressions`() {
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_regex_hint)).assertIsDisplayed()
    }

    @Test
    fun `the mode pick shows the skip baseline hint in skip mode`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_mode_skip_hint)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_mode_forward_hint)).assertDoesNotExist()
    }

    @Test
    fun `the mode pick shows the forward baseline hint in forward mode`() {
        // The VM seeds the draft from the stored filter, so no dropdown click is
        // needed (one setContent per test).
        showScreen(SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip"))))
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_mode_forward_hint)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_mode_skip_hint)).assertDoesNotExist()
    }

    @Test
    fun `the mode pick shows the off line while the draft has no active rule`() {
        // No active rule = the off state in either mode, so the off line replaces the
        // mode's baseline hint (whatever the picked polarity).
        showScreen(SmsFilter(FilterMode.FORWARD, emptyList()))
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_mode_forward_hint)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_mode_skip_hint)).assertDoesNotExist()
        // The mode subtitle is the first off line in the tree (the preview's header
        // line follows it).
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.settings_filter_off))
            .onFirst()
            .assertIsDisplayed()
    }

    @Test
    fun `the try-it box evaluates the sample against the draft`() {
        val viewModel = showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        // A blank body/sender is still a sample (the slot row's default tray is
        // picked): no rule hit -> forwarded.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_forward))
            .performScrollTo()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_try_forward)).assertIsDisplayed()
        // The match is case-sensitive as written: the lowercase sample hits "vip".
        // Typing through the field proves the wiring (field -> draft -> verdict).
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_body))
            .performTextInput("your vip code")
        composeTestRule.waitForIdle()
        // A hit in skip mode: the sample is skipped (the verdict sits below the
        // fold in the test viewport, so scroll it in first).
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_skip))
            .performScrollTo()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_try_skip)).assertIsDisplayed()
        // The second transition is driven through the VM (the field's wiring is
        // proven above): the verdict's reactivity to the draft is the assertion.
        viewModel.onTryBodyChanged("hello there")
        composeTestRule.waitForIdle()
        // A miss in skip mode: the sample forwards.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_forward))
            .performScrollTo()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_try_forward)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_try_skip)).assertDoesNotExist()
    }

    @Test
    fun `the try-it verdict follows the forward-mode draft`() {
        val viewModel = showScreen(SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip"))))
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_body))
            .performTextInput("a vip message")
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_forward))
            .performScrollTo()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_try_forward)).assertIsDisplayed()
        // Driven through the VM, like above: the verdict must flip with the draft.
        viewModel.onTryBodyChanged("nothing to see")
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_skip))
            .performScrollTo()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_try_skip)).assertIsDisplayed()
    }

    @Test
    fun `the preview card renders the draft as the settings card lines`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        // The preview sits below the fold in the test viewport, so scroll its top
        // (the label) in.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_preview_label))
            .performScrollTo()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_preview_label)).assertIsDisplayed()
        // The same header and rule line the Settings card will show after the save; they
        // sit out of the test viewport, so existence (not display) is the assertion.
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_card_skip)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_card_body, "vip")).assertExists()
    }

    @Test
    fun `the preview card reads the off line for an empty draft`() {
        showScreen()
        // The off line is the mode subtitle (the draft has no active rule) and the
        // preview's header line; the preview is the last of them in the tree.
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.settings_filter_off))
            .onLast()
            .performScrollTo()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.settings_filter_off))
            .onLast()
            .assertIsDisplayed()
    }

    @Test
    fun `the try-it box is hidden while the draft has no active rule`() {
        // An off filter forwards everything, there is nothing to test: the box is
        // gone...
        val viewModel = showScreen(SmsFilter(FilterMode.FORWARD, emptyList()))
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_try_title)).assertDoesNotExist()
        // ...and a rule that becomes active brings it back (the live preview keeps
        // its place, only the box is conditional).
        viewModel.onAddRule()
        viewModel.onRuleBodyChanged(
            viewModel.state.value.rules
                .single()
                .id,
            "vip",
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_try_title)).assertExists()
    }

    @Test
    fun `the preview sits above the try-it box`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        // Scroll the lower node (the try title) into view: the preview label sits
        // just above it, so both carry fresh bounds (off-screen nodes read 0x0).
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_title))
            .performScrollTo()
        val previewTop =
            composeTestRule
                .onNodeWithText(context.getString(R.string.settings_filter_preview_label))
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val tryTop =
            composeTestRule
                .onNodeWithText(context.getString(R.string.settings_filter_try_title))
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        assertTrue(previewTop < tryTop)
    }

    @Test
    fun `save with an invalid regex shows the error toast and stays`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "("))))
        withMockedToast { staticToast ->
            composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
            composeTestRule.waitForIdle()
            staticToast.verify {
                Toast.makeText(any<Context>(), eq(context.getString(R.string.settings_filter_invalid_regex)), eq(Toast.LENGTH_SHORT))
            }
        }
        assertFalse(backCalled)
        verify(prefs, never()).update(any())
        composeTestRule.onNodeWithText(context.getString(R.string.action_save)).assertExists()
    }

    @Test
    fun `save commits the draft and leaves`() {
        val filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip")))
        showScreen(filter)
        withMockedToast { staticToast ->
            composeTestRule.onNodeWithText(context.getString(R.string.action_save)).performClick()
            composeTestRule.waitForIdle()
            staticToast.verify {
                Toast.makeText(any<Context>(), eq(R.string.settings_saved), eq(Toast.LENGTH_SHORT))
            }
        }
        assertTrue(backCalled)
        val captor = argumentCaptor<(AppSettings) -> AppSettings>()
        verify(prefs).update(captor.capture())
        assertEquals(filter, captor.firstValue(AppSettings()).filter)
    }

    @Test
    fun `cancel leaves the screen without writing`() {
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(backCalled)
        verify(prefs, never()).update(any())
    }

    @Test
    fun `add rule appends a card to the draft`() {
        val viewModel = showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_add_rule)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            listOf(FilterRule()),
            viewModel.state.value.rules
                .map { it.rule },
        )
        assertEquals(
            1,
            composeTestRule.onAllNodes(hasText(context.getString(R.string.settings_filter_body))).fetchSemanticsNodes().size,
        )
    }

    // The rule card's key is the rule's draft id, not its slot: removing a rule
    // must not leave its focus on the rule that takes its slot — a positional diff
    // would keep the dead slot's focus on the next rule's field, and the next
    // keystroke would land in the wrong rule.
    @Test
    fun `removing a middle rule does not leave its focus on the next rule`() {
        showScreen(
            SmsFilter(
                FilterMode.SKIP,
                listOf(FilterRule(body = "aaa"), FilterRule(body = "bbb"), FilterRule(body = "ccc")),
            ),
        )
        // The focus goes through the field's RequestFocus semantics action (a
        // tap does not grant focus in the test environment).
        composeTestRule.onNodeWithText("bbb").requestFocus()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("bbb").assert(isFocused())
        // The middle card's remove button (the cards' close buttons share the label).
        composeTestRule
            .onAllNodesWithContentDescription(context.getString(R.string.settings_filter_remove_rule))[1]
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("aaa").assert(isNotFocused())
        composeTestRule.onNodeWithText("ccc").assert(isNotFocused())
    }

    @Test
    fun `the slot facet offers both trays as SIM 1 and SIM 2 in one row`() {
        // The facet lives in a rule card, so the draft needs a rule; it sits below
        // the fold in the test viewport, so scroll to it first. The queries target
        // the rule card's own checkboxes: the test box below offers its own radio
        // row for the sample's slot.
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule())))
        composeTestRule.onNodeWithTag("filter_rule_slot_0_1").performScrollTo()
        composeTestRule.onNodeWithTag("filter_rule_slot_0_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("filter_rule_slot_0_2").assertExists()
    }

    @Test
    fun `tapping the slot checkbox toggles the rule's slot`() {
        val viewModel = showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule())))
        // The rule card's own SIM 1 checkbox (the test box carries its own row).
        composeTestRule.onNodeWithTag("filter_rule_slot_0_1").performScrollTo()
        composeTestRule.onNodeWithTag("filter_rule_slot_0_1").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            listOf(1),
            viewModel.state.value.rules
                .first()
                .rule
                .slots,
        )
    }

    @Test
    fun `the try box slot row judges the sample against the picked tray`() {
        // FORWARD mode, a body+slot rule: the row defaults to the first tray,
        // which is the rule's tray, so the sample forwards; picking the other
        // tray flips the verdict to the skip (a radio is never cleared).
        val viewModel =
            showScreen(
                SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip", slots = listOf(1)))),
            )
        assertEquals(1, viewModel.state.value.trySlot)
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_body))
            .performTextInput("a vip message")
        composeTestRule.waitForIdle()
        // The default pick is the rule's tray: the rule hits.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_forward))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_forward))
            .assertIsDisplayed()
        // Pick the other tray: the rule's slot facet no longer hits.
        composeTestRule.onNodeWithTag("filter_try_slot_2").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, viewModel.state.value.trySlot)
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_skip))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_skip))
            .assertIsDisplayed()
    }

    @Test
    fun `the try box slot row is radio buttons and the rule card keeps its checkboxes`() {
        // The sample arrives on exactly one tray, so the try box picks exclusively
        // (radio); a rule may list several trays, so the rule card stays checkboxes.
        // The rule carries an active facet: the try box renders only while a rule
        // is active.
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        composeTestRule.onNodeWithTag("filter_try_slot_1").performScrollTo()
        composeTestRule.onNodeWithTag("filter_try_slot_1").assert(hasRole(Role.RadioButton))
        composeTestRule.onNodeWithTag("filter_try_slot_2").assert(hasRole(Role.RadioButton))
        composeTestRule.onNodeWithTag("filter_rule_slot_0_1").assert(hasRole(Role.Checkbox))
        composeTestRule.onNodeWithTag("filter_rule_slot_0_2").assert(hasRole(Role.Checkbox))
    }

    @Test
    fun `the slot facet is hidden on a single-modem device`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule())), supportedModemCount = 1)
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_slot, 1)).assertDoesNotExist()
    }

    @Test
    fun `the slot facet is hidden without the phone permission`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule())), phonePermissionGranted = false)
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_slot, 1)).assertDoesNotExist()
    }

    @Test
    fun `the slot row offers the device's trays and a saved slot the device does not offer renders disabled`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(slots = listOf(3)))))
        // The rule card's own checkboxes for the offered trays (the try box below
        // offers its own radio row for the sample's slot, so the text is not unique).
        composeTestRule.onNodeWithTag("filter_rule_slot_0_1").assertExists()
        composeTestRule.onNodeWithTag("filter_rule_slot_0_2").assertExists()
        // The saved slot 3 is never offered (the cap is 2): its disabled entry keeps
        // the rule visible and deletable. Only the rule card renders it, so the
        // label text is unique.
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_slot, 3)).assertExists()
    }

    @Test
    fun `add rule button is disabled when the maximum rule count is reached`() {
        val viewModel = showScreen()
        repeat(app.kotowski.sms2email.AppConfig.FILTER_MAX_RULES) {
            viewModel.onAddRule()
        }
        composeTestRule.waitForIdle()
        val addRuleNode = composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_add_rule))
        addRuleNode.performScrollTo()
        addRuleNode.assertIsDisplayed()
        addRuleNode.assert(hasClickAction()).performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            app.kotowski.sms2email.AppConfig.FILTER_MAX_RULES,
            viewModel.state.value.rules.size,
        )
    }

    @Test
    fun `the back icon navigates back without writing`() {
        showScreen()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_back))
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue(backCalled)
        verify(prefs, never()).update(any())
    }

    @Test
    fun `mode dropdown shows both options on expand`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_mode_skip))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_mode_skip))
            .performClick()
        composeTestRule.waitForIdle()
        // "Forward matching" only exists as a menu item, so its display proves the menu
        // opened; the current value renders twice once it does (field + menu item).
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_mode_forward))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.settings_filter_mode_skip))
            .onFirst()
            .assertIsDisplayed()
    }

    @Test
    fun `picking FORWARD mode updates the draft and the subtitle`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_mode_skip))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_mode_skip))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_mode_forward))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_mode_forward))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_mode_forward_hint))
            .assertIsDisplayed()
    }

    @Test
    fun `try-it verdict is hidden when sample is blank and slots hidden`() {
        showScreen(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))), supportedModemCount = 1)
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_forward))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_skip))
            .assertDoesNotExist()
    }

    @Test
    fun `try-it verdict shows when sample has content and slots hidden`() {
        val viewModel =
            showScreen(
                SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))),
                supportedModemCount = 1,
            )
        viewModel.onTryBodyChanged("your vip code")
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_skip))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_skip))
            .assertIsDisplayed()
    }

    @Test
    fun `try sender field updates the verdict`() {
        val viewModel = showScreen(SmsFilter(FilterMode.FORWARD, listOf(FilterRule(sender = "bob"))))
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_sender))
            .performTextInput("bob")
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_forward))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_try_forward))
            .assertIsDisplayed()
    }
}
