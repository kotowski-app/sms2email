package app.kotowski.sms2email.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// A card with the optional header row: the title on the left, the close (remove)
// button on the right; neither leaves the card without a header. The editor's
// rule cards (the close button, no title) and the test box (the title, no close
// button) are the same shape, so they share this.
@Composable
fun HeaderCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    // The close action on the header's right edge; null leaves the header without one.
    onClose: (() -> Unit)? = null,
    // The close button's accessibility label; required while onClose is set.
    closeLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
        ) {
            if (title != null || onClose != null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = closeLabel,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}
