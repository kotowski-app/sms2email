package app.kotowski.sms2email.ui.emailconfig

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R

enum class SmtpPortState {
    VALID,
    INVALID,
    EMPTY,
}

internal fun validateSmtpPort(value: String): SmtpPortState =
    when {
        value.isEmpty() -> SmtpPortState.EMPTY
        value.toIntOrNull() in AppConfig.VALID_PORT_RANGE -> SmtpPortState.VALID
        else -> SmtpPortState.INVALID
    }

// The port to commit for the draft: the parsed port, null for an empty or invalid
// draft (nothing to commit, the last valid port stays active).
internal fun smtpPortCommitValue(value: String): Int? = value.toIntOrNull()?.takeIf { it in AppConfig.VALID_PORT_RANGE }

// The shared port field of the SMTP forms (Settings and Onboarding): the numeric
// field with the border that shows the draft's validity (primary for a port in
// range, error for a draft that is not, outline for an empty one). The form owns
// the draft (the protocol pick resets it) and commits the parsed port (the
// controller's onPortDraftChanged).
@Composable
fun SmtpPortField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor =
        when (validateSmtpPort(value)) {
            SmtpPortState.VALID -> MaterialTheme.colorScheme.primary
            SmtpPortState.INVALID -> MaterialTheme.colorScheme.error
            SmtpPortState.EMPTY -> MaterialTheme.colorScheme.outline
        }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.email_config_port)) },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        singleLine = true,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = borderColor,
                unfocusedBorderColor = borderColor,
            ),
    )
}
