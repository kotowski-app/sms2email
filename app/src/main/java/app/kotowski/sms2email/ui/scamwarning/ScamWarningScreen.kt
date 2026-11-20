package app.kotowski.sms2email.ui.scamwarning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ui.components.RememberOnResume

@Composable
private fun AutoScaledContent(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = content,
    ) { measurables, constraints ->
        require(measurables.size == 1) { "AutoScaledContent requires exactly one child" }
        val measurable = measurables.first()

        val placeable =
            measurable.measure(
                Constraints(
                    minWidth = constraints.maxWidth,
                    maxWidth = constraints.maxWidth,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                ),
            )

        val scale =
            if (placeable.height > constraints.maxHeight && constraints.maxHeight > 0) {
                constraints.maxHeight.toFloat() / placeable.height
            } else {
                1f
            }

        layout(constraints.maxWidth, constraints.maxHeight) {
            val scaledWidth = (placeable.width * scale).toInt()
            val scaledHeight = (placeable.height * scale).toInt()

            val offset =
                alignment.align(
                    size = IntSize(scaledWidth, scaledHeight),
                    space = IntSize(constraints.maxWidth, constraints.maxHeight),
                    layoutDirection = layoutDirection,
                )

            val px = offset.x - (placeable.width * (1f - scale) / 2f).toInt()
            val py = offset.y - (placeable.height * (1f - scale) / 2f).toInt()

            placeable.placeWithLayer(px, py) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin.Center
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScamWarningScreen(
    onDismiss: () -> Unit,
    viewModel: ScamWarningViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    activity?.let { act ->
        DisposableEffect(Unit) {
            val controller = WindowInsetsControllerCompat(act.window, act.window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val secondsRemaining by viewModel.secondsRemaining.collectAsState()
    val progress = remember { Animatable(1f) }

    LaunchedEffect(secondsRemaining) {
        progress.snapTo(secondsRemaining.toFloat() / AppConfig.SCAM_WARNING_TIMER_SECONDS)
    }

    RememberOnResume(onResume = { viewModel.reset() }, onPause = { viewModel.reset() })

    DisposableEffect(Unit) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == Intent.ACTION_USER_PRESENT) {
                        viewModel.reset()
                    }
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    BackHandler {
        viewModel.reset()
    }

    val canDismiss = secondsRemaining <= 0

    val titleStyle = if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium
    val titleIconSize = if (isLandscape) 20.dp else 24.dp
    val topContentSpacer = if (isLandscape) 12.dp else 24.dp
    val titleCardSpacer = if (isLandscape) 16.dp else 32.dp
    val cardBottomSpacer = if (isLandscape) 16.dp else 32.dp
    val btnFontSize = if (isLandscape) 14.sp else 18.sp
    val cardTitleStyle = if (isLandscape) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
    val cardBodyStyle = if (isLandscape) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val cardInnerPadding = if (isLandscape) 12.dp else 16.dp

    Scaffold(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) { padding ->
        AutoScaledContent(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(if (isLandscape) 8.dp else 16.dp),
            alignment = if (isLandscape) Alignment.Center else Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(topContentSpacer))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(titleIconSize),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.scam_warning_title),
                        style = titleStyle,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                Spacer(modifier = Modifier.height(titleCardSpacer))

                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScamWarningCards(
                            innerPadding = cardInnerPadding,
                            titleStyle = cardTitleStyle,
                            bodyStyle = cardBodyStyle,
                            cardModifier = { Modifier.weight(1f) },
                        )
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        ScamWarningCards(
                            innerPadding = cardInnerPadding,
                            titleStyle = cardTitleStyle,
                            bodyStyle = cardBodyStyle,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(cardBottomSpacer))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isLandscape) 48.dp else 24.dp),
                ) {
                    if (!canDismiss) {
                        LinearProgressIndicator(
                            progress = { 1f - progress.value },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(if (isLandscape) 6.dp else 8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                            trackColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.3f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = pluralStringResource(R.plurals.scam_warning_timer, secondsRemaining, secondsRemaining),
                            style = if (isLandscape) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.reset()
                            onDismiss()
                        },
                        enabled = canDismiss,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (isLandscape) 40.dp else 48.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.scam_warning_proceed),
                            fontSize = btnFontSize,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

private data class ScamWarningCardSpec(
    val titleRes: Int,
    val textRes: Int,
    val isError: Boolean,
)

private val scamWarningCardSpecs =
    listOf(
        ScamWarningCardSpec(R.string.scam_warning_what, R.string.scam_warning_reads_sms, false),
        ScamWarningCardSpec(R.string.scam_warning_scam_alert, R.string.scam_warning_scam_text, true),
        ScamWarningCardSpec(R.string.scam_warning_what_to_do, R.string.scam_warning_contact, false),
    )

@Composable
private fun ScamWarningCards(
    innerPadding: Dp,
    titleStyle: TextStyle,
    bodyStyle: TextStyle,
    cardModifier: @Composable () -> Modifier = { Modifier },
) {
    scamWarningCardSpecs.forEach { spec ->
        ScamWarningCard(
            title = stringResource(spec.titleRes),
            text = stringResource(spec.textRes),
            isError = spec.isError,
            innerPadding = innerPadding,
            titleStyle = titleStyle,
            bodyStyle = bodyStyle,
            modifier = cardModifier(),
        )
    }
}

@Composable
private fun ScamWarningCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    innerPadding: Dp = 16.dp,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    bodyStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    textSpacer: Dp = 8.dp,
) {
    val backgroundColor =
        if (isError) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(innerPadding),
        ) {
            Text(
                text = title,
                style = titleStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(textSpacer))
            Text(
                text = text,
                style = bodyStyle,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
