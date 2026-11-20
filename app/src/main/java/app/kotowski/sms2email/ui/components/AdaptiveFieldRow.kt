package app.kotowski.sms2email.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The adaptive pair of the SMTP and sender forms (Settings and Onboarding): the two
// fields side by side with a divider in the landscape, stacked in the portrait. Each
// field receives its own modifier (the weight in the landscape, the full width in
// the portrait).
@Composable
fun AdaptiveFieldRow(
    isLandscape: Boolean,
    field1: @Composable (modifier: Modifier) -> Unit,
    field2: @Composable (modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    landscapePadding: Dp = 0.dp,
    portraitSpacing: Dp = 0.dp,
) {
    if (isLandscape) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(landscapePadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            field1(Modifier.weight(1f))
            VerticalDivider(
                modifier = Modifier.height(56.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            field2(Modifier.weight(1f))
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            field1(Modifier.fillMaxWidth())
            if (portraitSpacing > 0.dp) {
                Spacer(modifier = Modifier.height(portraitSpacing))
            }
            field2(Modifier.fillMaxWidth())
        }
    }
}
