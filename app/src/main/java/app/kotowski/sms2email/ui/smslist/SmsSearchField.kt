package app.kotowski.sms2email.ui.smslist

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kotowski.sms2email.R

@Composable
fun SmsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.sms_search_hint)) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_cancel))
            }
        },
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
    )
}

@Composable
fun SearchToggleButton(onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            Icons.Default.Search,
            contentDescription = stringResource(R.string.sms_search_hint),
        )
    }
}
