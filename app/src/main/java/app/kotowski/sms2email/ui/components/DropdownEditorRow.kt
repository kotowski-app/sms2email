package app.kotowski.sms2email.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kotowski.sms2email.R

// The shared editor row pair behind a settings toggle: while editing, the dropdown
// with the Cancel/Save pair; while locked, the stored value as plain text with the
// Edit button (a grayed-out dropdown would suggest an interaction that does not
// exist until Edit, and a bare value is unclear). The caller owns the state and the
// events (the save/cancel actions close the dropdown before they call the VM); the
// optional hint line (the "pick and tap Save" note while an enable is pending)
// renders under the row in the settings description style.
@Composable
fun DropdownEditorRow(
    editing: Boolean,
    dropdownValue: String,
    dropdownLabel: String,
    items: List<Pair<String, () -> Unit>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    lockedValue: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onEditStarted: () -> Unit,
    hint: String? = null,
    // The tag for the Edit button; null leaves it untagged.
    testTag: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            DropdownRow(
                value = dropdownValue,
                label = dropdownLabel,
                items = items,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
            // The filled tonal gives the row one clear primary action while
            // the edit is pending: the change applies only with this tap.
            FilledTonalButton(onClick = onSave) {
                Text(stringResource(R.string.action_save))
            }
        } else {
            Text(
                text = lockedValue,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onEditStarted,
                modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
            ) {
                Text(stringResource(R.string.action_edit))
            }
        }
    }
    if (hint != null) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
