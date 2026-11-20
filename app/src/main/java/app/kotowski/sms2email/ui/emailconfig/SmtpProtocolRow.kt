package app.kotowski.sms2email.ui.emailconfig

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.SmtpProtocol

// The shared protocol pick of the SMTP forms (Settings and Onboarding): the
// STARTTLS / Implicit TLS segmented row. The caller owns the protocol state and
// the port reset (a protocol pick proposes its default port).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmtpProtocolRow(
    smtpProtocol: SmtpProtocol,
    onProtocolChanged: (SmtpProtocol) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = smtpProtocol == SmtpProtocol.START_TLS,
            onClick = {
                onProtocolChanged(SmtpProtocol.START_TLS)
            },
            shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
        ) {
            Text(stringResource(R.string.email_config_smtp_starttls))
        }
        SegmentedButton(
            selected = smtpProtocol == SmtpProtocol.IMPLICIT_TLS,
            onClick = {
                onProtocolChanged(SmtpProtocol.IMPLICIT_TLS)
            },
            shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
        ) {
            Text(stringResource(R.string.email_config_smtp_implicit_tls))
        }
    }
}
