package app.kotowski.sms2email.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.ui.components.BottomBar
import app.kotowski.sms2email.ui.components.DropdownRow
import app.kotowski.sms2email.ui.components.HeaderCard
import app.kotowski.sms2email.ui.components.SystemScrollStateScrollIndicator
import app.kotowski.sms2email.ui.emailconfig.TextFieldRow
import app.kotowski.sms2email.util.ToastUtil

// The message-filter editor as a screen of its own: the mode pick and the rule
// cards (the body/sender regex fields with the live errors, the SIM slot
// checkboxes), and the Save/Cancel bottom bar. The screen backs itself with a
// fresh FilterConfigViewModel (a DRAFT started from the stored filter), so leaving
// it (Cancel, the back icon or the system back) drops the draft with the
// destination; the Settings screen re-reads the stored filter on resume. The
// draft+save shape matters specifically for regex: with persist-on-stroke an SMS
// arriving mid-typing would be evaluated against the in-progress pattern — in the
// forward matching a half-typed pattern live-changes what gets forwarded. With
// draft+Save the stored config changes atomically on save; the live predicate
// always sees a complete, validated config.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterConfigEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: FilterConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var modeExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    fun onSaveClicked() {
        when (viewModel.requestSaveFilter()) {
            FilterSaveOutcome.SAVED -> {
                ToastUtil.show(context, R.string.settings_saved)
                onNavigateBack()
            }

            FilterSaveOutcome.INCOMPLETE -> {
                viewModel.saveError()?.let { ToastUtil.show(context, it) }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_filter_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
            bottomBar = {
                BottomBar(
                    leading = {
                        OutlinedButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                    trailing = {
                        Button(
                            onClick = { onSaveClicked() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_save))
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .verticalScroll(scrollState),
                ) {
                    // The one global polarity for the whole filter (not per rule): the
                    // mode decides what a rule hit does; the subtitle states the
                    // baseline (what happens to the NON-matching messages) for the
                    // picked mode — the option name alone leaves it implicit.
                    DropdownRow(
                        value =
                            when (state.mode) {
                                FilterMode.SKIP -> stringResource(R.string.settings_filter_mode_skip)
                                FilterMode.FORWARD -> stringResource(R.string.settings_filter_mode_forward)
                            },
                        label = stringResource(R.string.settings_filter_mode_label),
                        items =
                            listOf(
                                stringResource(R.string.settings_filter_mode_skip) to { viewModel.onModeChanged(FilterMode.SKIP) },
                                stringResource(R.string.settings_filter_mode_forward) to { viewModel.onModeChanged(FilterMode.FORWARD) },
                            ),
                        expanded = modeExpanded,
                        onExpandedChange = { modeExpanded = it },
                        // The baseline of the picked mode; while the draft has no
                        // active rule the filter is the off state (forward
                        // everything in either mode), so the off line replaces the
                        // mode's baseline.
                        subtitle =
                            stringResource(
                                when {
                                    state.filterRules.none { it.isActive } -> R.string.settings_filter_off
                                    state.mode == FilterMode.SKIP -> R.string.settings_filter_mode_skip_hint
                                    else -> R.string.settings_filter_mode_forward_hint
                                },
                            ),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )

                    // What the facet fields are, stated up front: regular expressions
                    // with the match semantics, not discovered via the first error.
                    Text(
                        text = stringResource(R.string.settings_filter_regex_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    // The card's key is the rule's draft id (not its slot): a
                    // positional diff would keep a removed rule's field state
                    // (the focus, the cursor) on the slot, and the next keystroke
                    // would land in the wrong rule's field.
                    state.rules.forEachIndexed { index, draft ->
                        key(draft.id) {
                            FilterRuleCard(
                                index = index,
                                draft = draft,
                                state = state,
                                viewModel = viewModel,
                            )
                        }
                    }

                    // The soft cap: the rule count bound (the strict import and the
                    // lenient parse enforce the same).
                    OutlinedButton(
                        onClick = { viewModel.onAddRule() },
                        enabled = state.rules.size < AppConfig.FILTER_MAX_RULES,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_filter_add_rule))
                    }

                    // The live preview, first (it is the more important of the two:
                    // the save's result, visible before it): the header line plus the
                    // same rule lines the Settings row will render for the draft
                    // (FilterCardLines), in the subject-preview surface style.
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_filter_preview_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            FilterCardLines(SmsFilter(state.mode, state.filterRules))
                        }
                    }

                    // The "Try it" box: a sample message evaluated against the DRAFT
                    // (the stored filter is untouched until Save), so the user sees
                    // what their half-typed filter does before committing it. The same
                    // card shape as the rule cards (the shared HeaderCard, the title
                    // instead of the close button); the sample's SIM row is radio
                    // buttons — a message arrives on exactly one tray, so exactly
                    // one is always picked. Hidden while the draft has no active
                    // rule: an off filter forwards everything, there is nothing to
                    // test.
                    if (state.filterRules.any { it.isActive }) {
                        HeaderCard(
                            title = stringResource(R.string.settings_filter_try_title),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 12.dp),
                        ) {
                            TextFieldRow(
                                label = stringResource(R.string.settings_filter_try_body),
                                value = state.tryBody,
                                onValueChange = { viewModel.onTryBodyChanged(it) },
                            )
                            Spacer(Modifier.height(8.dp))
                            TextFieldRow(
                                label = stringResource(R.string.settings_filter_try_sender),
                                value = state.trySender,
                                onValueChange = { viewModel.onTrySenderChanged(it) },
                            )
                            // The sample's tray slot: a message arrives on exactly one
                            // tray, so the row is radio buttons with exactly one
                            // always picked (the first tray by default, no clear) —
                            // the rule card's facet keeps its checkboxes (a rule may
                            // list several trays). Picking a tray judges the sample as
                            // a message received on it. While the row is hidden (a
                            // single-modem device, no phone permission) the sample is
                            // the unknown slot the slot facet never hits, like a real
                            // message without a tray slot.
                            if (state.slotsVisible) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    state.offeredSlots.forEach { slot ->
                                        SlotRadioButton(
                                            label = stringResource(R.string.settings_filter_slot, slot),
                                            selected = state.trySlot == slot,
                                            onClick = { viewModel.onTrySlotPicked(slot) },
                                            testTag = "filter_try_slot_$slot",
                                        )
                                    }
                                }
                            }
                            state.tryVerdict?.let { willForward ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text =
                                        stringResource(
                                            if (willForward) {
                                                R.string.settings_filter_try_forward
                                            } else {
                                                R.string.settings_filter_try_skip
                                            },
                                        ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color =
                                        if (willForward) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                )
                            }
                        }
                    }
                }

                SystemScrollStateScrollIndicator(
                    scrollState = scrollState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

// One rule card of the editor: the remove button on the card's top edge (the
// rule has no number — the rules are OR'd, the order carries no meaning — so the
// icon alone marks the card), the body/sender regex fields (the live errors: an
// uncompilable pattern, or the facet text past the byte cap), and the SIM slot
// checkboxes. Every filled facet of a rule must hit (blank = off); the rules
// themselves are OR'd. The same shared card as the test box (the close button
// instead of the title).
@Composable
private fun FilterRuleCard(
    index: Int,
    draft: RuleDraft,
    state: FilterConfig,
    viewModel: FilterConfigViewModel,
) {
    HeaderCard(
        onClose = { viewModel.onRemoveRule(draft.id) },
        closeLabel = stringResource(R.string.settings_filter_remove_rule),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
    ) {
        TextFieldRow(
            label = stringResource(R.string.settings_filter_body),
            value = draft.rule.body,
            onValueChange = { viewModel.onRuleBodyChanged(draft.id, it) },
            isError = SmsFilter.isFacetInvalid(draft.rule.body),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextFieldRow(
            label = stringResource(R.string.settings_filter_sender),
            value = draft.rule.sender,
            onValueChange = { viewModel.onRuleSenderChanged(draft.id, it) },
            isError = SmsFilter.isFacetInvalid(draft.rule.sender),
        )
        // The slot facet is hidden on a single-modem device (the facet cannot
        // discriminate) or while the phone permission is missing. The offered
        // trays (1..2) as one checkbox row labeled "SIM N" each; a saved slot
        // the device does not offer renders as a disabled entry, so a dead rule
        // stays visible and deletable instead of looking like "no conditions".
        if (state.slotsVisible) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.offeredSlots.forEach { slot ->
                    SlotCheckbox(
                        label = stringResource(R.string.settings_filter_slot, slot),
                        checked = slot in draft.rule.slots,
                        enabled = true,
                        onToggled = { viewModel.onRuleSlotToggled(draft.id, slot) },
                        testTag = "filter_rule_slot_${index}_$slot",
                    )
                }
                draft.rule.slots
                    .filter { slot -> slot !in state.offeredSlots }
                    .sorted()
                    .forEach { slot ->
                        SlotCheckbox(
                            label = stringResource(R.string.settings_filter_slot, slot),
                            checked = true,
                            enabled = false,
                            onToggled = {},
                        )
                    }
            }
        }
    }
}

@Composable
private fun SlotRadioButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = { onClick() },
            modifier =
                if (testTag != null) {
                    Modifier.testTag(testTag)
                } else {
                    Modifier
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// A disabled entry is a saved slot the device does not offer (it cannot be
// cleared, only the rule can be deleted).
@Composable
private fun SlotCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggled: () -> Unit,
    testTag: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggled() },
            enabled = enabled,
            modifier =
                if (testTag != null) {
                    Modifier.testTag(testTag)
                } else {
                    Modifier
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}
