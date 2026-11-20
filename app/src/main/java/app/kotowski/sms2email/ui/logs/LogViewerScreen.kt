package app.kotowski.sms2email.ui.logs

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ui.components.Banner
import app.kotowski.sms2email.ui.components.SystemScrollStateScrollIndicator
import app.kotowski.sms2email.ui.components.TextConfirmDialog
import app.kotowski.sms2email.util.ToastUtil
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogViewerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    var queryFilter by remember { mutableStateOf("") }

    var showClearConfirm by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val entries by viewModel.entries.collectAsState()

    val filteredEntries =
        remember(entries, queryFilter) {
            if (queryFilter.isBlank()) {
                entries
            } else {
                entries.filter { it.message.contains(queryFilter, ignoreCase = true) }
            }
        }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            if (uri != null) {
                // The app's full log file, not the on-screen (possibly filtered) entries;
                // the read and the write run off the main thread in the VM.
                scope.launch {
                    val exported = viewModel.exportToUri(uri)
                    ToastUtil.show(
                        context,
                        if (exported) R.string.log_viewer_exported else R.string.log_viewer_export_failed,
                    )
                }
            }
        }

    // Only ever called with a non-blank line: the top bar button is disabled while there are
    // no filtered entries, and the row click passes the rendered entry text.
    fun copyText(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
            ToastUtil.show(context, R.string.log_viewer_copied)
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.log_viewer_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Icon-only: the copied content is the log buffer right next to it, so
                    // the button needs no text label.
                    IconButton(
                        onClick = { copyText(filteredEntries.joinToString("\n") { it.toString() }) },
                        enabled = filteredEntries.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.log_viewer_copy),
                        )
                    }
                    // Destructive, so it sits last; the confirm dialog states the
                    // consequence before anything is wiped.
                    IconButton(
                        onClick = { showClearConfirm = true },
                        enabled = entries.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.log_viewer_clear),
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
            // Fixed above the scroll, like the History screen's banner: the explanation of
            // the viewer's limits (and the only way to get the full history off the device)
            // must stay reachable at any scroll position.
            val canSaveLogs = entries.isNotEmpty()

            Column(modifier = Modifier.fillMaxSize()) {
                Banner(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.log_viewer_banner_title),
                    // No size cap in the banner: while the setting is off there is no file
                    // to state a cap for, and the cap is named in Settings anyway.
                    description = stringResource(R.string.log_viewer_banner_desc, viewModel.maxEntries),
                    // A button without a file to save is a dead control: hide it while the log is empty.
                    buttonText = if (canSaveLogs) stringResource(R.string.log_viewer_export) else null,
                    // Dimmed (but still tappable) while the log-to-file setting is off: the
                    // tap explains why there is nothing to save.
                    buttonDimmed = canSaveLogs && !viewModel.logToFileEnabled,
                    onButtonClick =
                        if (canSaveLogs) {
                            if (viewModel.logToFileEnabled) {
                                { exportLauncher.launch("sms2email-logs-${LocalDate.now()}.txt") }
                            } else {
                                { ToastUtil.show(context, R.string.log_viewer_enable_toast) }
                            }
                        } else {
                            null
                        },
                    buttonTestTag = "save_to_file",
                )

                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(scrollState),
                ) {
                    OutlinedTextField(
                        value = queryFilter,
                        onValueChange = { queryFilter = it },
                        label = { Text(stringResource(R.string.log_viewer_search)) },
                        placeholder = { Text(stringResource(R.string.log_viewer_search_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (filteredEntries.isEmpty()) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text =
                                        if (entries.isEmpty()) {
                                            stringResource(R.string.log_viewer_empty)
                                        } else {
                                            stringResource(R.string.log_viewer_no_match)
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        } else {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                            ) {
                                filteredEntries.forEach { entry ->
                                    Text(
                                        text = entry.toString(),
                                        style =
                                            MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { copyText(entry.toString()) }
                                                .padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SystemScrollStateScrollIndicator(
                scrollState = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }

    if (showClearConfirm) {
        TextConfirmDialog(
            titleResId = R.string.log_viewer_clear_title,
            detail = stringResource(R.string.log_viewer_clear_confirm),
            confirmResId = R.string.log_viewer_clear,
            onConfirm = {
                showClearConfirm = false
                viewModel.clear()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}
