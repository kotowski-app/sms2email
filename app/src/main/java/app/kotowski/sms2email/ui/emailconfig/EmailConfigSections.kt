package app.kotowski.sms2email.ui.emailconfig

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.prefs.SmtpProtocol
import app.kotowski.sms2email.data.prefs.isComplete
import app.kotowski.sms2email.ui.components.AdaptiveFieldRow
import app.kotowski.sms2email.util.EmailUtil

// The look of the SMTP credential fields: the outlined wizard fields of the
// onboarding and the settings rows of the settings screen.
enum class SmtpFieldLook {
    OUTLINED,
    SETTINGS,
}

// The enable rule of the SMTP test button (the one rule of both forms): the
// live port draft must be a port in range (an empty or invalid draft keeps the
// button disabled), the host, the username and the password must be non-blank,
// and no test may be running.
internal fun testSmtpEnabled(
    host: String,
    username: String,
    password: String,
    portDraft: String,
    isTesting: Boolean,
): Boolean =
    validateSmtpPort(portDraft) == SmtpPortState.VALID &&
        host.isNotBlank() &&
        username.isNotBlank() &&
        password.isNotBlank() &&
        !isTesting

// The live-form completeness rule (the one rule of both forms and of the save
// gate): the committed config must be complete (host, a port in range, username
// and password non-blank, sender and destination emails valid — the rule the
// queue path already enforces on the stored values) and the live port draft must
// be a port in range (an empty or invalid draft keeps the form incomplete, so a
// red or empty port field can never sit beside a runnable test or a saveable form).
internal fun isEmailFormComplete(config: EmailConfig): Boolean =
    config.settings.isComplete(config.smtpPassword) &&
        validateSmtpPort(config.portDraft) == SmtpPortState.VALID

// The enable rule of the test-email button (the one rule of both forms): the
// live form must be complete (the committed config plus a port draft in range —
// the completeness rule the save gate already enforces) and no test may be sending.
internal fun testEmailEnabled(config: EmailConfig): Boolean =
    isEmailFormComplete(config) &&
        !config.test.isSendingTestEmail

// The shared SMTP field block of the onboarding and the settings forms: the
// host, the protocol/port row, the username, the password with its hint. The
// form owns the port draft (the protocol pick resets it); the password
// visibility lives here (saveable, so a revealed password stays revealed
// across a rotation).
@Composable
fun SmtpFields(
    smtpHost: String,
    portDraft: String,
    smtpProtocol: SmtpProtocol,
    smtpUsername: String,
    smtpPassword: String,
    onSmtpHostChanged: (String) -> Unit,
    onPortDraftChange: (String) -> Unit,
    onSmtpProtocolChanged: (SmtpProtocol) -> Unit,
    onSmtpUsernameChanged: (String) -> Unit,
    onSmtpPasswordChanged: (String) -> Unit,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    look: SmtpFieldLook = SmtpFieldLook.SETTINGS,
    spacing: Dp = 4.dp,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }

    @Composable
    fun credentialField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        keyboardType: KeyboardType,
        modifier: Modifier = Modifier.fillMaxWidth(),
    ) {
        if (look == SmtpFieldLook.OUTLINED) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                modifier = modifier,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Next,
                    ),
                singleLine = true,
            )
        } else {
            TextFieldRow(
                label = label,
                value = value,
                onValueChange = onValueChange,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Next,
                    ),
                modifier = modifier,
            )
        }
    }

    Column(modifier = modifier) {
        credentialField(
            label = stringResource(R.string.email_config_host),
            value = smtpHost,
            onValueChange = onSmtpHostChanged,
            keyboardType = KeyboardType.Email,
        )
        Spacer(modifier = Modifier.height(spacing))
        AdaptiveFieldRow(
            isLandscape = isLandscape,
            landscapePadding = 8.dp,
            portraitSpacing = spacing,
            field1 = { fieldModifier ->
                Column(
                    modifier = fieldModifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.email_config_protocol),
                        style =
                            if (look == SmtpFieldLook.OUTLINED) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                        color =
                            if (look == SmtpFieldLook.OUTLINED) {
                                Color.Unspecified
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier =
                            Modifier.padding(
                                bottom =
                                    if (look == SmtpFieldLook.OUTLINED) {
                                        8.dp
                                    } else {
                                        4.dp
                                    },
                            ),
                    )
                    SmtpProtocolRow(
                        smtpProtocol = smtpProtocol,
                        onProtocolChanged = { protocol ->
                            onPortDraftChange(protocol.defaultPort.toString())
                            onSmtpProtocolChanged(protocol)
                        },
                    )
                }
            },
            field2 = { fieldModifier ->
                SmtpPortField(
                    value = portDraft,
                    onValueChange = onPortDraftChange,
                    modifier = fieldModifier,
                )
            },
        )
        Spacer(modifier = Modifier.height(spacing))
        AdaptiveFieldRow(
            isLandscape = isLandscape,
            landscapePadding = 8.dp,
            portraitSpacing = spacing,
            field1 = { fieldModifier ->
                credentialField(
                    label = stringResource(R.string.email_config_username),
                    value = smtpUsername,
                    onValueChange = onSmtpUsernameChanged,
                    keyboardType = KeyboardType.Text,
                    modifier = fieldModifier,
                )
            },
            field2 = { fieldModifier ->
                PasswordTextFieldRow(
                    label = stringResource(R.string.email_config_password),
                    value = smtpPassword,
                    onValueChange = onSmtpPasswordChanged,
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword },
                    modifier = fieldModifier,
                )
            },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.email_config_smtp_password_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// The shared sender/receiver block of the onboarding and the settings forms:
// the destination and the sender pairs, each an email (the non-empty draft that
// is not an email shows the error border) and an optional name.
@Composable
fun SenderReceiverFields(
    destEmail: String,
    destName: String,
    senderEmail: String,
    senderName: String,
    onDestEmailChanged: (String) -> Unit,
    onDestNameChanged: (String) -> Unit,
    onSenderEmailChanged: (String) -> Unit,
    onSenderNameChanged: (String) -> Unit,
    isLandscape: Boolean,
    modifier: Modifier = Modifier,
    landscapePadding: Dp = 8.dp,
    portraitFieldSpacing: Dp = 0.dp,
    portraitRowSpacing: Dp = 0.dp,
    senderNameImeAction: ImeAction = ImeAction.Next,
) {
    Column(modifier = modifier) {
        AdaptiveFieldRow(
            isLandscape = isLandscape,
            landscapePadding = landscapePadding,
            portraitSpacing = portraitFieldSpacing,
            field1 = { fieldModifier ->
                TextFieldRow(
                    label = stringResource(R.string.email_config_dest_email),
                    value = destEmail,
                    onValueChange = onDestEmailChanged,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    isError = EmailUtil.isInvalidEmail(destEmail),
                    modifier = fieldModifier,
                )
            },
            field2 = { fieldModifier ->
                TextFieldRow(
                    label =
                        stringResource(
                            R.string.optional_suffix,
                            stringResource(R.string.email_config_dest_name),
                        ),
                    value = destName,
                    onValueChange = onDestNameChanged,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = fieldModifier,
                )
            },
        )
        if (isLandscape || portraitRowSpacing > 0.dp) {
            Spacer(
                modifier =
                    Modifier.height(
                        if (isLandscape) {
                            8.dp
                        } else {
                            portraitRowSpacing
                        },
                    ),
            )
        }
        AdaptiveFieldRow(
            isLandscape = isLandscape,
            landscapePadding = landscapePadding,
            portraitSpacing = portraitFieldSpacing,
            field1 = { fieldModifier ->
                TextFieldRow(
                    label = stringResource(R.string.email_config_sender_email),
                    value = senderEmail,
                    onValueChange = onSenderEmailChanged,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    isError = EmailUtil.isInvalidEmail(senderEmail),
                    modifier = fieldModifier,
                )
            },
            field2 = { fieldModifier ->
                TextFieldRow(
                    label =
                        stringResource(
                            R.string.optional_suffix,
                            stringResource(R.string.email_config_sender_name),
                        ),
                    value = senderName,
                    onValueChange = onSenderNameChanged,
                    keyboardOptions = KeyboardOptions(imeAction = senderNameImeAction),
                    modifier = fieldModifier,
                )
            },
        )
    }
}
