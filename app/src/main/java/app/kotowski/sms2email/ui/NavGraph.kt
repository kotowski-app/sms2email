package app.kotowski.sms2email.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.kotowski.sms2email.ui.dashboard.DashboardScreen
import app.kotowski.sms2email.ui.history.HistoryScreen
import app.kotowski.sms2email.ui.logs.LogViewerScreen
import app.kotowski.sms2email.ui.onboarding.OnboardingScreen
import app.kotowski.sms2email.ui.queue.QueueScreen
import app.kotowski.sms2email.ui.scamwarning.ScamWarningScreen
import app.kotowski.sms2email.ui.settings.EmailConfigEditScreen
import app.kotowski.sms2email.ui.settings.FilterConfigEditScreen
import app.kotowski.sms2email.ui.settings.SettingsScreen
import app.kotowski.sms2email.ui.smsdetail.SmsDetailScreen

internal fun NavController.safeNavigate(
    route: String,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route, builder)
    }
}

internal fun NavController.safePopBackStack() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        if (previousBackStackEntry != null) {
            popBackStack()
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

// The SMS-detail route: its prefix and the single path argument's name (top-level so the
// route template and the argument binding share them).
private const val SMS_DETAIL_ROUTE_PREFIX = "sms_detail/"
private const val SMS_DETAIL_ARGUMENT_NAME = "smsId"

sealed class Screen(
    val route: String,
) {
    object ScamWarning : Screen("scam_warning")

    object Onboarding : Screen("onboarding")

    object Dashboard : Screen("dashboard")

    object History : Screen("history")

    object Queue : Screen("queue")

    object Settings : Screen("settings")

    object EmailConfigEdit : Screen("email_config_edit")

    object FilterConfigEdit : Screen("filter_config_edit")

    object LogViewer : Screen("log_viewer")

    object SmsDetail : Screen(SMS_DETAIL_ROUTE_PREFIX + "{${SMS_DETAIL_ARGUMENT_NAME}}") {
        fun createRoute(smsId: Long) = SMS_DETAIL_ROUTE_PREFIX + smsId
    }
}

@Composable
fun AppNavGraph(
    onboardingComplete: Boolean,
    scamWarningDismissed: Boolean,
    onDismissScamWarning: () -> Unit,
) {
    val navController = rememberNavController()

    fun getNextDestination(): String =
        when {
            !scamWarningDismissed -> Screen.ScamWarning.route
            onboardingComplete -> Screen.Dashboard.route
            else -> Screen.Onboarding.route
        }

    NavHost(
        navController = navController,
        startDestination = getNextDestination(),
    ) {
        composable(Screen.ScamWarning.route) {
            ScamWarningScreen(
                onDismiss = {
                    onDismissScamWarning()
                    navController.safeNavigate(
                        if (onboardingComplete) {
                            Screen.Dashboard.route
                        } else {
                            Screen.Onboarding.route
                        },
                    ) {
                        popUpTo(Screen.ScamWarning.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Onboarding.route) {
            val context = LocalContext.current

            OnboardingScreen(
                onBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.safePopBackStack()
                    } else {
                        context.findActivity()?.finish()
                    }
                },
                onComplete = {
                    // The wizard opened from the Dashboard sits above the existing Dashboard
                    // entry: popping it lands on that entry instead of pushing a duplicate.
                    if (navController.previousBackStackEntry?.destination?.route == Screen.Dashboard.route) {
                        navController.safePopBackStack()
                    } else {
                        navController.safeNavigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToHistory = { navController.safeNavigate(Screen.History.route) },
                onNavigateToQueue = { navController.safeNavigate(Screen.Queue.route) },
                onNavigateToSettings = { navController.safeNavigate(Screen.Settings.route) },
                onOpenSetupWizard = {
                    navController.safeNavigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onSmsClicked = { smsId -> navController.safeNavigate(Screen.SmsDetail.createRoute(smsId)) },
            )
        }

        composable(Screen.Queue.route) {
            QueueScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onSmsClicked = { smsId -> navController.safeNavigate(Screen.SmsDetail.createRoute(smsId)) },
            )
        }

        composable(
            route = Screen.SmsDetail.route,
            arguments = listOf(navArgument(SMS_DETAIL_ARGUMENT_NAME) { type = NavType.LongType }),
        ) { backStackEntry ->
            val smsId = backStackEntry.arguments?.getLong(SMS_DETAIL_ARGUMENT_NAME) ?: return@composable
            SmsDetailScreen(
                smsId = smsId,
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToEmailConfigEdit = { navController.safeNavigate(Screen.EmailConfigEdit.route) },
                onNavigateToFilterConfigEdit = { navController.safeNavigate(Screen.FilterConfigEdit.route) },
                onNavigateToLogViewer = { navController.safeNavigate(Screen.LogViewer.route) },
            )
        }

        composable(Screen.EmailConfigEdit.route) {
            EmailConfigEditScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(Screen.FilterConfigEdit.route) {
            FilterConfigEditScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(Screen.LogViewer.route) {
            LogViewerScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }
    }
}
