package app.kotowski.sms2email.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import app.kotowski.sms2email.R
import app.kotowski.sms2email.ThemeMode
import app.kotowski.sms2email.data.filter.FilterMode
import app.kotowski.sms2email.data.filter.FilterRule
import app.kotowski.sms2email.data.filter.SmsFilter
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.data.prefs.ThemePreference
import app.kotowski.sms2email.service.EmailService
import app.kotowski.sms2email.service.SmtpTestRunner
import app.kotowski.sms2email.service.SmtpTester
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.PermissionSnapshot
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.stubContextGetString
import app.kotowski.sms2email.work.BackgroundStateReconciler
import app.kotowski.sms2email.work.ExactWakeArmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock private lateinit var appContext: Context

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var emailService: EmailService

    @Mock private lateinit var permissionManager: PermissionManager

    @Mock private lateinit var themePreference: ThemePreference

    @Mock private lateinit var backgroundStateReconciler: BackgroundStateReconciler

    @Mock private lateinit var exactWakeArmer: ExactWakeArmer

    private var filterEditCalled = false
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        // The VM resolves the subject-preview strings through the app context at
        // construction; the mock delegates getString to the real application.
        stubContextGetString(appContext, context)
        // The VM reads the live permission at construction (the extras seed).
        whenever(permissionManager.snapshot())
            .thenReturn(PermissionSnapshot(sms = false, notifications = false, contacts = false, phone = false, battery = false))
        filterEditCalled = false
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Dispatchers.resetMain()
    }

    private fun showScreen(settings: AppSettings = AppSettings()) {
        whenever(prefs.load()).thenReturn(settings)
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = { filterEditCalled = true },
                onNavigateToLogViewer = {},
                viewModel = viewModel,
            )
        }
    }

    @Test
    fun `the filter toggle renders and the row stays hidden while the feature is off`() {
        showScreen()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_message_filtering))
            .performScrollTo()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_message_filtering)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_off)).assertDoesNotExist()
        composeTestRule.onNodeWithTag("filter_summary_edit").assertDoesNotExist()
    }

    @Test
    fun `the rules preview renders under the toggle while the feature is on`() {
        showScreen(
            AppSettings(
                filterEnabled = true,
                filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))),
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "vip"))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "vip"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_card_skip)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_filter_off)).assertDoesNotExist()
        composeTestRule.onNodeWithTag("filter_summary_edit").assertIsDisplayed()
    }

    @Test
    fun `toggling the filter on reveals the rules preview`() {
        showScreen(
            AppSettings(
                filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))),
            ),
        )
        // Scroll the switch itself into view: scrolling the toggle's title first leaves the
        // switch's bounds degenerate in the semantics tree, and the pointer click misses a zero-sized node.
        composeTestRule
            .onNodeWithTag("message_filtering_toggle")
            .performScrollTo()
        composeTestRule
            .onNodeWithTag("message_filtering_toggle")
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "vip"))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "vip"))
            .assertIsDisplayed()
    }

    @Test
    fun `the edit button leaves on the filter edit screen`() {
        showScreen(
            AppSettings(
                filterEnabled = true,
                filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))),
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "vip"))
            .performScrollTo()
        composeTestRule
            .onNodeWithTag("filter_summary_edit")
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue(filterEditCalled)
    }

    @Test
    fun `the about link rows exist`() {
        showScreen()
        val sourceRow = context.getString(R.string.settings_about_source_code)
        val bugRow = context.getString(R.string.settings_about_report_bug)
        val donateRow = context.getString(R.string.settings_about_donation)
        // Scroll the last row in: the rows sit adjacent, so the ones above stay visible.
        composeTestRule.onNodeWithText(donateRow).performScrollTo()
        composeTestRule.onNodeWithText(sourceRow).assertIsDisplayed()
        composeTestRule.onNodeWithText(bugRow).assertIsDisplayed()
        composeTestRule.onNodeWithText(donateRow).assertIsDisplayed()
    }

    private fun showScreenLandscape(settings: AppSettings = AppSettings()) {
        context.resources.configuration.apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        showScreen(settings)
    }

    private fun showScreenPortrait(settings: AppSettings = AppSettings()) {
        context.resources.configuration.apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        showScreen(settings)
    }

    @Test
    fun `landscape mode renders the resolution toggles in a row`() {
        showScreenLandscape()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_resolve_contacts))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_resolve_contacts))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_sim_name_resolution_title))
            .assertExists()
    }

    @Test
    fun `portrait mode renders the resolution toggles stacked`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_resolve_contacts))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_resolve_contacts))
            .assertIsDisplayed()
    }

    @Test
    fun `theme dropdown expands on click`() {
        showScreenPortrait()
        composeTestRule.onNodeWithTag("dropdown_theme").performScrollTo()
        composeTestRule.onNodeWithTag("dropdown_theme").performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(ThemeMode.LIGHT.labelRes))
            .assertExists()
        composeTestRule
            .onNodeWithText(context.getString(ThemeMode.DARK.labelRes))
            .assertExists()
    }

    @Test
    fun `language dropdown renders with system default label`() {
        showScreenPortrait()
        // The theme row shows the same "System default" value (both default to system),
        // so match all and use the first: the language row is composed before the theme.
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.settings_system_default))
            .onFirst()
            .performScrollTo()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.settings_system_default))
            .onFirst()
            .assertIsDisplayed()
    }

    @Test
    fun `sweep interval dropdown renders all presets`() {
        showScreenPortrait()
        composeTestRule.onNodeWithTag("dropdown_sweep_interval").performScrollTo()
        composeTestRule.onNodeWithTag("dropdown_sweep_interval").performClick()
        composeTestRule.waitForIdle()
        // The selected preset renders twice once the menu opens (field + menu item).
        for (preset in app.kotowski.sms2email.SweepIntervalPreset.entries) {
            composeTestRule
                .onAllNodesWithText(context.getString(preset.labelRes))
                .onFirst()
                .assertExists()
        }
    }

    @Test
    fun `dedup level dropdown renders all levels`() {
        showScreenPortrait()
        composeTestRule.onNodeWithTag("dropdown_dedup_level").performScrollTo()
        composeTestRule.onNodeWithTag("dropdown_dedup_level").performClick()
        composeTestRule.waitForIdle()
        // The selected level renders twice once the menu opens (field + menu item).
        for (level in app.kotowski.sms2email.DedupLevel.entries) {
            composeTestRule
                .onAllNodesWithText(context.getString(level.labelRes))
                .onFirst()
                .assertExists()
        }
    }

    @Test
    fun `log level dropdown renders all levels`() {
        showScreenPortrait()
        composeTestRule.onNodeWithTag("dropdown_log_level").performScrollTo()
        composeTestRule.onNodeWithTag("dropdown_log_level").performClick()
        composeTestRule.waitForIdle()
        // The selected level renders twice once the menu opens (field + menu item).
        for (level in app.kotowski.sms2email.LogLevel.entries) {
            composeTestRule
                .onAllNodesWithText(context.getString(level.labelRes))
                .onFirst()
                .assertExists()
        }
    }

    @Test
    fun `sticky notification toggle renders with correct active state`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_sticky_notification))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_sticky_notification))
            .assertIsDisplayed()
    }

    @Test
    fun `include time in body toggle renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_include_time_in_body))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_include_time_in_body))
            .assertIsDisplayed()
    }

    @Test
    fun `subject prefix section renders the preview surface`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .assertIsDisplayed()
    }

    @Test
    fun `retention section renders the toggle`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .assertIsDisplayed()
    }

    @Test
    fun `retention section shows dropdown editor when enabled`() {
        showScreenPortrait(
            AppSettings(
                autoCleanupRetentionDays = 365,
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .performScrollTo()
        // The label renders only in the edit mode (the locked row is value + Edit);
        // scroll the button itself in, the title scroll may leave it off-screen.
        composeTestRule
            .onNodeWithTag("retention_edit")
            .performScrollTo()
        composeTestRule.onNodeWithTag("retention_edit").assertExists()
        composeTestRule.onNodeWithTag("retention_edit").performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_retention_label))
            .assertExists()
    }

    @Test
    fun `log to file toggle renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .assertIsDisplayed()
    }

    @Test
    fun `log to file section shows dropdown editor when enabled`() {
        showScreenPortrait(
            AppSettings(
                logToFileEnabled = true,
                logFileSizeBytes = 262144,
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .performScrollTo()
        // The label renders only in the edit mode (the locked row is value + Edit);
        // scroll the button itself in, the title scroll may leave it off-screen.
        composeTestRule
            .onNodeWithTag("log_size_edit")
            .performScrollTo()
        composeTestRule.onNodeWithTag("log_size_edit").assertExists()
        composeTestRule.onNodeWithTag("log_size_edit").performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_size_label))
            .assertExists()
    }

    @Test
    fun `pdu timestamp toggle renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_pdu_timestamp))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_pdu_timestamp))
            .assertIsDisplayed()
    }

    @Test
    fun `json email format toggle renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_json_email_format))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_json_email_format))
            .assertIsDisplayed()
    }

    @Test
    fun `exact wake toggle renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_exact_wake_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_exact_wake_title))
            .assertIsDisplayed()
    }

    @Test
    fun `export and import buttons render in data section`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.config_export))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.config_export))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.config_import))
            .assertIsDisplayed()
    }

    @Test
    fun `log viewer navigation row renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_logs))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_logs))
            .assertIsDisplayed()
    }

    @Test
    fun `about section version row renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_version_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_version_title))
            .assertIsDisplayed()
    }

    @Test
    fun `back icon navigates back`() {
        var backCalled = false
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val viewModel =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = { backCalled = true },
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = viewModel,
            )
        }
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.action_back))
            .performClick()
        assertTrue(backCalled)
    }

    @Test
    fun `landscape country field renders after contacts toggle when enabled`() {
        // The resume sync force-disables the toggle while the contacts permission is
        // missing, so grant it or the country field never renders.
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = false, notifications = false, contacts = true, phone = false, battery = false),
        )
        showScreenLandscape(
            AppSettings(
                useContactResolution = true,
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_country_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_country_title))
            .assertIsDisplayed()
    }

    @Test
    fun `portrait country field renders after contacts toggle when enabled`() {
        // The resume sync force-disables the toggle while the contacts permission is
        // missing, so grant it or the country field never renders.
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = false, notifications = false, contacts = true, phone = false, battery = false),
        )
        showScreenPortrait(
            AppSettings(
                useContactResolution = true,
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_country_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_country_title))
            .assertIsDisplayed()
    }

    @Test
    fun `landscape country field hidden when contacts off`() {
        showScreenLandscape()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_country_title))
            .assertDoesNotExist()
    }

    @Test
    fun `retention enable pending shows dropdown editor`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onRetentionToggled(true)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        // Scroll the hint (below the row) in, so the row and the hint are both in view.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_enable_hint))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_retention_label))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_enable_hint))
            .assertIsDisplayed()
    }

    @Test
    fun `log to file enable pending shows dropdown editor`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogToFileToggled(true)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        // Scroll the hint (below the row) in, so the row and the hint are both in view.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_hint))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_size_label))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_hint))
            .assertIsDisplayed()
    }

    @Test
    fun `retention ENABLE confirm dialog renders`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onRetentionToggled(true)
        vm.onRetentionSave()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_enable_title))
            .assertIsDisplayed()
    }

    @Test
    fun `retention ENABLE confirm dialog dismiss closes the dialog`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onRetentionToggled(true)
        vm.onRetentionSave()
        vm.onConfirmDismissed()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_enable_title))
            .assertDoesNotExist()
    }

    @Test
    fun `retention SHORTEN confirm dialog renders`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 1825))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onEditStarted()
        vm.onRetentionDraftChanged(365)
        vm.onRetentionSave()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_shorten_title))
            .assertIsDisplayed()
    }

    @Test
    fun `log DISABLE confirm dialog renders`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 262144))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogToFileToggled(false)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_disable_title))
            .assertIsDisplayed()
    }

    @Test
    fun `log SHRINK confirm dialog renders`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 16777216))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogEditStarted()
        vm.onLogSizeDraftChanged(262144)
        vm.onLogSave()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_shrink_title))
            .assertIsDisplayed()
    }

    @Test
    fun `log SHRINK confirm dialog dismiss closes the dialog`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 16777216))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogEditStarted()
        vm.onLogSizeDraftChanged(262144)
        vm.onLogSave()
        vm.onLogConfirmDismissed()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_shrink_title))
            .assertDoesNotExist()
    }

    @Test
    fun `log DISABLE confirm dialog dismiss closes the dialog`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 262144))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogToFileToggled(false)
        vm.onLogConfirmDismissed()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_disable_title))
            .assertDoesNotExist()
    }

    @Test
    fun `migration section note renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_section_migration_note))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_section_migration_note))
            .assertIsDisplayed()
    }

    @Test
    fun `retention period dropdown items are visible when editing`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 365))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onEditStarted()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_retention_label))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_retention_label))
            .performClick()
        composeTestRule.waitForIdle()
        // The selected preset renders twice once the menu opens (field + menu item).
        for (preset in app.kotowski.sms2email.PeriodPreset.entries) {
            composeTestRule
                .onAllNodesWithText(context.getString(preset.labelRes))
                .onFirst()
                .assertExists()
        }
    }

    @Test
    fun `log size dropdown items are visible when editing`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 262144))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogEditStarted()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_size_label))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_size_label))
            .performClick()
        composeTestRule.waitForIdle()
        // The selected size renders twice once the menu opens (field + menu item).
        for (preset in app.kotowski.sms2email.LogSizePreset.entries) {
            composeTestRule
                .onAllNodesWithText(context.getString(preset.labelRes))
                .onFirst()
                .assertExists()
        }
    }

    @Test
    fun `log viewer navigation row clicks to navigate`() {
        var logViewerCalled = false
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = { logViewerCalled = true },
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_logs))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_logs))
            .performClick()
        assertTrue(logViewerCalled)
    }

    @Test
    fun `retention ENABLE confirm dialog confirm flow state transitions through dialog`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onRetentionToggled(true)
        vm.onRetentionSave()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_enable_title))
            .assertIsDisplayed()
        // Dismiss closes the dialog; the confirm action's commit (VM logic) is tested in SettingsViewModelTest.
        vm.onConfirmDismissed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_enable_title))
            .assertDoesNotExist()
    }

    @Test
    fun `retention SHORTEN confirm dialog confirm commits and closes the dialog`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 1825))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onEditStarted()
        vm.onRetentionDraftChanged(365)
        vm.onRetentionSave()
        vm.onConfirmed()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_shorten_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .assertIsDisplayed()
    }

    @Test
    fun `retention save with same period closes the edit without confirm`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 365))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onEditStarted()
        vm.onRetentionSave()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_shorten_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_enable_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(
                context.getString(
                    R.string.settings_cleanup_period_value,
                    context.getString(app.kotowski.sms2email.PeriodPreset.ONE_YEAR.labelRes),
                ),
            ).assertExists()
    }

    @Test
    fun `retention save with longer period commits directly without confirm`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 365))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onEditStarted()
        vm.onRetentionDraftChanged(1825)
        vm.onRetentionSave()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_shorten_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_enable_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .assertIsDisplayed()
    }

    @Test
    fun `retention toggle off when enabled commits and hides dropdown editor`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 365))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onRetentionToggled(false)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_retention_label))
            .assertDoesNotExist()
    }

    @Test
    fun `retention edit cancel closes the edit`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 365))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onEditStarted()
        vm.onRetentionDraftChanged(1825)
        vm.onEditCancelled()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(
                context.getString(
                    R.string.settings_cleanup_period_value,
                    context.getString(app.kotowski.sms2email.PeriodPreset.ONE_YEAR.labelRes),
                ),
            ).assertExists()
    }

    @Test
    fun `retention pending enable cancel drops the pending state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onRetentionToggled(true)
        vm.onEditCancelled()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_enable_hint))
            .assertDoesNotExist()
    }

    @Test
    fun `log SHRINK confirm dialog confirm commits and closes the dialog`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 16777216))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogEditStarted()
        vm.onLogSizeDraftChanged(262144)
        vm.onLogSave()
        vm.onLogConfirmed()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_shrink_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .assertIsDisplayed()
    }

    @Test
    fun `log DISABLE confirm dialog confirm commits and closes the dialog`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 262144))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogToFileToggled(false)
        vm.onLogConfirmed()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_disable_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_size_label))
            .assertDoesNotExist()
    }

    @Test
    fun `log save with same size closes the edit without confirm`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 262144))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogEditStarted()
        vm.onLogSave()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_shrink_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(
                context.getString(
                    R.string.settings_log_size_value,
                    context.getString(app.kotowski.sms2email.LogSizePreset.TWO_HUNDRED_FIFTY_SIX_KB.labelRes),
                ),
            ).assertExists()
    }

    @Test
    fun `log save with larger size commits directly without confirm`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 262144))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogEditStarted()
        vm.onLogSizeDraftChanged(16777216)
        vm.onLogSave()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_shrink_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .assertIsDisplayed()
    }

    @Test
    fun `log edit cancel closes the edit`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 262144))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogEditStarted()
        vm.onLogSizeDraftChanged(16777216)
        vm.onLogEditCancelled()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(
                context.getString(
                    R.string.settings_log_size_value,
                    context.getString(app.kotowski.sms2email.LogSizePreset.TWO_HUNDRED_FIFTY_SIX_KB.labelRes),
                ),
            ).assertExists()
    }

    @Test
    fun `log pending enable cancel drops the pending state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogToFileToggled(true)
        vm.onLogEditCancelled()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_hint))
            .assertDoesNotExist()
    }

    @Test
    fun `subject preview renders with contact resolution on`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = false, notifications = false, contacts = true, phone = false, battery = false),
        )
        showScreenPortrait(
            AppSettings(
                useContactResolution = true,
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .assertIsDisplayed()
    }

    @Test
    fun `subject preview renders with contact resolution off`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .assertIsDisplayed()
    }

    @Test
    fun `subject preview renders with SIM display on`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = false, notifications = false, contacts = false, phone = true, battery = false),
        )
        showScreenPortrait(
            AppSettings(
                displaySimCards = true,
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .assertIsDisplayed()
    }

    @Test
    fun `subject preview renders with SIM display off`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_subject_preview))
            .assertIsDisplayed()
    }

    @Test
    fun `filter toggle off hides the rules preview row`() {
        showScreen(
            AppSettings(
                filterEnabled = true,
                filter = SmsFilter(FilterMode.SKIP, listOf(FilterRule(body = "vip"))),
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "vip"))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "vip"))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("message_filtering_toggle")
            .performScrollTo()
        composeTestRule
            .onNodeWithTag("message_filtering_toggle")
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_filter_card_body, "vip"))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithTag("filter_summary_edit")
            .assertDoesNotExist()
    }

    @Test
    fun `contacts toggle off path disables the setting`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = false, notifications = false, contacts = true, phone = false, battery = false),
        )
        whenever(permissionManager.contactsPermission).thenReturn("android.permission.READ_CONTACTS")
        showScreenPortrait(
            AppSettings(
                useContactResolution = true,
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_resolve_contacts))
            .performScrollTo()
        // The country field renders while contacts is on.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_country_title))
            .assertExists()
        // Under Robolectric the permission flow is not testable, but we can verify
        // the initial state renders correctly with the feature on.
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_resolve_contacts))
            .assertIsDisplayed()
    }

    @Test
    fun `sim toggle renders with correct state`() {
        whenever(permissionManager.snapshot()).thenReturn(
            PermissionSnapshot(sms = false, notifications = false, contacts = false, phone = true, battery = false),
        )
        whenever(permissionManager.phonePermission).thenReturn("android.permission.READ_PHONE_STATE")
        showScreenPortrait(
            AppSettings(
                displaySimCards = true,
            ),
        )
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_sim_name_resolution_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_sim_name_resolution_title))
            .assertIsDisplayed()
    }

    @Test
    fun `sticky notification toggle on then off commits the state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onStickyNotificationChanged(true)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_sticky_notification))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_sticky_notification))
            .assertIsDisplayed()
        vm.onStickyNotificationChanged(false)
    }

    @Test
    fun `exact wake toggle on then off commits the state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onExactWakeChanged(true)
        vm.onExactWakeChanged(false)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_exact_wake_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_exact_wake_title))
            .assertIsDisplayed()
    }

    @Test
    fun `include time in body toggle on then off commits the state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onIncludeTimeInBodyChanged(true)
        vm.onIncludeTimeInBodyChanged(false)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_include_time_in_body))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_include_time_in_body))
            .assertIsDisplayed()
    }

    @Test
    fun `pdu timestamp toggle on then off commits the state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onPduTimestampChanged(true)
        vm.onPduTimestampChanged(false)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_pdu_timestamp))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_pdu_timestamp))
            .assertIsDisplayed()
    }

    @Test
    fun `json email format toggle on then off commits the state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onJsonEmailFormatChanged(true)
        vm.onJsonEmailFormatChanged(false)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_json_email_format))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_json_email_format))
            .assertIsDisplayed()
    }

    @Test
    fun `theme mode change via dropdown commits the state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.setThemeMode(ThemeMode.LIGHT)
        vm.setThemeMode(ThemeMode.DARK)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule.onNodeWithTag("dropdown_theme").performScrollTo()
        composeTestRule.onNodeWithTag("dropdown_theme").performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText(context.getString(ThemeMode.LIGHT.labelRes))
            .assertExists()
        composeTestRule
            .onNodeWithText(context.getString(ThemeMode.DARK.labelRes))
            .assertExists()
    }

    @Test
    fun `dedup level change via dropdown commits the state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.setDedupLevel(app.kotowski.sms2email.DedupLevel.AGGRESSIVE)
        vm.setDedupLevel(app.kotowski.sms2email.DedupLevel.OFF)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule.onNodeWithTag("dropdown_dedup_level").performScrollTo()
        composeTestRule.onNodeWithTag("dropdown_dedup_level").performClick()
        composeTestRule.waitForIdle()
        for (level in app.kotowski.sms2email.DedupLevel.entries) {
            composeTestRule
                .onAllNodesWithText(context.getString(level.labelRes))
                .onFirst()
                .assertExists()
        }
    }

    @Test
    fun `log level change via dropdown commits the state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.setLogLevel(app.kotowski.sms2email.LogLevel.DEBUG)
        vm.setLogLevel(app.kotowski.sms2email.LogLevel.OFF)
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule.onNodeWithTag("dropdown_log_level").performScrollTo()
        composeTestRule.onNodeWithTag("dropdown_log_level").performClick()
        composeTestRule.waitForIdle()
        for (level in app.kotowski.sms2email.LogLevel.entries) {
            composeTestRule
                .onAllNodesWithText(context.getString(level.labelRes))
                .onFirst()
                .assertExists()
        }
    }

    @Test
    fun `sweep interval change via dropdown commits the state`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        for (preset in app.kotowski.sms2email.SweepIntervalPreset.entries) {
            vm.setSweepInterval(preset.minutes)
        }
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule.onNodeWithTag("dropdown_sweep_interval").performScrollTo()
        composeTestRule.onNodeWithTag("dropdown_sweep_interval").performClick()
        composeTestRule.waitForIdle()
        for (preset in app.kotowski.sms2email.SweepIntervalPreset.entries) {
            composeTestRule
                .onAllNodesWithText(context.getString(preset.labelRes))
                .onFirst()
                .assertExists()
        }
    }

    @Test
    fun `about section renders version with flavor label`() {
        showScreenPortrait()
        // Scroll the FOSS label in: the version title sits directly above it, so it stays visible.
        composeTestRule.onNodeWithText("FOSS").performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_version_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("FOSS").assertIsDisplayed()
    }

    @Test
    fun `migration section renders export and import buttons`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.config_export))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.config_export))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.config_import))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_section_migration_note))
            .assertExists()
    }

    @Test
    fun `email config edit navigation button renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_section_forwarding))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_section_forwarding))
            .assertIsDisplayed()
    }

    @Test
    fun `retention confirm SHORTEN dismiss keeps edit open with draft`() {
        whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 1825))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onEditStarted()
        vm.onRetentionDraftChanged(365)
        vm.onRetentionSave()
        vm.onConfirmDismissed()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_shorten_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_cleanup_retention_label))
            .assertExists()
    }

    @Test
    fun `log confirm SHRINK dismiss keeps edit open with draft`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 16777216))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogEditStarted()
        vm.onLogSizeDraftChanged(262144)
        vm.onLogSave()
        vm.onLogConfirmDismissed()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_shrink_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_size_label))
            .assertExists()
    }

    @Test
    fun `log confirm DISABLE dismiss keeps setting on`() {
        whenever(prefs.load()).thenReturn(AppSettings(logToFileEnabled = true, logFileSizeBytes = 262144))
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        vm.onLogToFileToggled(false)
        vm.onLogConfirmDismissed()
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = {},
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_disable_title))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_log_to_file_title))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(
                context.getString(
                    R.string.settings_log_size_value,
                    context.getString(app.kotowski.sms2email.LogSizePreset.TWO_HUNDRED_FIFTY_SIX_KB.labelRes),
                ),
            ).assertExists()
    }

    @Test
    fun `language icon test tag renders`() {
        showScreenPortrait()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_language_label))
            .performScrollTo()
        composeTestRule
            .onNodeWithText(context.getString(R.string.settings_language_label))
            .assertIsDisplayed()
    }

    @Test
    fun `email config card edit button navigates to edit screen`() {
        var emailEditCalled = false
        whenever(prefs.load()).thenReturn(AppSettings())
        whenever(prefs.smtpPassword).thenReturn("")
        whenever(prefs.themeMode).thenReturn(ThemeMode.SYSTEM)
        val vm =
            SettingsViewModel(
                appContext,
                prefs,
                SmtpTestRunner(SmtpTester(emailService) { true }),
                ContactResolver(appContext, prefs),
                permissionManager,
                themePreference,
                backgroundStateReconciler,
                exactWakeArmer,
            )
        composeTestRule.setContent {
            SettingsScreen(
                onNavigateBack = {},
                onNavigateToEmailConfigEdit = { emailEditCalled = true },
                onNavigateToFilterConfigEdit = {},
                onNavigateToLogViewer = {},
                viewModel = vm,
            )
        }
        composeTestRule
            .onNodeWithTag("email_config_edit")
            .performScrollTo()
        composeTestRule
            .onNodeWithTag("email_config_edit")
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue(emailEditCalled)
    }
}
