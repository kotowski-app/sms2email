package app.kotowski.sms2email.ui.onboarding

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.system.PermissionGroup
import app.kotowski.sms2email.system.PermissionSnapshot
import app.kotowski.sms2email.system.PermissionsAutoResetStatus
import app.kotowski.sms2email.ui.components.AutoValueField
import app.kotowski.sms2email.ui.components.BottomBar
import app.kotowski.sms2email.ui.components.DropdownRow
import app.kotowski.sms2email.ui.components.IMPORT_MIME_FILTER
import app.kotowski.sms2email.ui.components.PermissionLaunchers
import app.kotowski.sms2email.ui.components.PermissionsAutoResetDialog
import app.kotowski.sms2email.ui.components.RememberOnResume
import app.kotowski.sms2email.ui.components.SystemScrollStateScrollIndicator
import app.kotowski.sms2email.ui.components.TextConfirmDialog
import app.kotowski.sms2email.ui.components.languageLabelRes
import app.kotowski.sms2email.ui.components.languagePickerItems
import app.kotowski.sms2email.ui.components.rememberImportLauncher
import app.kotowski.sms2email.ui.components.rememberPermissionLaunchers
import app.kotowski.sms2email.ui.components.requestPermission
import app.kotowski.sms2email.ui.emailconfig.SenderReceiverFields
import app.kotowski.sms2email.ui.emailconfig.SmtpFieldLook
import app.kotowski.sms2email.ui.emailconfig.SmtpFields
import app.kotowski.sms2email.ui.emailconfig.TestResultButton
import app.kotowski.sms2email.ui.emailconfig.testEmailEnabled
import app.kotowski.sms2email.ui.emailconfig.testSmtpEnabled
import app.kotowski.sms2email.ui.icons.WandStars
import app.kotowski.sms2email.util.AndroidVersion
import app.kotowski.sms2email.util.ToastUtil
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsState()
    val smtpTestSucceeded = state.smtpTestSucceeded
    val testEmailSucceeded = state.testEmailSucceeded

    // The step is the only wizard state that lives in the composition; rememberSaveable
    // keeps it across a config change (rotation) while the form fields and test results
    // ride the back-stack-entry-scoped ViewModel. Dialogs stay ephemeral on purpose.
    var currentStep by rememberSaveable(stateSaver = WizardStep.Saver) { mutableStateOf(WizardStep.WELCOME) }

    var hasCompleted by remember { mutableStateOf(false) }

    var showSkipConfirm by remember { mutableStateOf(false) }
    var showSkipInfo by remember { mutableStateOf(false) }
    var showAutoResetDialog by remember { mutableStateOf(false) }
    var showRecommendedWarning by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val permissionsState = remember { mutableStateOf(viewModel.permissionManager.snapshot()) }
    val autoResetStatusState = remember { mutableStateOf<PermissionsAutoResetStatus?>(null) }
    // Only the SMS pair is required to proceed; contacts/phone are optional and
    // notifications/battery/auto-reset are recommended (they warn, they do not block).
    val permissionsGranted = permissionsState.value.sms
    val notificationsPermissionLabel = stringResource(R.string.permissions_notifications)
    val batteryPermissionLabel = stringResource(R.string.permissions_battery)
    val autoResetPermissionLabel = stringResource(R.string.permissions_auto_reset)
    // The recommended permissions still missing (display names): proceeding past the
    // permissions step with a non-empty list shows a warning dialog before moving on.
    val missingRecommended =
        buildList {
            if (AndroidVersion.is13Plus() && !permissionsState.value.notifications) {
                add(notificationsPermissionLabel)
            }
            if (!permissionsState.value.battery) {
                add(batteryPermissionLabel)
            }
            if (AndroidVersion.is11Plus() && autoResetStatusState.value == PermissionsAutoResetStatus.ACTIVE) {
                add(autoResetPermissionLabel)
            }
        }

    fun refreshPermissions() {
        permissionsState.value = viewModel.permissionManager.snapshot()
    }

    RememberOnResume(
        onResume = {
            refreshPermissions()
            if (AndroidVersion.is11Plus()) {
                coroutineScope.launch {
                    autoResetStatusState.value = viewModel.permissionManager.getPermissionsAutoResetStatus()
                }
            }
        },
    )

    val launchers =
        rememberPermissionLaunchers(
            onResult = { refreshPermissions() },
            onAutoResetResult = {
                coroutineScope.launch {
                    if (AndroidVersion.is11Plus()) {
                        autoResetStatusState.value = viewModel.permissionManager.getPermissionsAutoResetStatus()
                    }
                }
            },
        )

    val importLauncher = rememberImportLauncher { uri -> viewModel.importPicked(uri) }

    val onboardingComplete = state.onboardingComplete

    LaunchedEffect(onboardingComplete) {
        if (onboardingComplete && !hasCompleted) {
            hasCompleted = true
            onComplete()
        }
    }

    LaunchedEffect(currentStep) {
        if (currentStep == WizardStep.PERMISSIONS || currentStep == WizardStep.SENDER) {
            refreshPermissions()
        }
    }

    LaunchedEffect(Unit) {
        if (AndroidVersion.is11Plus()) {
            autoResetStatusState.value = viewModel.permissionManager.getPermissionsAutoResetStatus()
        }
    }

    BackHandler(enabled = true) {
        currentStep.previous?.let { currentStep = it } ?: onBack()
    }

    val canProceed = currentStep.canProceed(permissionsGranted, smtpTestSucceeded, testEmailSucceeded)

    Scaffold(
        bottomBar = {
            BottomBar(
                currentStep = currentStep.ordinal,
                totalSteps = WizardStep.entries.size,
                leading = {
                    OutlinedButton(
                        onClick = {
                            currentStep.previous?.let { currentStep = it } ?: onBack()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                trailing = {
                    Button(
                        onClick = {
                            if (!canProceed) {
                                val toastRes =
                                    when (currentStep) {
                                        WizardStep.PERMISSIONS -> R.string.onboarding_grant_required
                                        WizardStep.SMTP, WizardStep.SENDER -> R.string.onboarding_enter_fields_test
                                        else -> null
                                    }
                                toastRes?.let { ToastUtil.show(context, it) }
                            } else if (currentStep == WizardStep.PERMISSIONS && missingRecommended.isNotEmpty()) {
                                showRecommendedWarning = true
                            } else if (!currentStep.isLast) {
                                if (currentStep == WizardStep.SMTP) viewModel.tryAutoFillEmail()
                                currentStep.next?.let { currentStep = it }
                            } else {
                                if (!viewModel.completeOnboarding()) {
                                    ToastUtil.show(context, R.string.email_config_incomplete_config)
                                }
                            }
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .alpha(if (canProceed) 1f else 0.38f),
                    ) {
                        Text(
                            if (currentStep.isLast) {
                                stringResource(R.string.action_done)
                            } else {
                                stringResource(R.string.action_next)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
        ) {
            when (currentStep) {
                WizardStep.WELCOME -> {
                    WelcomeStep(
                        viewModel = viewModel,
                        onImportClick = {
                            importLauncher.launch(
                                arrayOf(IMPORT_MIME_FILTER),
                            )
                        },
                        onSkipClick =
                            if (state.alreadyConfigured) {
                                null
                            } else {
                                { showSkipConfirm = true }
                            },
                    )
                }

                WizardStep.PERMISSIONS -> {
                    PermissionsStep(
                        viewModel,
                        snapshot = permissionsState.value,
                        autoResetStatusState = autoResetStatusState,
                        showNotifications = AndroidVersion.is13Plus(),
                        showAutoReset = AndroidVersion.is11Plus(),
                        showPhonePermission = !viewModel.isSingleSimDevice,
                        launchers = launchers,
                        onShowAutoResetDialog = { showAutoResetDialog = true },
                    )
                }

                WizardStep.SMTP -> {
                    SmtpStep(viewModel)
                }

                WizardStep.SENDER -> {
                    SenderReceiverStep(
                        viewModel,
                        permissionsState.value.contacts,
                        if (!viewModel.isSingleSimDevice) permissionsState.value.phone else false,
                    )
                }

                WizardStep.DONE -> {
                    DoneStep()
                }
            }
        }
    }

    if (showSkipConfirm && !state.alreadyConfigured) {
        SkipConfirmDialog(
            onDismiss = { showSkipConfirm = false },
            onConfirm = {
                showSkipConfirm = false
                showSkipInfo = true
            },
        )
    }

    if (showSkipInfo && !state.alreadyConfigured) {
        SkipInfoDialog(
            onDismiss = { showSkipInfo = false },
            onClose = {
                showSkipInfo = false
                viewModel.skipOnboarding()
            },
        )
    }

    if (showAutoResetDialog) {
        PermissionsAutoResetDialog(
            onDismiss = { showAutoResetDialog = false },
            onOpenSettings = {
                launchers.autoReset.launch(viewModel.permissionManager.createManagePermissionsAutoResetIntent())
            },
        )
    }

    if (showRecommendedWarning) {
        RecommendedPermissionsWarningDialog(
            missingNames = missingRecommended,
            onDismiss = {
                showRecommendedWarning = false
            },
            onProceed = {
                showRecommendedWarning = false
                currentStep.next?.let { currentStep = it }
            },
        )
    }
}

@Composable
private fun SkipConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    TextConfirmDialog(
        detail = stringResource(R.string.onboarding_skip_confirm),
        confirmResId = R.string.onboarding_skip,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SkipInfoDialog(
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    TextConfirmDialog(
        icon = WandStars,
        detail = stringResource(R.string.onboarding_skip_info),
        confirmResId = R.string.action_close,
        showCancel = false,
        onConfirm = onClose,
        onDismiss = onDismiss,
    )
}

@Composable
private fun RecommendedPermissionsWarningDialog(
    missingNames: List<String>,
    onDismiss: () -> Unit,
    onProceed: () -> Unit,
) {
    TextConfirmDialog(
        titleResId = R.string.onboarding_permissions_missing_warning_title,
        detail = stringResource(R.string.onboarding_permissions_missing_warning_text, missingNames.joinToString("\n")),
        confirmResId = R.string.onboarding_skip,
        onConfirm = onProceed,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeStep(
    viewModel: OnboardingViewModel,
    onImportClick: () -> Unit,
    onSkipClick: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    var languageExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.welcome_screen_icon),
            contentDescription = null,
            modifier = Modifier.size(180.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        DropdownRow(
            value = stringResource(languageLabelRes(state.config.appLanguage)),
            label = stringResource(R.string.settings_language_label),
            items = languagePickerItems({ viewModel.form.setAppLanguage(it) }),
            expanded = languageExpanded,
            onExpandedChange = { languageExpanded = it },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Translate,
                    contentDescription = null,
                )
            },
            testTag = "dropdown_language_onboarding",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onImportClick,
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.config_import))
        }
        onSkipClick?.let { skipClick ->
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = skipClick,
            ) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
    }
}

@Composable
private fun PermissionsStep(
    viewModel: OnboardingViewModel,
    snapshot: PermissionSnapshot,
    autoResetStatusState: androidx.compose.runtime.MutableState<PermissionsAutoResetStatus?>,
    showNotifications: Boolean,
    showAutoReset: Boolean,
    showPhonePermission: Boolean,
    launchers: PermissionLaunchers,
    onShowAutoResetDialog: () -> Unit,
) {
    val context = LocalContext.current

    fun onGrant(group: PermissionGroup) =
        requestPermission(context, viewModel.permissionManager, launchers, group) {
            launchers.appSettings.launch(viewModel.permissionManager.createAppSettingsIntent())
        }

    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(modifier = Modifier.height(14.dp))

            PermissionRow(
                title = stringResource(R.string.permissions_sms),
                description = stringResource(R.string.permissions_sms_desc),
                granted = snapshot.sms,
                onGrant = { onGrant(PermissionGroup.SMS) },
            )

            PermissionRow(
                title = stringResource(R.string.optional_suffix, stringResource(R.string.permissions_contacts)),
                description = stringResource(R.string.permissions_contacts_desc),
                granted = snapshot.contacts,
                onGrant = { onGrant(PermissionGroup.CONTACTS) },
            )

            if (showPhonePermission) {
                PermissionRow(
                    title = stringResource(R.string.optional_suffix, stringResource(R.string.permissions_phone)),
                    description = stringResource(R.string.permissions_phone_desc),
                    granted = snapshot.phone,
                    onGrant = { onGrant(PermissionGroup.PHONE) },
                )
            }

            if (showNotifications) {
                PermissionRow(
                    title = stringResource(R.string.recommended_suffix, stringResource(R.string.permissions_notifications)),
                    description = stringResource(R.string.permissions_notifications_desc),
                    granted = snapshot.notifications,
                    onGrant = { onGrant(PermissionGroup.NOTIFICATIONS) },
                )
            }

            PermissionRow(
                title = stringResource(R.string.recommended_suffix, stringResource(R.string.permissions_battery)),
                description = stringResource(R.string.permissions_battery_desc),
                granted = snapshot.battery,
                onGrant = { onGrant(PermissionGroup.BATTERY) },
            )

            // Shown while the status is loading (null); hidden only once the platform
            // confirms the feature is absent.
            if (
                showAutoReset &&
                autoResetStatusState.value != PermissionsAutoResetStatus.NOT_AVAILABLE
            ) {
                val autoResetGranted = autoResetStatusState.value == PermissionsAutoResetStatus.DISABLED
                PermissionRow(
                    title = stringResource(R.string.recommended_suffix, stringResource(R.string.permissions_auto_reset)),
                    description = stringResource(R.string.permissions_auto_reset_desc),
                    granted = autoResetGranted,
                    onGrant = { onShowAutoResetDialog() },
                )
            }
        }
        SystemScrollStateScrollIndicator(
            scrollState = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.permissions_granted),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                TextButton(onClick = onGrant) {
                    Text(stringResource(R.string.onboarding_grant))
                }
            }
        }
    }
}

@Composable
private fun SmtpStep(viewModel: OnboardingViewModel) {
    val state by viewModel.state.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.email_config_section_smtp),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(modifier = Modifier.height(14.dp))

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
                look = SmtpFieldLook.OUTLINED,
                spacing = 8.dp,
            )

            Spacer(modifier = Modifier.height(16.dp))

            TestResultButton(
                label = stringResource(R.string.email_config_smtp_test),
                onClick = {
                    viewModel.testConnection()
                },
                enabled =
                    testSmtpEnabled(
                        state.config.settings.smtpHost,
                        state.config.settings.smtpUsername,
                        state.config.smtpPassword,
                        state.config.portDraft,
                        state.config.test.isTestingConnection,
                    ),
                isWorking = state.config.test.isTestingConnection,
                result = state.config.test.smtpTestResult,
            )
        }
        SystemScrollStateScrollIndicator(
            scrollState = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun SenderReceiverStep(
    viewModel: OnboardingViewModel,
    contactsInitiallyGranted: Boolean,
    phoneInitiallyGranted: Boolean,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(contactsInitiallyGranted) {
        if (contactsInitiallyGranted) {
            viewModel.form.ensureContactResolutionOnIfUnset()
        }
    }

    LaunchedEffect(phoneInitiallyGranted) {
        if (phoneInitiallyGranted) {
            viewModel.form.ensureSimDisplayOnIfUnset()
        }
    }

    val scrollState = rememberScrollState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.email_config_sender_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(modifier = Modifier.height(14.dp))

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
                portraitFieldSpacing = 8.dp,
                portraitRowSpacing = 8.dp,
                senderNameImeAction = ImeAction.Done,
            )

            Spacer(modifier = Modifier.height(8.dp))

            AutoValueField(
                isLandscape = isLandscape,
                title = stringResource(R.string.email_config_auto_subject_prefix),
                description = stringResource(R.string.email_config_auto_subject_prefix_desc),
                checked = state.config.settings.autoSubjectPrefix,
                onCheckedChange = { viewModel.form.onAutoSubjectPrefixChanged(it) },
                value = state.config.settings.subjectPrefix,
                onValueChange = { viewModel.form.onSubjectPrefixChanged(it) },
                onCommit = { viewModel.form.onSubjectPrefixCommitted(it) },
                label = stringResource(R.string.email_config_subject_prefix),
                // The import re-reads the form (bumping the generation): re-sync the
                // local draft from the imported value, like the settings screen does.
                resetKey = state.config.refreshGeneration,
            )

            Spacer(modifier = Modifier.height(16.dp))

            TestResultButton(
                label = stringResource(R.string.email_config_send_test_email),
                onClick = { viewModel.sendTestEmail() },
                enabled = testEmailEnabled(state.config),
                isWorking = state.config.test.isSendingTestEmail,
                result = state.config.test.testEmailResult,
            )
        }
        SystemScrollStateScrollIndicator(
            scrollState = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun DoneStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Done,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_done_message),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
    }
}
