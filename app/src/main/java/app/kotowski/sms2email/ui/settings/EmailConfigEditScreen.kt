package app.kotowski.sms2email.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ui.components.BottomBar
import app.kotowski.sms2email.ui.components.SectionHeader
import app.kotowski.sms2email.ui.components.SystemScrollStateScrollIndicator
import app.kotowski.sms2email.ui.components.TextConfirmDialog
import app.kotowski.sms2email.ui.emailconfig.SenderReceiverFields
import app.kotowski.sms2email.ui.emailconfig.SmtpFields
import app.kotowski.sms2email.ui.emailconfig.TestResultButton
import app.kotowski.sms2email.ui.emailconfig.testEmailEnabled
import app.kotowski.sms2email.util.ToastUtil

// The email-config editor as a screen of its own: the SMTP and the sender/receiver
// forms with the test buttons, and the Save/Cancel bottom bar. The screen backs
// itself with a fresh SettingsViewModel (a DRAFT form started from the stored
// config), so leaving it (Cancel, the back icon or the system back) drops the draft
// with the destination; the Settings screen re-reads the stored config on resume.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailConfigEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // The untested-settings warning: the config is complete, but the test email has
    // not succeeded; Cancel keeps the editor open, Save anyway commits it regardless.
    var showUntestedConfirm by remember { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrollState = rememberScrollState()

    // The save of the email edit: the gate's outcomes (the toasts and the
    // untested-settings warning live here, the rule in the VM). A save pops the
    // screen back to Settings, where the summary card re-reads the stored config.
    fun onSaveClicked() {
        when (viewModel.requestSaveEmail()) {
            EmailSaveOutcome.SAVED -> {
                ToastUtil.show(context, R.string.settings_saved)
                onNavigateBack()
            }

            EmailSaveOutcome.INCOMPLETE -> {
                ToastUtil.show(context, R.string.email_config_incomplete_config)
            }

            EmailSaveOutcome.NEEDS_CONFIRM -> {
                showUntestedConfirm = true
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_section_forwarding)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
            bottomBar = {
                BottomBar(
                    leading = {
                        OutlinedButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                    trailing = {
                        Button(
                            onClick = { onSaveClicked() },
                            modifier = Modifier.weight(1f),
                        ) {
                            // The icon is decorative: the text next to it is the button's
                            // label, so a description of its own would be read twice.
                            Icon(
                                Icons.Default.Save,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_save))
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .verticalScroll(scrollState),
                ) {
                    SmtpSection(
                        viewModel = viewModel,
                        isLandscape = isLandscape,
                    )
                    SenderReceiverSection(
                        viewModel = viewModel,
                        isLandscape = isLandscape,
                    )
                    TestButtonsSection(
                        viewModel = viewModel,
                    )
                }

                SystemScrollStateScrollIndicator(
                    scrollState = scrollState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }

    if (showUntestedConfirm) {
        TextConfirmDialog(
            titleResId = R.string.settings_email_untested_title,
            detail = stringResource(R.string.settings_email_untested_body),
            confirmResId = R.string.settings_email_save_anyway,
            onConfirm = {
                showUntestedConfirm = false
                when (viewModel.confirmSaveAnyway()) {
                    EmailSaveOutcome.SAVED -> {
                        ToastUtil.show(context, R.string.settings_saved)
                        onNavigateBack()
                    }

                    EmailSaveOutcome.INCOMPLETE,
                    EmailSaveOutcome.NEEDS_CONFIRM,
                    -> {
                        ToastUtil.show(context, R.string.email_config_incomplete_config)
                    }
                }
            },
            onDismiss = { showUntestedConfirm = false },
        )
    }
}

@Composable
private fun SmtpSection(
    viewModel: SettingsViewModel,
    isLandscape: Boolean,
) {
    val state by viewModel.state.collectAsState()

    SectionHeader(title = stringResource(R.string.email_config_section_smtp))

    SmtpFields(
        smtpHost = state.config.settings.smtpHost,
        portDraft = state.config.portDraft,
        smtpProtocol = state.config.settings.smtpProtocol,
        smtpUsername = state.config.settings.smtpUsername,
        smtpPassword = state.config.smtpPassword,
        onSmtpHostChanged = { viewModel.form.onSmtpHostChanged(it) },
        onPortDraftChange = { viewModel.form.onPortDraftChanged(it) },
        onSmtpProtocolChanged = { viewModel.form.onSmtpProtocolChanged(it) },
        onSmtpUsernameChanged = { viewModel.form.onSmtpUsernameChanged(it) },
        onSmtpPasswordChanged = { viewModel.form.onSmtpPasswordChanged(it) },
        isLandscape = isLandscape,
    )
}

@Composable
private fun SenderReceiverSection(
    viewModel: SettingsViewModel,
    isLandscape: Boolean,
) {
    val state by viewModel.state.collectAsState()

    SectionHeader(title = stringResource(R.string.email_config_sender_title))

    SenderReceiverFields(
        destEmail = state.config.settings.destEmail,
        destName = state.config.settings.destName,
        senderEmail = state.config.settings.senderEmail,
        senderName = state.config.settings.senderName,
        onDestEmailChanged = { viewModel.form.onDestEmailChanged(it) },
        onDestNameChanged = { viewModel.form.onDestNameChanged(it) },
        onSenderEmailChanged = { viewModel.form.onSenderEmailChanged(it) },
        onSenderNameChanged = { viewModel.form.onSenderNameChanged(it) },
        isLandscape = isLandscape,
        // The last field of the form: Done, like the wizard's last field, so Enter
        // closes the keyboard instead of wrapping the focus to the first field.
        senderNameImeAction = ImeAction.Done,
    )

    if (!isLandscape) {
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun TestButtonsSection(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()

    TestResultButton(
        label = stringResource(R.string.email_config_send_test_email),
        onClick = { viewModel.sendTestEmail() },
        enabled = testEmailEnabled(state.config),
        isWorking = state.config.test.isSendingTestEmail,
        result = state.config.test.testEmailResult,
    )
}
