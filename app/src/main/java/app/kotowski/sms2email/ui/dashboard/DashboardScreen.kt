package app.kotowski.sms2email.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.system.PermissionGroup
import app.kotowski.sms2email.system.PermissionsAutoResetStatus
import app.kotowski.sms2email.ui.components.PermissionsAutoResetDialog
import app.kotowski.sms2email.ui.components.RememberOnResume
import app.kotowski.sms2email.ui.components.SystemScrollStateScrollIndicator
import app.kotowski.sms2email.ui.components.TextConfirmDialog
import app.kotowski.sms2email.ui.components.rememberPermissionLaunchers
import app.kotowski.sms2email.ui.components.requestPermission
import app.kotowski.sms2email.ui.icons.Globe2Cancel
import app.kotowski.sms2email.ui.icons.StackedEmail
import app.kotowski.sms2email.ui.icons.WandStars
import app.kotowski.sms2email.ui.icons.slotIcon
import app.kotowski.sms2email.util.AndroidVersion
import app.kotowski.sms2email.util.DeviceUtil
import app.kotowski.sms2email.util.Links
import app.kotowski.sms2email.util.ToastUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToHistory: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenSetupWizard: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showSetupWizardConfirm by remember { mutableStateOf(false) }
    var showPermissionsAutoResetDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.updatePermissions()
        viewModel.loadSimCards()
        viewModel.updatePermissionsAutoResetStatus()
    }

    RememberOnResume(
        onResume = {
            viewModel.updatePermissions()
            viewModel.loadSimCards()
            viewModel.updatePermissionsAutoResetStatus()
        },
    )

    val launchers =
        rememberPermissionLaunchers(
            onResult = { viewModel.updatePermissions() },
            onAutoResetResult = { viewModel.updatePermissionsAutoResetStatus() },
        )

    fun openAppSettings() {
        launchers.appSettings.launch(viewModel.permissionManager.createAppSettingsIntent())
    }

    fun openPermissionsAutoResetSettings() {
        launchers.autoReset.launch(viewModel.permissionManager.createManagePermissionsAutoResetIntent())
    }

    fun onGrantPermission(group: PermissionGroup) =
        requestPermission(context, viewModel.permissionManager, launchers, group) { openAppSettings() }

    val versionBase = DeviceUtil.displayVersion()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.version_prefix, versionBase),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (DeviceUtil.flavorLabel.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = DeviceUtil.flavorLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (!state.hasInternet) {
                        ConnectivityIndicator(
                            onClick = {
                                ToastUtil.show(context, R.string.no_internet)
                            },
                        )
                    }
                    IconButton(onClick = {
                        showSetupWizardConfirm = true
                    }) {
                        Icon(
                            imageVector = WandStars,
                            contentDescription = stringResource(R.string.dashboard_setup_wizard),
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
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
                    .padding(padding),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MasterToggleCard(
                    viewModel = viewModel,
                    onNavigateToHistory = onNavigateToHistory,
                    onNavigateToQueue = onNavigateToQueue,
                )

                PermissionsCard(
                    viewModel = viewModel,
                    onGrantPermission = ::onGrantPermission,
                    onOpenAutoResetInfo = { showPermissionsAutoResetDialog = true },
                )

                SimCardsCard(viewModel)

                DonateRow(
                    title = stringResource(R.string.settings_about_donation),
                    onOpenUrl = { uriHandler.openUri(Links.DONATE) },
                )
            }

            SystemScrollStateScrollIndicator(
                scrollState = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }

    StopForwardingConfirmDialog(viewModel)

    SetupWizardConfirmDialog(
        showSetupWizardConfirm = showSetupWizardConfirm,
        onDismiss = { showSetupWizardConfirm = false },
        onOpenSetupWizard = onOpenSetupWizard,
    )

    if (showPermissionsAutoResetDialog) {
        PermissionsAutoResetDialog(
            onDismiss = { showPermissionsAutoResetDialog = false },
            onOpenSettings = { openPermissionsAutoResetSettings() },
        )
    }
}

@Composable
private fun MasterToggleCard(
    viewModel: DashboardViewModel,
    onNavigateToHistory: () -> Unit,
    onNavigateToQueue: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sms_forwarding),
                    style = MaterialTheme.typography.titleLarge,
                )
                Switch(
                    checked = state.isForwardingEnabled,
                    onCheckedChange = { isChecked ->
                        viewModel.toggleForwarding(isChecked)
                    },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(isActive = state.isForwardingEnabled)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        if (state.isForwardingEnabled) {
                            stringResource(R.string.dashboard_status_listening)
                        } else {
                            stringResource(R.string.dashboard_status_disabled)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onNavigateToHistory() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.history_title))
                }

                OutlinedButton(
                    onClick = { onNavigateToQueue() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = StackedEmail,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.queue_title))
                }
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    viewModel: DashboardViewModel,
    onGrantPermission: (PermissionGroup) -> Unit,
    onOpenAutoResetInfo: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            PermissionStatusRow(
                label = stringResource(R.string.permissions_sms),
                granted = state.smsGranted,
                onGrant = { onGrantPermission(PermissionGroup.SMS) },
            )
            PermissionStatusRow(
                label = stringResource(R.string.permissions_contacts),
                granted = state.contactsGranted,
                optional = true,
                onGrant = { onGrantPermission(PermissionGroup.CONTACTS) },
            )
            if (!state.isSingleSimDevice || state.displaySimCards) {
                PermissionStatusRow(
                    label = stringResource(R.string.permissions_phone),
                    granted = state.phoneGranted,
                    optional = true,
                    onGrant = { onGrantPermission(PermissionGroup.PHONE) },
                )
            }
            if (AndroidVersion.is13Plus()) {
                PermissionStatusRow(
                    label = stringResource(R.string.permissions_notifications),
                    granted = state.notificationsGranted,
                    recommended = true,
                    onGrant = { onGrantPermission(PermissionGroup.NOTIFICATIONS) },
                )
            }
            PermissionStatusRow(
                label = stringResource(R.string.permissions_battery),
                granted = state.batteryOptimizationsGranted,
                recommended = true,
                onGrant = { onGrantPermission(PermissionGroup.BATTERY) },
            )
            // Hidden entirely once the platform confirms the feature is absent; null is
            // "not fetched yet", so the row keeps showing until the first status read.
            if (
                AndroidVersion.is11Plus() &&
                state.permissionsAutoResetStatus != PermissionsAutoResetStatus.NOT_AVAILABLE
            ) {
                PermissionStatusRow(
                    label = stringResource(R.string.permissions_auto_reset),
                    granted = state.permissionsAutoResetStatus == PermissionsAutoResetStatus.DISABLED,
                    recommended = state.permissionsAutoResetStatus != PermissionsAutoResetStatus.DISABLED,
                    onGrant = { onOpenAutoResetInfo() },
                )
            }
        }
    }
}

@Composable
private fun SimCardsCard(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsState()
    // The VM fills simCards only while the display setting is on.
    if (state.simCards.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.sim_cards),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                state.simCards.forEachIndexed { index, sim ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SimCard,
                                    contentDescription = null,
                                )
                                slotIcon(sim.slot)?.let { icon ->
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                    )
                                }
                            }
                            Text(
                                text = sim.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (index < state.simCards.size - 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DonateRow(
    title: String,
    onOpenUrl: () -> Unit,
) {
    // The donate link leaves the app, so it carries the open-in-new icon the
    // Settings' about rows use as the "goes somewhere" cue: coffee icon + label +
    // icon, centered (no URL, unlike settings).
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .clickable { onOpenUrl() },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Coffee,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun StopForwardingConfirmDialog(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsState()
    if (state.stopForwardingQueueCount > 0) {
        TextConfirmDialog(
            titleResId = R.string.dashboard_stop_forwarding_title,
            detail =
                stringResource(
                    R.string.dashboard_stop_forwarding_confirm,
                    state.stopForwardingQueueCount,
                ),
            confirmResId = R.string.dashboard_stop_forwarding_turn_off,
            onConfirm = { viewModel.confirmStopForwarding() },
            onDismiss = { viewModel.cancelStopForwarding() },
        )
    }
}

@Composable
private fun SetupWizardConfirmDialog(
    showSetupWizardConfirm: Boolean,
    onDismiss: () -> Unit,
    onOpenSetupWizard: () -> Unit,
) {
    if (showSetupWizardConfirm) {
        TextConfirmDialog(
            detail = stringResource(R.string.dashboard_setup_wizard_confirm),
            confirmResId = R.string.action_yes,
            onConfirm = {
                onDismiss()
                onOpenSetupWizard()
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun StatusDot(isActive: Boolean) {
    val color =
        if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(8.dp),
    ) {
        drawCircle(color = color)
    }
}

// A single permission row of the dashboard Permissions card. Not-granted rows come in
// two non-blocking flavours that share the info icon: "optional" (adds a feature) and
// "recommended" (keeps background forwarding reliable). Only a required, missing
// permission renders the error style.
@Composable
private fun PermissionStatusRow(
    label: String,
    granted: Boolean,
    optional: Boolean = false,
    recommended: Boolean = false,
    onGrant: () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .then(if (!granted) Modifier.clickable(onClick = onGrant) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.wrapContentWidth(),
        ) {
            if (granted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            } else if (optional || recommended) {
                val labelRes =
                    if (recommended) R.string.dashboard_permission_recommended else R.string.dashboard_permission_optional
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.dashboard_permission_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectivityIndicator(onClick: () -> Unit) {
    val blinkAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        blinkAlpha.animateTo(
            targetValue = 0.3f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
        )
    }

    IconButton(onClick = onClick) {
        Icon(
            imageVector = Globe2Cancel,
            contentDescription = stringResource(R.string.no_internet),
            modifier = Modifier.size(20.dp).graphicsLayer { alpha = blinkAlpha.value },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
