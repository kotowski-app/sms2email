package app.kotowski.sms2email.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// The shared read-only dropdown row (a pick from a fixed list): the ExposedDropdownMenuBox
// wiring (the read-only field with the chevron, the menu, the close-on-pick) lives here
// once. The caller owns the expanded state.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownRow(
    value: String,
    label: String,
    // The menu options: a label with the pick action.
    items: List<Pair<String, () -> Unit>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // The field's tag; null leaves it untagged.
    testTag: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    // The optional description under the field (the toggle rows carry the same as a
    // subtitle); null leaves the row without one.
    subtitle: String? = null,
) {
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                leadingIcon = leadingIcon,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                items.forEach { (itemLabel, onSelect) ->
                    DropdownMenuItem(
                        text = { Text(itemLabel) },
                        // The pick action runs first, then the menu closes.
                        onClick = {
                            onSelect()
                            onExpandedChange(false)
                        },
                    )
                }
            }
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
