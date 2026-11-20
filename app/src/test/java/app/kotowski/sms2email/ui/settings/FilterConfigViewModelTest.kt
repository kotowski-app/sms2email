package app.kotowski.sms2email.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.SimDetector
import org.junit.Before
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterConfigViewModelTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var simDetector: SimDetector

    @Mock private lateinit var mockContext: Context

    // The real permission manager on the mocked context: the stubbed checkPermission
    // answers the phone-permission read (the SimNameResolverTest pattern — a Mockito
    // mock's phonePermission getter returns null, which no stub can match).
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        permissionManager = PermissionManager(mockContext)
    }

    // The VM seeds the draft at construction, so the stubs are in place first. The
    // defaults are the dual-modem phone with the phone permission (the facet's
    // visible state); the hidden states are picked per test.
    private fun viewModelFor(
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
        return FilterConfigViewModel(appContext, prefs, simDetector, permissionManager)
    }

    // The commit's transform applied to the product default: the saved snapshot
    // the prefs would write.
    private fun savedSettings(): AppSettings {
        val captor = argumentCaptor<(AppSettings) -> AppSettings>()
        verify(prefs).update(captor.capture())
        return captor.firstValue(AppSettings())
    }

    // The draft's rule ids in display order (the rule card's Compose key, fresh per seed).
    private fun ruleIds(viewModel: FilterConfigViewModel): List<String> =
        viewModel.state.value.rules
            .map { it.id }

    @Test
    fun `the draft is seeded from the stored filter`() {
        val filter = SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip")))
        val viewModel = viewModelFor(filter)
        assertEquals(FilterMode.FORWARD, viewModel.state.value.mode)
        assertEquals(
            listOf(FilterRule(body = "vip")),
            viewModel.state.value.rules
                .map { it.rule },
        )
    }

    @Test
    fun `each draft rule carries a unique id that survives its edits`() {
        val viewModel =
            viewModelFor(
                SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "a"), FilterRule(body = "b"))),
            )
        val ids = ruleIds(viewModel)
        // A unique id per rule (a duplicate key would crash the rule list).
        assertEquals(2, ids.toSet().size)
        // The id is the rule's identity: editing the rule keeps it.
        viewModel.onRuleBodyChanged(ids[0], "changed")
        assertEquals(ids, ruleIds(viewModel))
        assertEquals(
            listOf(FilterRule(body = "changed"), FilterRule(body = "b")),
            viewModel.state.value.rules
                .map { it.rule },
        )
    }

    @Test
    fun `offeredSlots covers the device's trays and slotsVisible gates on device and permission`() {
        val viewModel = viewModelFor()
        assertEquals(listOf(1, 2), viewModel.state.value.offeredSlots)
        assertTrue(viewModel.state.value.slotsVisible)
        // A third modem (if there were one) is never offered — the cap is 2.
        assertEquals(listOf(1, 2), viewModelFor(supportedModemCount = 3).state.value.offeredSlots)
        assertFalse(viewModelFor(supportedModemCount = 1).state.value.slotsVisible)
        assertFalse(viewModelFor(phonePermissionGranted = false).state.value.slotsVisible)
    }

    @Test
    fun `onModeChanged switches the draft's mode`() {
        val viewModel = viewModelFor()
        viewModel.onModeChanged(FilterMode.FORWARD)
        assertEquals(FilterMode.FORWARD, viewModel.state.value.mode)
    }

    @Test
    fun `onAddRule appends an empty rule and stops at the cap`() {
        val viewModel = viewModelFor()
        repeat(AppConfig.FILTER_MAX_RULES) { viewModel.onAddRule() }
        viewModel.onAddRule()
        assertEquals(AppConfig.FILTER_MAX_RULES, viewModel.state.value.rules.size)
        assertTrue(
            viewModel.state.value.rules
                .all { it.rule == FilterRule() },
        )
        // Every added rule gets its own key (a duplicate would crash the list).
        assertEquals(AppConfig.FILTER_MAX_RULES, ruleIds(viewModel).toSet().size)
    }

    @Test
    fun `onRemoveRule drops the rule by id and ignores an unknown id`() {
        val viewModel =
            viewModelFor(
                SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "a"), FilterRule(body = "b"))),
            )
        val ids = ruleIds(viewModel)
        viewModel.onRemoveRule(ids[1])
        assertEquals(
            listOf(FilterRule(body = "a")),
            viewModel.state.value.rules
                .map { it.rule },
        )
        viewModel.onRemoveRule("no such rule")
        assertEquals(
            listOf(FilterRule(body = "a")),
            viewModel.state.value.rules
                .map { it.rule },
        )
    }

    @Test
    fun `the rule edits reach the draft`() {
        val viewModel = viewModelFor(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "a"))))
        val id = ruleIds(viewModel).single()
        viewModel.onRuleBodyChanged(id, "new")
        viewModel.onRuleSenderChanged(id, "123")
        viewModel.onRuleSlotToggled(id, 2)
        viewModel.onRuleSlotToggled(id, 1)
        viewModel.onRuleSlotToggled(id, 2)
        val rule =
            viewModel.state.value.rules
                .first()
                .rule
        assertEquals("new", rule.body)
        assertEquals("123", rule.sender)
        // Toggle 2 on, add 1 (sorted), toggle 2 off.
        assertEquals(listOf(1), rule.slots)
    }

    @Test
    fun `requestSaveFilter commits the draft when it is valid and reports SAVED`() {
        val viewModel = viewModelFor(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        viewModel.onRuleBodyChanged(ruleIds(viewModel).single(), "changed")
        assertEquals(FilterSaveOutcome.SAVED, viewModel.requestSaveFilter())
        assertEquals(
            SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "changed"))),
            savedSettings().filter,
        )
    }

    @Test
    fun `requestSaveFilter blocks an uncompilable regex and writes nothing`() {
        val viewModel = viewModelFor()
        viewModel.onAddRule()
        viewModel.onRuleBodyChanged(ruleIds(viewModel).single(), "(")
        assertEquals(FilterSaveOutcome.INCOMPLETE, viewModel.requestSaveFilter())
        verify(prefs, never()).update(any())
    }

    @Test
    fun `requestSaveFilter blocks an over-cap facet and writes nothing`() {
        val viewModel = viewModelFor()
        viewModel.onAddRule()
        viewModel.onRuleBodyChanged(ruleIds(viewModel).single(), "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1))
        assertEquals(FilterSaveOutcome.INCOMPLETE, viewModel.requestSaveFilter())
        verify(prefs, never()).update(any())
    }

    @Test
    fun `requestSaveFilter of an empty forward draft commits the forward off state`() {
        val viewModel = viewModelFor()
        viewModel.onModeChanged(FilterMode.FORWARD)
        assertEquals(FilterSaveOutcome.SAVED, viewModel.requestSaveFilter())
        // The mode is kept (the user's polarity for the next rule), the rule list
        // empty (the off state the predicate forwards everything for).
        assertEquals(SmsFilter(FilterMode.FORWARD, emptyList()), savedSettings().filter)
    }

    @Test
    fun `requestSaveFilter drops the rules without an active facet on commit`() {
        val viewModel = viewModelFor()
        viewModel.onAddRule()
        viewModel.onAddRule()
        viewModel.onRuleBodyChanged(ruleIds(viewModel)[0], "vip")
        assertEquals(FilterSaveOutcome.SAVED, viewModel.requestSaveFilter())
        assertEquals(
            SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))),
            savedSettings().filter,
        )
    }

    @Test
    fun `requestSaveFilter of an all-empty draft commits the off state`() {
        val viewModel = viewModelFor()
        viewModel.onAddRule()
        viewModel.onAddRule()
        assertEquals(FilterSaveOutcome.SAVED, viewModel.requestSaveFilter())
        assertEquals(SmsFilter(), savedSettings().filter)
    }

    // The "Try it" verdict follows the DRAFT's filter; the stored one is untouched until Save.

    @Test
    fun `the try-it verdict is null while the sample is blank and the slot row is hidden`() {
        // The row hidden (a single-modem device): the sample has no tray, so a
        // fully blank sample carries no verdict.
        val viewModel =
            viewModelFor(
                SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))),
                supportedModemCount = 1,
            )
        assertNull(viewModel.state.value.trySlot)
        assertNull(viewModel.state.value.tryVerdict)
        viewModel.onTryBodyChanged("")
        viewModel.onTrySenderChanged("  ")
        assertNull(viewModel.state.value.tryVerdict)
    }

    @Test
    fun `the try-it verdict follows the skip-mode draft`() {
        val viewModel = viewModelFor(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))))
        // The match is case-sensitive as written: the lowercase sample hits "vip".
        viewModel.onTryBodyChanged("your vip code")
        // A hit in skip mode: the sample is skipped.
        assertEquals(false, viewModel.state.value.tryVerdict)
        viewModel.onTryBodyChanged("hello there")
        // A miss in skip mode: the sample forwards.
        assertEquals(true, viewModel.state.value.tryVerdict)
        // The sender sample alone (the sender facet is inactive): forwards.
        viewModel.onTryBodyChanged("")
        viewModel.onTrySenderChanged("+12345")
        assertEquals(true, viewModel.state.value.tryVerdict)
    }

    @Test
    fun `the try-it verdict follows the forward-mode draft`() {
        val viewModel = viewModelFor(SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip"))))
        viewModel.onTryBodyChanged("a vip message")
        // A hit in forward mode: the sample forwards.
        assertEquals(true, viewModel.state.value.tryVerdict)
        viewModel.onTryBodyChanged("nothing to see")
        // A miss in forward mode: the sample is skipped.
        assertEquals(false, viewModel.state.value.tryVerdict)
    }

    @Test
    fun `the try-it verdict of a forward draft without an active rule forwards the sample`() {
        // The off state: the forward mode without a rule forwards everything, the
        // sample included (the mirror of the skip-mode case above).
        val viewModel = viewModelFor(SmsFilter(FilterMode.FORWARD, emptyList()))
        viewModel.onTryBodyChanged("nothing to see")
        assertEquals(true, viewModel.state.value.tryVerdict)
    }

    @Test
    fun `the try-it verdict tracks a rule edited after the sample was typed`() {
        val viewModel = viewModelFor()
        viewModel.onTryBodyChanged("prize alert")
        // No rules yet: the off state forwards the sample.
        assertEquals(true, viewModel.state.value.tryVerdict)
        viewModel.onAddRule()
        viewModel.onRuleBodyChanged(ruleIds(viewModel).single(), "prize")
        // The sample was typed first: the verdict flips when the rule starts
        // matching it.
        assertEquals(false, viewModel.state.value.tryVerdict)
    }

    // The sample's tray slot: the row always has exactly one tray picked (the
    // first by default) while visible; while hidden the sample is the unknown
    // slot, which the facet never hits.

    @Test
    fun `the try box slot row defaults to the first tray while visible`() {
        assertEquals(1, viewModelFor().state.value.trySlot)
        assertEquals(1, viewModelFor(supportedModemCount = 3).state.value.trySlot)
        // The row hidden (a single-modem device, no phone permission): the
        // sample is the unknown slot.
        assertNull(viewModelFor(supportedModemCount = 1).state.value.trySlot)
        assertNull(viewModelFor(phonePermissionGranted = false).state.value.trySlot)
    }

    @Test
    fun `the try-it verdict follows the sample's picked tray slot`() {
        val viewModel =
            viewModelFor(
                SmsFilter(FilterMode.FORWARD, listOf(FilterRule(body = "vip", slots = listOf(1)))),
            )
        viewModel.onTryBodyChanged("a vip message")
        // The default pick (the first tray) is the rule's tray: the rule hits ->
        // forwarded.
        assertEquals(true, viewModel.state.value.tryVerdict)
        // A tray the rule does not list: still skipped.
        viewModel.onTrySlotPicked(2)
        assertEquals(false, viewModel.state.value.tryVerdict)
    }

    @Test
    fun `the try-it verdict shows for a slot-only sample`() {
        val viewModel = viewModelFor(SmsFilter(FilterMode.FORWARD, listOf(FilterRule(slots = listOf(1)))))
        // The default pick (SIM 1) alone is a sample to judge: the slot-only rule
        // forwards it.
        assertEquals(true, viewModel.state.value.tryVerdict)
        // The other tray: the rule does not hit -> skipped.
        viewModel.onTrySlotPicked(2)
        assertEquals(false, viewModel.state.value.tryVerdict)
    }

    @Test
    fun `onTrySlotPicked is single-select and never clears`() {
        val viewModel = viewModelFor()
        assertEquals(1, viewModel.state.value.trySlot)
        // Picking another tray moves the sample (a message arrives on one SIM).
        viewModel.onTrySlotPicked(2)
        assertEquals(2, viewModel.state.value.trySlot)
        // Tapping the picked one keeps it picked (a radio is never cleared).
        viewModel.onTrySlotPicked(2)
        assertEquals(2, viewModel.state.value.trySlot)
    }

    @Test
    fun `FilterConfig defaults exercise all data class parameter defaults`() {
        val defaults = FilterConfig()
        assertEquals(FilterMode.SKIP, defaults.mode)
        assertTrue(defaults.rules.isEmpty())
        assertEquals(1, defaults.supportedModemCount)
        assertFalse(defaults.phonePermissionGranted)
        assertEquals("", defaults.tryBody)
        assertEquals("", defaults.trySender)
        assertNull(defaults.trySlot)
        assertFalse(defaults.slotsVisible)
        assertEquals(listOf(1), defaults.offeredSlots)
    }

    @Test
    fun `saveError names the first failed check in field order`() {
        val viewModel = viewModelFor()
        viewModel.onAddRule()
        val id = ruleIds(viewModel).single()
        viewModel.onRuleBodyChanged(id, "(")
        viewModel.onRuleSenderChanged(id, "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1))
        // The uncompilable regex precedes the byte cap in the gate's order.
        assertEquals(appContext.getString(R.string.settings_filter_invalid_regex), viewModel.saveError())

        val overCap = viewModelFor()
        overCap.onAddRule()
        overCap.onRuleBodyChanged(ruleIds(overCap).single(), "a".repeat(AppConfig.FILTER_FACET_MAX_BYTES + 1))
        assertEquals(
            appContext.getString(R.string.settings_filter_too_long, AppConfig.FILTER_FACET_MAX_BYTES),
            overCap.saveError(),
        )

        // A draft without an active rule (in either mode) is the legal off state:
        // no gate names it.
        val forward = viewModelFor()
        forward.onModeChanged(FilterMode.FORWARD)
        assertNull(forward.saveError())

        assertNull(viewModelFor(SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "ok")))).saveError())
    }
}
