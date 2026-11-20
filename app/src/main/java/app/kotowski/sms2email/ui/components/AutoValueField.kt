package app.kotowski.sms2email.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

// The auto-value field: the auto-mode switch with the editable field (disabled
// while auto is on). The draft lives here, a toggle off restores the committed
// value, and a resetKey change (an external state refresh) re-syncs it from
// the value. An optional errorText puts the field in the error state and
// renders the message under it.
@Composable
fun AutoValueField(
    isLandscape: Boolean,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    label: String,
    errorText: String? = null,
    resetKey: Int = 0,
) {
    var draft by remember { mutableStateOf(value) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(resetKey, checked) {
        if (!checked) draft = value
    }

    @Composable
    fun prefixField(modifier: Modifier) {
        Column(modifier = modifier) {
            OutlinedTextField(
                value = if (checked) value else draft,
                onValueChange = {
                    draft = it
                    onValueChange(it)
                },
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            onCommit(draft)
                            if (!checked) focusManager.clearFocus()
                        },
                    ),
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions =
                    KeyboardOptions(
                        imeAction = if (checked) ImeAction.None else ImeAction.Done,
                    ),
                enabled = !checked,
                isError = errorText != null,
                singleLine = true,
            )
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
        }
    }

    if (isLandscape) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
            VerticalDivider(
                modifier = Modifier.height(56.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            prefixField(Modifier.weight(1f))
        }
    } else {
        ToggleRowWithSubtitle(
            title = title,
            subtitle = description,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        prefixField(Modifier.fillMaxWidth())
    }
}
