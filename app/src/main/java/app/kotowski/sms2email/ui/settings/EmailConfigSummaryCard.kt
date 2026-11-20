package app.kotowski.sms2email.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.ui.components.AdaptiveFieldRow
import app.kotowski.sms2email.ui.components.DetailRow
import app.kotowski.sms2email.ui.emailconfig.EmailConfig

internal const val EMPTY_VALUE_PLACEHOLDER = "—"
internal const val PASSWORD_MASK_CHAR = "•"

// The read-only view of the email config in the Settings screen (the view mode of
// the edit/save block): one card with the two groups (SMTP settings and email
// settings), the landscape pairs folded side by side, the password masked, and an
// em dash for every empty value.
@Composable
fun EmailConfigSummaryCard(
    config: EmailConfig,
    isLandscape: Boolean,
    onEdit: () -> Unit,
) {
    val protocolLabel =
        stringResource(
            if (config.settings.smtpProtocol == SmtpProtocol.START_TLS) {
                R.string.email_config_smtp_starttls
            } else {
                R.string.email_config_smtp_implicit_tls
            },
        )
    val maskedPassword =
        if (config.smtpPassword.isEmpty()) {
            EMPTY_VALUE_PLACEHOLDER
        } else {
            PASSWORD_MASK_CHAR.repeat(config.smtpPassword.length)
        }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.email_config_section_smtp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onEdit, modifier = Modifier.testTag("email_config_edit")) {
                    Text(stringResource(R.string.action_edit))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            DetailRow(
                label = stringResource(R.string.email_config_host),
                value = config.settings.smtpHost.ifEmpty { EMPTY_VALUE_PLACEHOLDER },
            )
            AdaptiveFieldRow(
                isLandscape = isLandscape,
                field1 = { m ->
                    DetailRow(
                        label = stringResource(R.string.email_config_protocol),
                        value = protocolLabel,
                        modifier = m,
                    )
                },
                field2 = { m ->
                    DetailRow(
                        label = stringResource(R.string.email_config_port),
                        value = config.settings.smtpPort.toString(),
                        modifier = m,
                    )
                },
            )
            AdaptiveFieldRow(
                isLandscape = isLandscape,
                field1 = { m ->
                    DetailRow(
                        label = stringResource(R.string.email_config_username),
                        value = config.settings.smtpUsername.ifEmpty { EMPTY_VALUE_PLACEHOLDER },
                        modifier = m,
                    )
                },
                field2 = { m ->
                    DetailRow(
                        label = stringResource(R.string.email_config_password),
                        value = maskedPassword,
                        modifier = m,
                    )
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.email_config_sender_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            AdaptiveFieldRow(
                isLandscape = isLandscape,
                field1 = { m ->
                    DetailRow(
                        label = stringResource(R.string.email_config_dest_email),
                        value = config.settings.destEmail.ifEmpty { EMPTY_VALUE_PLACEHOLDER },
                        modifier = m,
                    )
                },
                field2 = { m ->
                    DetailRow(
                        label = stringResource(R.string.email_config_dest_name),
                        value = config.settings.destName.ifEmpty { EMPTY_VALUE_PLACEHOLDER },
                        modifier = m,
                    )
                },
            )
            AdaptiveFieldRow(
                isLandscape = isLandscape,
                field1 = { m ->
                    DetailRow(
                        label = stringResource(R.string.email_config_sender_email),
                        value = config.settings.senderEmail.ifEmpty { EMPTY_VALUE_PLACEHOLDER },
                        modifier = m,
                    )
                },
                field2 = { m ->
                    DetailRow(
                        label = stringResource(R.string.email_config_sender_name),
                        value = config.settings.senderName.ifEmpty { EMPTY_VALUE_PLACEHOLDER },
                        modifier = m,
                    )
                },
            )
        }
    }
}
