package app.kotowski.sms2email.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter

// The read-only view of the committed message filter in the Settings screen, under
// the feature toggle: the rules preview as text on the subject preview's tinted
// background (each rule is ONE line, the active facets joined by "AND", the rules
// separated by an "OR" line, the body/sender patterns quoted — matched exactly as
// written, a space is a pattern difference, the slots as tray numbers) and the
// "Edit" button that opens the filter editor screen. While the filter has no active
// rule the preview reads the off line, so the committed state stays named; a saved
// rule with no active facets renders the muted "no conditions" (reachable via import
// only — the editor drops empty rules on save and the predicate ignores them).
@Composable
fun FilterSummaryRow(
    filter: SmsFilter,
    onEdit: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (filter.rules.none { it.isActive }) {
                    Text(
                        text = stringResource(R.string.settings_filter_off),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (filter.rules.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                FilterRuleLines(filter)
            }
        }
        TextButton(onClick = onEdit, modifier = Modifier.testTag("filter_summary_edit")) {
            Text(stringResource(R.string.action_edit))
        }
    }
}

// The preview body (the header line and the rule lines): the editor renders the
// same lines as the live preview of its draft. The small gap after the header line
// separates the policy sentence from the rules.
@Composable
internal fun FilterCardLines(filter: SmsFilter) {
    val off = filter.rules.none { it.isActive }

    Text(
        text =
            when {
                off -> stringResource(R.string.settings_filter_off)
                filter.mode == FilterMode.SKIP -> stringResource(R.string.settings_filter_card_skip)
                else -> stringResource(R.string.settings_filter_card_forward)
            },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    if (filter.rules.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
    }
    FilterRuleLines(filter)
}

@Composable
internal fun FilterRuleLines(filter: SmsFilter) {
    filter.rules.forEachIndexed { index, rule ->
        if (index > 0) {
            Text(
                text = stringResource(R.string.settings_filter_or),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = ruleSummaryLine(rule),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (rule.isActive) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun ruleSummaryLine(rule: FilterRule): String {
    if (!rule.isActive) return stringResource(R.string.settings_filter_no_conditions)
    val and = stringResource(R.string.settings_filter_and)
    val facets =
        buildList {
            if (rule.body.isNotBlank()) add(stringResource(R.string.settings_filter_card_body, rule.body))
            if (rule.sender.isNotBlank()) add(stringResource(R.string.settings_filter_card_sender, rule.sender))
            if (rule.slots.isNotEmpty()) {
                // One slot stays "SIM 1"; several read as "(SIM 1 OR 2)" — the
                // within-facet OR, grouped so the line's AND cannot swallow it.
                // The join runs over the raw numbers: stringResource does not
                // reach the joinToString transform lambda.
                val slots =
                    if (rule.slots.size == 1) {
                        stringResource(R.string.settings_filter_slot, rule.slots.first())
                    } else {
                        val or = stringResource(R.string.settings_filter_or)
                        stringResource(
                            R.string.settings_filter_slots_group,
                            rule.slots.sorted().joinToString(" $or "),
                        )
                    }
                add(stringResource(R.string.settings_filter_card_slots, slots))
            }
        }
    return facets.joinToString(" $and ")
}
