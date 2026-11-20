package app.kotowski.sms2email.ui.settings

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.BuildConfig
import app.kotowski.sms2email.DedupLevel
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.LogSizePreset
import app.kotowski.sms2email.PeriodPreset
import app.kotowski.sms2email.R
import app.kotowski.sms2email.SweepIntervalPreset
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.system.PermissionGroup
import app.kotowski.sms2email.ui.components.AutoValueField
import app.kotowski.sms2email.ui.components.DropdownEditorRow
import app.kotowski.sms2email.ui.components.DropdownRow
import app.kotowski.sms2email.ui.components.IMPORT_MIME_FILTER
import app.kotowski.sms2email.ui.components.NavigationRow
import app.kotowski.sms2email.ui.components.RememberOnResume
import app.kotowski.sms2email.ui.components.SectionHeader
import app.kotowski.sms2email.ui.components.SectionToggle
import app.kotowski.sms2email.ui.components.SettingsRowPadding
import app.kotowski.sms2email.ui.components.SystemScrollStateScrollIndicator
import app.kotowski.sms2email.ui.components.TextConfirmDialog
import app.kotowski.sms2email.ui.components.ToggleRowWithSubtitle
import app.kotowski.sms2email.ui.components.handlePermissionRequest
import app.kotowski.sms2email.ui.components.languageLabelRes
import app.kotowski.sms2email.ui.components.languagePickerItems
import app.kotowski.sms2email.ui.components.rememberImportLauncher
import app.kotowski.sms2email.util.AndroidVersion
import app.kotowski.sms2email.util.DeviceUtil
import app.kotowski.sms2email.util.Links
import app.kotowski.sms2email.util.Logger
import app.kotowski.sms2email.util.ToastUtil
import app.kotowski.sms2email.util.writeExport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEmailConfigEdit: () -> Unit,
    onNavigateToFilterConfigEdit: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val scrollState = rememberScrollState()

    RememberOnResume(
        onResume = {
            // The permission refresh comes FIRST: it detects the grant transitions against
            // the state from before the resume and re-syncs the notification and the
            // exact-alarm tokens; the full refresh after it re-seeds the same fresh
            // permission values.
            viewModel.refreshNotificationsPermission()
            viewModel.refreshExactAlarmPermission()
            viewModel.refreshState()
            viewModel.syncPermissionGatedToggles()
        },
    )

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                    AppearanceSection(viewModel)
                    NotificationsSection(viewModel)
                    // The forwarding block: the email config card and the subject prefix under
                    // one section header (the edit lives on its own screen, the card's Edit
                    // icon, so the stored config is what is shown and re-read on every resume).
                    SectionHeader(title = stringResource(R.string.settings_section_forwarding))
                    EmailConfigSummaryCard(
                        config = state.config,
                        isLandscape = isLandscape,
                        onEdit = onNavigateToEmailConfigEdit,
                    )
                    SubjectPrefixSection(
                        viewModel = viewModel,
                        isLandscape = isLandscape,
                    )
                    // The received time as the first line of the plain-text body: off by
                    // default, the user opts in; the JSON format carries its own time.
                    ToggleRowWithSubtitle(
                        title = stringResource(R.string.settings_include_time_in_body),
                        subtitle = stringResource(R.string.settings_include_time_in_body_desc),
                        checked = state.includeTimeInBody,
                        onCheckedChange = { viewModel.onIncludeTimeInBodyChanged(it) },
                    )
                    ResolutionTogglesSection(
                        viewModel = viewModel,
                        isLandscape = isLandscape,
                    )
                    RetentionSection(viewModel)
                    SchedulingSection(viewModel)
                    DataSection(viewModel)
                    AdvancedSection(
                        viewModel = viewModel,
                        onNavigateToFilterConfigEdit = onNavigateToFilterConfigEdit,
                        onNavigateToLogViewer = onNavigateToLogViewer,
                    )
                    AboutSection()
                }

                SystemScrollStateScrollIndicator(
                    scrollState = scrollState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun AppearanceSection(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    var themeExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }

    SectionHeader(title = stringResource(R.string.settings_section_appearance))

    // The language picker: a pick recreates the activity with the new locale
    // right away (no app restart) and persists the choice. The translate icon
    // names the row (theme and language both only offer a pick, the icon keeps
    // them apart).
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
                modifier = Modifier.testTag("language_icon"),
            )
        },
        testTag = "dropdown_language",
        modifier = SettingsRowPadding,
    )

    // The theme picker: a pick recomposes the root with the new palette right
    // away (the root collects the holder's flow), no restart.
    DropdownRow(
        value = stringResource(state.themeMode.labelRes),
        label = stringResource(R.string.settings_theme_label),
        items =
            ThemeMode.entries.map { stringResource(it.labelRes) to { viewModel.setThemeMode(it) } },
        expanded = themeExpanded,
        onExpandedChange = { themeExpanded = it },
        testTag = "dropdown_theme",
        modifier = SettingsRowPadding,
    )
}

@Composable
private fun NotificationsSection(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshNotificationsPermission()
        }

    val appSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun openAppSettings() {
        appSettingsLauncher.launch(viewModel.permissionManager.createAppSettingsIntent())
    }

    SectionHeader(title = stringResource(R.string.permissions_notifications))

    // The active state: the stored intent AND the notifications permission. Enabling
    // commits the intent right away and requests the permission in the same gesture
    // while it is missing; the refresh on the dialog's return (and on resume) turns the
    // row on once granted. A permanent denial opens the app settings page.
    ToggleRowWithSubtitle(
        title = stringResource(R.string.settings_sticky_notification),
        subtitle = stringResource(R.string.settings_sticky_notification_desc),
        checked = state.showStickyNotification && state.notificationsGranted,
        onCheckedChange = { isChecked ->
            viewModel.onStickyNotificationChanged(isChecked)
            if (isChecked && !state.notificationsGranted) {
                handlePermissionRequest(
                    context,
                    viewModel.permissionManager,
                    viewModel.permissionManager.permissionsFor(PermissionGroup.NOTIFICATIONS),
                    notificationsLauncher,
                    onOpenSettings = ::openAppSettings,
                )
            }
        },
    )
}

// The subject prefix and the live subject preview: they persist on change (not
// part of the email edit's draft), so the section is always visible and editable.
@Composable
private fun SubjectPrefixSection(
    viewModel: SettingsViewModel,
    isLandscape: Boolean,
) {
    val state by viewModel.state.collectAsState()

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
        resetKey = state.config.refreshGeneration,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_subject_preview),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = state.subjectPreview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            )
        }
    }
}

@Composable
private fun ResolutionTogglesSection(
    viewModel: SettingsViewModel,
    isLandscape: Boolean,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val contactsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            viewModel.form.onUseContactResolutionChanged(permissions[viewModel.permissionManager.contactsPermission] == true)
        }

    val phoneLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            viewModel.form.onDisplaySimCardsChanged(permissions[viewModel.permissionManager.phonePermission] == true)
        }

    val appSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun openAppSettings() {
        appSettingsLauncher.launch(viewModel.permissionManager.createAppSettingsIntent())
    }

    fun handlePermissionToggle(
        permission: String,
        launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        isChecked: Boolean,
        onEnabled: () -> Unit,
        onDisabled: () -> Unit,
    ) {
        if (!isChecked) {
            onDisabled()
            return
        }
        handlePermissionRequest(
            context,
            viewModel.permissionManager,
            arrayOf(permission),
            launcher,
            onOpenSettings = ::openAppSettings,
            onAlreadyGranted = onEnabled,
        )
    }

    @Composable
    fun contactsToggle(modifier: Modifier = Modifier) {
        SectionToggle(
            isLandscape = isLandscape,
            sectionTitle = stringResource(R.string.permissions_contacts),
            title = stringResource(R.string.settings_resolve_contacts),
            description = stringResource(R.string.permissions_contacts_desc),
            checked = state.config.settings.useContactResolution,
            onCheckedChange = { isChecked ->
                handlePermissionToggle(
                    viewModel.permissionManager.contactsPermission,
                    contactsLauncher,
                    isChecked,
                    { viewModel.form.onUseContactResolutionChanged(true) },
                    { viewModel.form.onUseContactResolutionChanged(false) },
                )
            },
            modifier = modifier,
        )
    }

    @Composable
    fun simToggle(modifier: Modifier = Modifier) {
        SectionToggle(
            isLandscape = isLandscape,
            sectionTitle = stringResource(R.string.sim_cards),
            title = stringResource(R.string.settings_sim_name_resolution_title),
            description = stringResource(R.string.permissions_phone_desc),
            checked = state.config.settings.displaySimCards,
            onCheckedChange = { isChecked ->
                handlePermissionToggle(
                    viewModel.permissionManager.phonePermission,
                    phoneLauncher,
                    isChecked,
                    { viewModel.form.onDisplaySimCardsChanged(true) },
                    { viewModel.form.onDisplaySimCardsChanged(false) },
                )
            },
            modifier = modifier,
        )
    }

    // The country field is a parameter of the contact resolution: the row renders
    // only while the feature is on (a dead row would be worse UX).
    fun showCountryField() = state.config.settings.useContactResolution

    if (isLandscape) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            contactsToggle(Modifier.weight(1f))
            VerticalDivider(
                modifier = Modifier.height(56.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            simToggle(Modifier.weight(1f))
        }
        if (showCountryField()) {
            CountryForLocalNumbersField(viewModel, isLandscape)
        }
    } else {
        contactsToggle()
        if (showCountryField()) {
            CountryForLocalNumbersField(viewModel, isLandscape)
        }
        simToggle()
    }
}

// The country for local numbers: the auto/manual ISO code the contact resolution
// normalizes with, right after the "use contact resolution" toggle that owns it.
@Composable
private fun CountryForLocalNumbersField(
    viewModel: SettingsViewModel,
    isLandscape: Boolean,
) {
    val state by viewModel.state.collectAsState()

    AutoValueField(
        isLandscape = isLandscape,
        title = stringResource(R.string.settings_country_title),
        description = stringResource(R.string.settings_country_desc),
        checked = state.config.settings.autoCountry,
        onCheckedChange = { viewModel.form.onAutoCountryChanged(it) },
        value = state.countryValue,
        onValueChange = { viewModel.form.onCountryCodeChanged(it) },
        onCommit = { viewModel.form.onCountryCodeCommitted(it) },
        label = stringResource(R.string.settings_country_code_label),
        errorText = state.countryError,
        resetKey = state.config.refreshGeneration,
    )
}

@Composable
private fun RetentionSection(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val retention = state.retention

    // The dropdown's open/closed: pure presentation — the decision (which confirm to
    // show, what to commit) lives in the VM's retention state, the screen renders it
    // and forwards the events.
    var retentionPeriodExpanded by remember { mutableStateOf(false) }

    // The enable-in-progress: the toggle is visually on but nothing is committed yet —
    // the period row is open in the edit mode, and the commit waits for the Save's
    // confirm dialog with the picked period.
    val enablePending = retention.editing && !retention.enabled

    SectionHeader(title = stringResource(R.string.settings_section_data_retention))

    ToggleRowWithSubtitle(
        title = stringResource(R.string.settings_cleanup_title),
        // The committed period is already named by the locked period row below,
        // so the subtitle stays the plain description in both states.
        subtitle = stringResource(R.string.settings_cleanup_desc),
        // On while the enable is pending too: the toggle shows the intent, the
        // commit waits for the period row's Save.
        checked = retention.enabled || enablePending,
        onCheckedChange = {
            // Turning off closes the dropdown with the edit; the VM commits the
            // off straight away (a pending enable just drops).
            if (!it) retentionPeriodExpanded = false
            viewModel.onRetentionToggled(it)
        },
    )

    // The period row is visible with the cleanup on (locked until the row's
    // Edit — a one-tap dropdown pick must not silently re-decide a destructive
    // period) and with the enable pending (open in the edit mode from the
    // toggle).
    if (retention.enabled || enablePending) {
        DropdownEditorRow(
            editing = retention.editing,
            dropdownValue = stringResource(PeriodPreset.labelResFor(retention.draftDays)),
            dropdownLabel = stringResource(R.string.settings_cleanup_retention_label),
            items =
                PeriodPreset.entries.map { preset ->
                    stringResource(preset.labelRes) to { viewModel.onRetentionDraftChanged(preset.days) }
                },
            expanded = retentionPeriodExpanded,
            onExpandedChange = { retentionPeriodExpanded = it },
            lockedValue =
                stringResource(
                    R.string.settings_cleanup_period_value,
                    stringResource(PeriodPreset.labelResFor(retention.storedDays)),
                ),
            onSave = {
                retentionPeriodExpanded = false
                viewModel.onRetentionSave()
            },
            onCancel = {
                retentionPeriodExpanded = false
                viewModel.onEditCancelled()
            },
            onEditStarted = { viewModel.onEditStarted() },
            // While the enable is pending nothing is committed yet: the hint below
            // the row says so, in the same style as the other settings descriptions.
            hint = if (enablePending) stringResource(R.string.settings_cleanup_enable_hint) else null,
            testTag = "retention_edit",
        )
    }

    // The enabling confirmation: shown from the period row's Save while the enable
    // is pending; enabling starts the deletions, so the commit with the picked
    // period waits for the explicit OK.
    if (retention.confirm == RetentionConfirm.ENABLE) {
        RetentionConfirmDialog(
            titleRes = R.string.settings_cleanup_enable_title,
            confirmRes = R.string.settings_cleanup_enable_confirm,
            days = retention.draftDays,
            onDismiss = { viewModel.onConfirmDismissed() },
            onConfirm = { viewModel.onConfirmed() },
        )
    }

    // The shortening confirmation: a shorter period deletes the messages it no longer
    // covers, so the commit waits for the explicit OK; dismissing keeps the edit
    // open with the draft.
    if (retention.confirm == RetentionConfirm.SHORTEN) {
        RetentionConfirmDialog(
            titleRes = R.string.settings_cleanup_shorten_title,
            confirmRes = R.string.settings_cleanup_shorten_confirm,
            days = retention.draftDays,
            onDismiss = { viewModel.onConfirmDismissed() },
            onConfirm = { viewModel.onConfirmed() },
        )
    }
}

@Composable
private fun LogToFileConfirmDialog(
    titleRes: Int,
    confirmRes: Int,
    detailRes: Int,
    // The size the shrink truncates to; the disable names no size.
    sizeLabel: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    TextConfirmDialog(
        titleResId = titleRes,
        detail = if (sizeLabel != null) stringResource(detailRes, sizeLabel) else stringResource(detailRes),
        confirmResId = confirmRes,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SchedulingSection(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    var sweepIntervalExpanded by remember { mutableStateOf(false) }

    // The system "Alarms & reminders" page: only reachable (and only needed) from API 31
    // on, where the permission can be missing; the refresh on its return re-reads the
    // live state and arms the tokens once granted.
    val exactAlarmLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshExactAlarmPermission()
        }

    SectionHeader(title = stringResource(R.string.settings_section_scheduling))

    // The recovery-sweep period: a pick commits right away (the BackgroundStateReconciler
    // re-arms the sweep in response), the bare option labels, the explanation under.
    DropdownRow(
        value = stringResource(SweepIntervalPreset.labelResFor(state.retrySweepIntervalMinutes)),
        label = stringResource(R.string.settings_stuck_recovery),
        items =
            SweepIntervalPreset.entries.map {
                stringResource(it.labelRes) to { viewModel.setSweepInterval(it.minutes) }
            },
        expanded = sweepIntervalExpanded,
        onExpandedChange = { sweepIntervalExpanded = it },
        subtitle = stringResource(R.string.settings_stuck_recovery_desc),
        testTag = "dropdown_sweep_interval",
        modifier = SettingsRowPadding,
    )

    // The active state: the stored intent AND the permission (API 31+; below it the check
    // is unconditionally true). Enabling commits the intent right away and opens the
    // system page while the permission is missing; the refresh on its return (and on
    // resume) arms the tokens once granted.
    ToggleRowWithSubtitle(
        title = stringResource(R.string.settings_exact_wake_title),
        subtitle = stringResource(R.string.settings_exact_wake_desc),
        checked = state.exactWakeEnabled && state.exactAlarmsGranted,
        onCheckedChange = { isChecked ->
            viewModel.onExactWakeChanged(isChecked)
            if (AndroidVersion.is12Plus() && isChecked && !state.exactAlarmsGranted) {
                exactAlarmLauncher.launch(viewModel.permissionManager.createRequestScheduleExactAlarmIntent())
            }
        },
    )
}

@Composable
private fun DataSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val backupFilename = stringResource(R.string.settings_backup_filename)

    // The JSON is regenerated inside the callback: the result can arrive at a fresh
    // composition (a process death while the picker was open), where any state remembered
    // at tap time is gone.
    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    if (writeExport(context, uri, viewModel.exportSettings())) {
                        ToastUtil.show(context, R.string.config_export_success)
                    } else {
                        Logger.e("Settings", "Export failed: no output stream")
                        ToastUtil.show(context, R.string.config_export_failed)
                    }
                } catch (e: Exception) {
                    Logger.e("Settings", "Export failed: ${e.message}")
                    ToastUtil.show(context, R.string.config_export_failed)
                }
            }
        }

    val importLauncher = rememberImportLauncher { uri -> viewModel.importPicked(uri) }

    SectionHeader(title = stringResource(R.string.settings_section_migration))

    Text(
        text = stringResource(R.string.settings_section_migration_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                exportLauncher.launch(backupFilename)
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Default.Upload,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.config_export))
        }
        // The import rewrites the email prefs, the export only reads; the email
        // edit lives on its own screen, so both are always available here.
        OutlinedButton(
            onClick = {
                importLauncher.launch(arrayOf(IMPORT_MIME_FILTER))
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.config_import))
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun AdvancedSection(
    viewModel: SettingsViewModel,
    onNavigateToFilterConfigEdit: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var dedupLevelExpanded by remember { mutableStateOf(false) }
    var logLevelExpanded by remember { mutableStateOf(false) }
    val logToFile = state.logToFile
    var logSizeExpanded by remember { mutableStateOf(false) }

    // The enable-in-progress: the toggle is visually on but nothing is committed yet —
    // the size row is open in the edit mode, the commit waits for the row's Save.
    val logEnablePending = logToFile.editing && !logToFile.enabled

    SectionHeader(title = stringResource(R.string.settings_section_advanced))

    // The message-filter feature toggle: an explicit opt-in (off by default). While off
    // the filter is not evaluated (every message forwards) and the row under the toggle
    // is hidden; while on the committed rules preview with the Edit button shows under
    // the toggle (the edit lives on its own screen with its own draft). The stored
    // config is untouched by the toggle — a dormant draft while off, re-applied as-is
    // on the next enable.
    ToggleRowWithSubtitle(
        title = stringResource(R.string.settings_message_filtering),
        subtitle = stringResource(R.string.settings_message_filtering_desc),
        checked = state.filterEnabled,
        onCheckedChange = { viewModel.onFilterEnabledChanged(it) },
        testTag = "message_filtering_toggle",
    )
    if (state.filterEnabled) {
        FilterSummaryRow(
            filter = state.filter,
            onEdit = onNavigateToFilterConfigEdit,
        )
    }

    ToggleRowWithSubtitle(
        title = stringResource(R.string.settings_pdu_timestamp),
        subtitle = stringResource(R.string.settings_pdu_timestamp_desc),
        checked = state.usePduTimestamp,
        onCheckedChange = { viewModel.onPduTimestampChanged(it) },
    )

    // The duplicate protection level: the bare option labels (no per-level descriptions
    // in the menu), the generic explanation under the field.
    DropdownRow(
        value = stringResource(state.dedupLevel.labelRes),
        label = stringResource(R.string.settings_dedup_level_label),
        items =
            DedupLevel.entries.map { stringResource(it.labelRes) to { viewModel.setDedupLevel(it) } },
        expanded = dedupLevelExpanded,
        onExpandedChange = { dedupLevelExpanded = it },
        subtitle = stringResource(R.string.settings_dedup_level_desc),
        testTag = "dropdown_dedup_level",
        modifier = SettingsRowPadding,
    )

    ToggleRowWithSubtitle(
        title = stringResource(R.string.settings_json_email_format),
        subtitle = stringResource(R.string.settings_json_email_format_desc),
        checked = state.useJsonEmailFormat,
        onCheckedChange = { viewModel.onJsonEmailFormatChanged(it) },
    )

    SectionHeader(title = stringResource(R.string.log_viewer_title))

    DropdownRow(
        value = stringResource(state.logLevel.labelRes),
        label = stringResource(R.string.settings_log_level_label),
        items =
            LogLevel.entries
                .filter { it != LogLevel.DEBUG || BuildConfig.DEBUG }
                .map { stringResource(it.labelRes) to { viewModel.setLogLevel(it) } },
        expanded = logLevelExpanded,
        onExpandedChange = { logLevelExpanded = it },
        subtitle = stringResource(R.string.settings_log_level_desc),
        testTag = "dropdown_log_level",
        modifier = SettingsRowPadding,
    )

    // The size row is visible with the setting on (locked until the row's Edit) and
    // with the enable pending (open in the edit mode from the toggle).
    ToggleRowWithSubtitle(
        title = stringResource(R.string.settings_log_to_file_title),
        subtitle = stringResource(R.string.settings_log_to_file_desc),
        // On while the enable is pending too: the toggle shows the intent, the
        // commit waits for the size row's Save.
        checked = logToFile.enabled || logEnablePending,
        onCheckedChange = {
            // Turning off closes the dropdown with the edit; the disable then waits
            // for the confirm (a pending enable just drops).
            if (!it) logSizeExpanded = false
            viewModel.onLogToFileToggled(it)
        },
    )

    if (logToFile.enabled || logEnablePending) {
        DropdownEditorRow(
            editing = logToFile.editing,
            dropdownValue = stringResource(LogSizePreset.labelResFor(logToFile.draftSize)),
            dropdownLabel = stringResource(R.string.settings_log_size_label),
            items =
                LogSizePreset.entries.map { preset ->
                    stringResource(preset.labelRes) to { viewModel.onLogSizeDraftChanged(preset.bytes) }
                },
            expanded = logSizeExpanded,
            onExpandedChange = { logSizeExpanded = it },
            lockedValue =
                stringResource(
                    R.string.settings_log_size_value,
                    stringResource(LogSizePreset.labelResFor(logToFile.storedSize)),
                ),
            onSave = {
                logSizeExpanded = false
                viewModel.onLogSave()
            },
            onCancel = {
                logSizeExpanded = false
                viewModel.onLogEditCancelled()
            },
            onEditStarted = { viewModel.onLogEditStarted() },
            hint = if (logEnablePending) stringResource(R.string.settings_log_to_file_hint) else null,
            testTag = "log_size_edit",
        )
    }

    // The shrink confirmation: a smaller size truncates the file to the new cap, so
    // the commit waits for the explicit OK; dismissing keeps the edit open with the
    // draft.
    if (logToFile.confirm == LogToFileConfirm.SHRINK) {
        LogToFileConfirmDialog(
            titleRes = R.string.settings_log_to_file_shrink_title,
            confirmRes = R.string.settings_log_to_file_shrink_confirm,
            detailRes = R.string.settings_log_to_file_shrink_detail,
            sizeLabel = stringResource(LogSizePreset.labelResFor(logToFile.draftSize)),
            onDismiss = { viewModel.onLogConfirmDismissed() },
            onConfirm = { viewModel.onLogConfirmed() },
        )
    }

    // The disable confirmation: turning the setting off deletes the file, so the
    // commit waits for the explicit OK; dismissing keeps the setting on.
    if (logToFile.confirm == LogToFileConfirm.DISABLE) {
        LogToFileConfirmDialog(
            titleRes = R.string.settings_log_to_file_disable_title,
            confirmRes = R.string.settings_log_to_file_disable_confirm,
            detailRes = R.string.settings_log_to_file_disable_detail,
            onDismiss = { viewModel.onLogConfirmDismissed() },
            onConfirm = { viewModel.onLogConfirmed() },
        )
    }

    // Navigation, not an action: the chevron row (NavigationRow) keeps a pure
    // "go somewhere" quiet — as a filled button it would be the loudest
    // element of the settings.
    NavigationRow(
        label = stringResource(R.string.settings_logs),
        onClick = onNavigateToLogViewer,
    )
}

@Composable
private fun AboutSection() {
    val uriHandler = LocalUriHandler.current

    SectionHeader(title = stringResource(R.string.settings_section_about))

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(SettingsRowPadding),
    ) {
        Column {
            Text(
                text = stringResource(R.string.settings_version_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = DeviceUtil.displayVersion(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (DeviceUtil.flavorLabel.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = DeviceUtil.flavorLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    AboutLinkRow(
        title = stringResource(R.string.settings_about_source_code),
        url = Links.REPO,
        onOpenUrl = { uriHandler.openUri(it) },
    )

    AboutLinkRow(
        title = stringResource(R.string.settings_about_report_bug),
        url = Links.REPORT_BUG,
        onOpenUrl = { uriHandler.openUri(it) },
    )

    AboutLinkRow(
        title = stringResource(R.string.settings_about_donation),
        hint = stringResource(R.string.settings_about_donation_hint),
        url = Links.DONATE,
        onOpenUrl = { uriHandler.openUri(it) },
    )
}

@Composable
private fun RetentionConfirmDialog(
    titleRes: Int,
    confirmRes: Int,
    days: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    TextConfirmDialog(
        titleResId = titleRes,
        detail =
            stringResource(
                R.string.settings_cleanup_confirm_detail,
                stringResource(PeriodPreset.labelResFor(days)),
            ),
        confirmResId = confirmRes,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun AboutLinkRow(
    title: String,
    url: String,
    onOpenUrl: (String) -> Unit,
    hint: String? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onOpenUrl(url) }
                .then(SettingsRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The rows leave the app (a browser tab), so the column takes the row's width and
        // the open-in-new icon sits at the end — the "goes somewhere" cue the in-app
        // navigation rows carry as a chevron.
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
