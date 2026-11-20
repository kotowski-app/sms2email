package app.kotowski.sms2email.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.kotowski.sms2email.R

// The multi-select top bar shared by the History and Queue screens: the cancel and
// the count on the left, the send action and a second status-scoped action on the
// right (Cancel for the in-flight Queue rows, Delete for the terminal History rows).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onFirstAction: () -> Unit,
    onSecondAction: () -> Unit,
    firstResId: Int,
    secondResId: Int,
    secondIcon: ImageVector,
    countResId: Int,
) {
    TopAppBar(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
                Text(
                    text = stringResource(countResId, selectedCount),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        },
        actions = {
            IconButton(
                onClick = onFirstAction,
                enabled = selectedCount > 0,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(firstResId),
                    tint =
                        if (selectedCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            IconButton(
                onClick = onSecondAction,
                enabled = selectedCount > 0,
            ) {
                Icon(
                    secondIcon,
                    contentDescription = stringResource(secondResId),
                    tint =
                        if (selectedCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        },
    )
}
