package app.kotowski.sms2email.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.SimDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject

// The outcome of a message-filter save attempt: committed, or blocked by the save
// gate (an uncompilable facet regex, an over-cap facet). A draft without an active
// rule (in either mode) is the legal off state and commits as-is.
enum class FilterSaveOutcome {
    SAVED,
    INCOMPLETE,
}

// One rule of the editor draft with its stable id: the id is the rule card's
// Compose key, so the card's field state (the focus, the cursor) follows the
// rule, not its slot — a middle delete must not move the removed rule's half
// typed state onto the rule that takes its slot. The id lives with the draft
// only (fresh per seed, never persisted — the stored filter carries no ids).
data class RuleDraft(
    val id: String,
    val rule: FilterRule,
)

// The draft of the message filter as the screen's state: the mode + rules the user
// is editing (committed only on Save — the live predicate always sees the stored
// config, never a half-typed one) and the slot facet's inputs (the device's modem
// count and the phone permission).
data class FilterConfig(
    val mode: FilterMode = FilterMode.SKIP,
    val rules: List<RuleDraft> = emptyList(),
    // The device's supported modem count (SimDetector.getSupportedModemCount — a
    // permission-free hardware query, cached, fails safe to 1). It drives the
    // facet's visibility and the offered tray slots. The default (1) hides the
    // facet for a directly constructed state.
    val supportedModemCount: Int = 1,
    // The phone permission: without it the resolver reports no SIMs, so the facet
    // stays hidden. The default hides the facet.
    val phonePermissionGranted: Boolean = false,
    // The "Try it" sample as the draft's own fields: the sample body and the sample
    // sender (as the stored phone number), blank while untouched.
    val tryBody: String = "",
    val trySender: String = "",
    // The sample's tray slot: the radio row always has exactly one tray picked
    // (a message arrives on exactly one SIM) — the first offered tray by default.
    // Null (the unknown slot) only while the row is hidden (single-modem device or
    // no phone permission) — the slot facet never hits the sample, exactly like a
    // real message without a tray slot.
    val trySlot: Int? = null,
) {
    // The live verdict of the "Try it" sample against the DRAFT (not the stored
    // filter — that is the point of the box): null while the sample is fully blank
    // (no body, no sender, and the slot row hidden — nothing to judge), otherwise
    // what shouldForward says for it, the sample's picked slot included.
    val tryVerdict: Boolean?
        get() {
            if (tryBody.isBlank() && trySender.isBlank() && trySlot == null) return null
            return SmsFilter(mode, filterRules).shouldForward(tryBody, trySender, trySlot)
        }

    val filterRules: List<FilterRule>
        get() = rules.map { it.rule }

    // The slot facet is visible on a multi-modem device with the phone permission:
    // the modem count (the device) is the signal, not the number of inserted SIMs —
    // a dual-modem phone with one card in still offers both slots, a single-modem
    // device can never discriminate. Without the permission the resolver offers no
    // SIM names, so the facet stays hidden.
    val slotsVisible: Boolean
        get() = supportedModemCount > 1 && phonePermissionGranted

    // The offered tray slots: 1..modem count, capped at 2 (a third tray is
    // effectively never present and is never offered). An empty tray is still
    // offerable: the user picks the tray they will use. The entry's label is the
    // tray number alone (the SIM name is not rendered).
    val offeredSlots: List<Int>
        get() = (1..minOf(supportedModemCount, 2)).toList()
}

// The message-filter editor as a screen of its own: the draft of the stored filter
// with the Save/Cancel bottom bar. The screen backs itself with this fresh VM, so
// leaving it (Cancel, the back icon or the system back) drops the draft with the
// destination; the Settings screen re-reads the stored filter on resume.
@HiltViewModel
class FilterConfigViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val prefs: EncryptedPrefs,
        private val simDetector: SimDetector,
        private val permissionManager: PermissionManager,
    ) : ViewModel() {
        // The save gate's failed check, in the order the editor shows its fields:
        // the uncompilable regex first, then the byte cap.
        private enum class SaveGate { INVALID_REGEX, FACET_TOO_LONG }

        private fun failedGate(config: FilterConfig): SaveGate? =
            when {
                config.rules.any { it.rule.hasInvalidRegex } -> SaveGate.INVALID_REGEX
                config.rules.any { it.rule.hasFacetTooLong } -> SaveGate.FACET_TOO_LONG
                else -> null
            }

        private val _state = MutableStateFlow(stateFromPrefs())
        val state: StateFlow<FilterConfig> = _state.asStateFlow()

        // The modem count and the permission are live reads: a permission change
        // while the screen is open is picked up by the next open.
        private fun stateFromPrefs(): FilterConfig {
            val settings = prefs.load()
            val config =
                FilterConfig(
                    mode = settings.filter.mode,
                    rules = settings.filter.rules.map { rule -> RuleDraft(UUID.randomUUID().toString(), rule) },
                    supportedModemCount = simDetector.getSupportedModemCount(),
                    phonePermissionGranted = permissionManager.isGranted(permissionManager.phonePermission),
                )
            return if (config.slotsVisible) config.copy(trySlot = config.offeredSlots.first()) else config
        }

        fun onModeChanged(mode: FilterMode) {
            _state.update { it.copy(mode = mode) }
        }

        // The soft caps: the rule count bound (the strict import and the lenient parse
        // enforce the same); the facet byte cap is live on the field, not here.
        fun onAddRule() {
            _state.update { state ->
                if (state.rules.size >= AppConfig.FILTER_MAX_RULES) {
                    state
                } else {
                    state.copy(rules = state.rules + RuleDraft(UUID.randomUUID().toString(), FilterRule()))
                }
            }
        }

        // The id of the rule to drop (an unknown id is a no-op).
        fun onRemoveRule(id: String) {
            _state.update { state ->
                state.copy(rules = state.rules.filterNot { it.id == id })
            }
        }

        private fun mutateRule(
            id: String,
            transform: (FilterRule) -> FilterRule,
        ) {
            _state.update { state ->
                state.copy(rules = state.rules.map { draft -> if (draft.id == id) draft.copy(rule = transform(draft.rule)) else draft })
            }
        }

        fun onRuleBodyChanged(
            id: String,
            value: String,
        ) {
            mutateRule(id) { it.copy(body = value) }
        }

        fun onRuleSenderChanged(
            id: String,
            value: String,
        ) {
            mutateRule(id) { it.copy(sender = value) }
        }

        // The slot chip pick: the tray number toggled in the rule's slots (sorted, so
        // the order is stable for the summary's rendering).
        fun onRuleSlotToggled(
            id: String,
            slot: Int,
        ) {
            mutateRule(id) { rule ->
                val slots = if (slot in rule.slots) rule.slots - slot else (rule.slots + slot).sorted()
                rule.copy(slots = slots)
            }
        }

        fun onTryBodyChanged(value: String) {
            _state.update { it.copy(tryBody = value) }
        }

        fun onTrySenderChanged(value: String) {
            _state.update { it.copy(trySender = value) }
        }

        fun onTrySlotPicked(slot: Int) {
            _state.update { it.copy(trySlot = slot) }
        }

        // The save gate: the same checks the fields show live; a failed gate blocks
        // the commit (the stored config is what the live predicate reads, so a
        // half-typed pattern must never reach it).
        fun requestSaveFilter(): FilterSaveOutcome {
            if (failedGate(_state.value) != null) return FilterSaveOutcome.INCOMPLETE
            commitDraft()
            return FilterSaveOutcome.SAVED
        }

        fun saveError(): String? =
            when (failedGate(_state.value)) {
                SaveGate.INVALID_REGEX -> {
                    appContext.getString(R.string.settings_filter_invalid_regex)
                }

                SaveGate.FACET_TOO_LONG -> {
                    appContext.getString(R.string.settings_filter_too_long, AppConfig.FILTER_FACET_MAX_BYTES)
                }

                null -> {
                    null
                }
            }

        // The one atomic commit of the draft: the whole snapshot through the prefs'
        // single write (the snapshot then serves the typed filter to every reader, no
        // JSON on the hot path). Rules with no active facet are dropped on the way
        // out: the predicate never hits them, and a dead "no conditions" line in the
        // summary card would only confuse — an all-empty draft commits as the off
        // state, the mode kept (the predicate treats no active rule as off in
        // either mode, so the user's polarity survives for the next rule).
        private fun commitDraft() {
            val config = _state.value
            prefs.update { it.copy(filter = SmsFilter(config.mode, config.filterRules.filter { rule -> rule.isActive })) }
            _state.value = stateFromPrefs()
        }
    }
