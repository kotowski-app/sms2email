package app.kotowski.sms2email.ui.emailconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kotowski.sms2email.R
import app.kotowski.sms2email.service.EmailResult

internal fun testResultIcon(result: EmailResult): ImageVector =
    when (result) {
        is EmailResult.Success -> Icons.Default.CheckCircle
        is EmailResult.Failure -> Icons.Default.Cancel
    }

// The shared test button of the SMTP/sender forms (Settings and Onboarding):
// the label, the spinner while the test runs, and the result icon after it.
@Composable
fun TestResultButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    isWorking: Boolean,
    result: EmailResult?,
    modifier: Modifier = Modifier,
    indicatorSize: Dp = 16.dp,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        if (isWorking) {
            // The spinner replaces the label, so it carries the accessibility text the
            // button would otherwise lose while the run is in flight.
            val workingLabel = stringResource(R.string.email_config_test_testing)
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .size(indicatorSize)
                        .semantics { contentDescription = workingLabel },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else if (result != null) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label)
                Spacer(modifier = Modifier.width(8.dp))
                // The icon alone carries the outcome, so TalkBack reads its label with
                // the button's text (the success reuses the toast's string).
                Icon(
                    imageVector = testResultIcon(result),
                    contentDescription =
                        stringResource(
                            if (result is EmailResult.Success) {
                                R.string.email_config_test_success
                            } else {
                                R.string.history_status_failed
                            },
                        ),
                    modifier = Modifier.size(indicatorSize),
                )
            }
        } else {
            Text(label)
        }
    }
}
