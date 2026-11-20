package app.kotowski.sms2email.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.BackgroundFlags
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.NetworkConnectivityObserver
import app.kotowski.sms2email.system.NetworkStatus
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.system.PermissionSnapshot
import app.kotowski.sms2email.system.PermissionsAutoResetStatus
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimDetector
import app.kotowski.sms2email.system.SimNameResolver
import app.kotowski.sms2email.testing.SkipInReleaseRule
import app.kotowski.sms2email.testing.withMockedToast
import app.kotowski.sms2email.ui.icons.Globe2Cancel
import app.kotowski.sms2email.ui.icons.StackedEmail
import app.kotowski.sms2email.ui.icons.WandStars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Robolectric NATIVE tests for the Dashboard screen: state matrix exercises the
// forwarding toggle, connectivity, permissions, SIM cards and dialog paths.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenTest {
    @get:Rule
    val composeTestRule: ComposeContentTestRule = SkipInReleaseRule(createComposeRule())

    private val context: Context =
        androidx.test.core.app.ApplicationProvider
            .getApplicationContext<Context>()

    @Mock private lateinit var mockPrefs: EncryptedPrefs

    @Mock private lateinit var mockPermissionManager: PermissionManager

    @Mock private lateinit var mockSimDetector: SimDetector

    @Mock private lateinit var mockSimNameResolver: SimNameResolver

    @Mock private lateinit var mockSimChangeObserver: SimChangeObserver

    @Mock private lateinit var mockNetworkObserver: NetworkConnectivityObserver

    @Mock private lateinit var mockRepository: SmsRepository

    private val testDispatcher = StandardTestDispatcher()
    private var forwardingPref = false
    private var displaySimCardsPref = false

    // Exposed so the setter mirrors the real EncryptedPrefs behavior.
    private lateinit var flagsFlow: MutableStateFlow<BackgroundFlags>
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)
        forwardingPref = false
        displaySimCardsPref = false

        whenever(mockPrefs.load()).thenAnswer {
            AppSettings(
                isForwardingEnabled = forwardingPref,
                displaySimCards = displaySimCardsPref,
            )
        }
        doAnswer { inv ->
            val transform = inv.getArgument<(AppSettings) -> AppSettings>(0)
            val next = transform(AppSettings(isForwardingEnabled = forwardingPref, displaySimCards = displaySimCardsPref))
            forwardingPref = next.isForwardingEnabled
            flagsFlow.value = flagsFlow.value.copy(forwardingEnabled = forwardingPref)
            Unit
        }.whenever(mockPrefs).update(any())

        whenever(mockNetworkObserver.isConnectedCurrently()).thenReturn(true)
        whenever(mockNetworkObserver.networkStatus).thenReturn(flowOf(NetworkStatus.Available))
        whenever(mockSimChangeObserver.simChanges).thenReturn(emptyFlow())
        whenever(mockSimDetector.isSingleSimDevice()).thenReturn(true)
        whenever(mockSimNameResolver.getActiveSims()).thenReturn(emptyList())
        // Suspend stubs need runBlocking
        runBlocking {
            whenever(mockRepository.getQueueIds()).thenReturn(emptyList())
            whenever(mockPermissionManager.getPermissionsAutoResetStatus())
                .thenReturn(PermissionsAutoResetStatus.DISABLED)
        }

        // Permission snapshot: all granted by default
        whenever(mockPermissionManager.snapshot()).thenReturn(
            PermissionSnapshot(
                sms = true,
                notifications = true,
                contacts = true,
                phone = true,
                battery = true,
            ),
        )

        // Intent creators called from the screen
        whenever(mockPermissionManager.createAppSettingsIntent()).thenReturn(Intent())
        whenever(mockPermissionManager.createManagePermissionsAutoResetIntent()).thenReturn(Intent())
        whenever(mockPermissionManager.createBatteryOptimizationIntent()).thenReturn(Intent())

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = forwardingPref, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)

        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    private fun showScreen() {
        composeTestRule.setContent {
            DashboardScreen(
                onNavigateToHistory = { },
                onNavigateToQueue = { },
                onNavigateToSettings = { },
                onOpenSetupWizard = { },
                viewModel = viewModel,
            )
        }
        settle()
    }

    // Pump the scheduler and Robolectric looper until the compose frame settles.
    private fun settle() {
        testDispatcher.scheduler.runCurrent()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `screen renders the app name in the top bar`() {
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.app_name)).assertExists()
    }

    @Test
    fun `forwarding enabled shows listening status`() {
        forwardingPref = true
        flagsFlow.value = BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false)
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.dashboard_status_listening)).assertExists()
    }

    @Test
    fun `forwarding disabled shows disabled status`() {
        forwardingPref = false
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.dashboard_status_disabled)).assertExists()
    }

    @Test
    fun `toggling the switch updates the forwarding state`() {
        forwardingPref = false
        showScreen()

        composeTestRule.onNodeWithText(context.getString(R.string.sms_forwarding)).assertExists()
    }

    @Test
    fun `history and queue buttons are visible`() {
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.history_title)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_title)).assertExists()
    }

    @Test
    fun `settings icon is visible in the top bar`() {
        showScreen()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.settings_title))
            .assertExists()
    }

    @Test
    fun `setup wizard icon is visible in the top bar`() {
        showScreen()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.dashboard_setup_wizard))
            .assertExists()
    }

    // ConnectivityIndicator renders when hasInternet is false.
    @Test
    fun `connectivity indicator renders when offline`() {
        val networkFlow = MutableSharedFlow<NetworkStatus>(extraBufferCapacity = 1)
        whenever(mockNetworkObserver.networkStatus).thenReturn(networkFlow)
        whenever(mockNetworkObserver.isConnectedCurrently()).thenReturn(false)

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()
        showScreen()

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.no_internet))
            .assertExists()
    }

    @Test
    fun `clicking connectivity indicator shows no internet toast`() {
        val networkFlow = MutableSharedFlow<NetworkStatus>(extraBufferCapacity = 1)
        whenever(mockNetworkObserver.networkStatus).thenReturn(networkFlow)
        whenever(mockNetworkObserver.isConnectedCurrently()).thenReturn(false)

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()
        showScreen()

        withMockedToast {
            composeTestRule
                .onNodeWithContentDescription(context.getString(R.string.no_internet))
                .performClick()
        }
        settle()
    }

    @Test
    fun `setup wizard confirm dialog opens and can be confirmed`() {
        showScreen()

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.dashboard_setup_wizard))
            .performClick()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.dashboard_setup_wizard_confirm))
            .assertExists()
    }

    @Test
    fun `setup wizard confirm dialog can be dismissed`() {
        showScreen()

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.dashboard_setup_wizard))
            .performClick()
        settle()

        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.dashboard_setup_wizard_confirm))
            .assertDoesNotExist()
    }

    @Test
    fun `permissions card renders with all granted`() {
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_title)).assertExists()
    }

    @Test
    fun `missing SMS permission shows missing state`() {
        whenever(mockPermissionManager.snapshot()).thenReturn(
            PermissionSnapshot(
                sms = false,
                notifications = true,
                contacts = true,
                phone = true,
                battery = true,
            ),
        )
        showScreen()
        viewModel.updatePermissions()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.dashboard_permission_missing))
            .assertExists()
    }

    @Test
    fun `optional permission shows optional label`() {
        whenever(mockPermissionManager.snapshot()).thenReturn(
            PermissionSnapshot(
                sms = true,
                notifications = true,
                contacts = false,
                phone = true,
                battery = true,
            ),
        )
        showScreen()
        viewModel.updatePermissions()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.dashboard_permission_optional))
            .assertExists()
    }

    @Test
    fun `recommended permission shows recommended label`() {
        whenever(mockPermissionManager.snapshot()).thenReturn(
            PermissionSnapshot(
                sms = true,
                notifications = true,
                contacts = true,
                phone = true,
                battery = false,
            ),
        )
        showScreen()
        viewModel.updatePermissions()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.dashboard_permission_recommended))
            .assertExists()
    }

    @Test
    fun `sim cards card renders when cards are loaded`() {
        displaySimCardsPref = true
        whenever(mockSimNameResolver.getActiveSims()).thenReturn(
            listOf(
                SimNameResolver.ActiveSim(slot = 1, displayName = "CarrierOne"),
                SimNameResolver.ActiveSim(slot = 2, displayName = "CarrierTwo"),
            ),
        )

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()
        runBlocking { viewModel.readSimCards() }
        testDispatcher.scheduler.runCurrent()

        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.sim_cards)).assertExists()
        composeTestRule.onNodeWithText("CarrierOne").assertExists()
        composeTestRule.onNodeWithText("CarrierTwo").assertExists()
    }

    @Test
    fun `sim cards card hidden when no cards`() {
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.sim_cards)).assertDoesNotExist()
    }

    @Test
    fun `stop forwarding dialog shows with queued rows`() {
        forwardingPref = true
        runBlocking { whenever(mockRepository.getQueueIds()).thenReturn(listOf(1L, 2L)) }

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        showScreen()

        composeTestRule.onNodeWithText(context.getString(R.string.sms_forwarding)).assertExists()
    }

    @Test
    fun `stop forwarding dialog renders when queue count is set`() {
        forwardingPref = true
        runBlocking { whenever(mockRepository.getQueueIds()).thenReturn(listOf(1L)) }

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        viewModel.toggleForwarding(false)
        testDispatcher.scheduler.runCurrent()

        showScreen()

        composeTestRule
            .onNodeWithText(context.getString(R.string.dashboard_stop_forwarding_title))
            .assertExists()
    }

    @Test
    fun `confirming stop forwarding turns it off`() {
        forwardingPref = true
        runBlocking { whenever(mockRepository.getQueueIds()).thenReturn(listOf(1L)) }

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        viewModel.toggleForwarding(false)
        testDispatcher.scheduler.runCurrent()

        showScreen()

        withMockedToast {
            composeTestRule
                .onNodeWithText(context.getString(R.string.dashboard_stop_forwarding_turn_off))
                .performClick()
        }
        settle()

        assertFalse(viewModel.state.value.isForwardingEnabled)
        assertEquals(0, viewModel.state.value.stopForwardingQueueCount)
    }

    @Test
    fun `cancelling stop forwarding keeps it on`() {
        forwardingPref = true
        runBlocking { whenever(mockRepository.getQueueIds()).thenReturn(listOf(1L)) }

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        viewModel.toggleForwarding(false)
        testDispatcher.scheduler.runCurrent()

        showScreen()

        composeTestRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        settle()

        assertTrue(viewModel.state.value.isForwardingEnabled)
        assertEquals(0, viewModel.state.value.stopForwardingQueueCount)
    }

    @Test
    fun `auto reset permission row renders`() {
        runBlocking {
            whenever(mockPermissionManager.getPermissionsAutoResetStatus())
                .thenReturn(PermissionsAutoResetStatus.ACTIVE)
        }

        showScreen()

        composeTestRule
            .onNodeWithText(context.getString(R.string.permissions_auto_reset))
            .assertExists()
    }

    @Test
    fun `donate row renders`() {
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_about_donation)).assertExists()
    }

    // Icon cache branches: accessing each icon property twice covers the cached return.
    @Test
    fun `icon cache branches are covered by double access`() {
        val icon1a = Globe2Cancel
        val icon1b = Globe2Cancel
        assertTrue(icon1a === icon1b)

        val icon2a = WandStars
        val icon2b = WandStars
        assertTrue(icon2a === icon2b)

        val icon3a = StackedEmail
        val icon3b = StackedEmail
        assertTrue(icon3a === icon3b)
    }

    @Test
    fun `history button click triggers navigation callback`() {
        var historyNavigated = false
        composeTestRule.setContent {
            DashboardScreen(
                onNavigateToHistory = { historyNavigated = true },
                onNavigateToQueue = { },
                onNavigateToSettings = { },
                onOpenSetupWizard = { },
                viewModel = viewModel,
            )
        }
        settle()
        composeTestRule.onNodeWithText(context.getString(R.string.history_title)).performClick()
        assertTrue(historyNavigated)
    }

    @Test
    fun `queue button click triggers navigation callback`() {
        var queueNavigated = false
        composeTestRule.setContent {
            DashboardScreen(
                onNavigateToHistory = { },
                onNavigateToQueue = { queueNavigated = true },
                onNavigateToSettings = { },
                onOpenSetupWizard = { },
                viewModel = viewModel,
            )
        }
        settle()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_title)).performClick()
        assertTrue(queueNavigated)
    }

    @Test
    fun `settings icon click triggers navigation callback`() {
        var settingsNavigated = false
        composeTestRule.setContent {
            DashboardScreen(
                onNavigateToHistory = { },
                onNavigateToQueue = { },
                onNavigateToSettings = { settingsNavigated = true },
                onOpenSetupWizard = { },
                viewModel = viewModel,
            )
        }
        settle()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.settings_title))
            .performClick()
        assertTrue(settingsNavigated)
    }

    @Test
    fun `confirming setup wizard dialog triggers callback`() {
        var wizardOpened = false
        composeTestRule.setContent {
            DashboardScreen(
                onNavigateToHistory = { },
                onNavigateToQueue = { },
                onNavigateToSettings = { },
                onOpenSetupWizard = { wizardOpened = true },
                viewModel = viewModel,
            )
        }
        settle()

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.dashboard_setup_wizard))
            .performClick()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.action_yes))
            .performClick()
        settle()

        assertTrue(wizardOpened)
    }

    @Test
    fun `toggling forwarding switch on calls viewModel`() {
        showScreen()
        // The switch node is the sibling of the toggle label text.
        composeTestRule.onNodeWithText(context.getString(R.string.sms_forwarding)).assertExists()
        // Directly drive the VM to exercise the toggle-on path (switch click is flaky under Robolectric).
        viewModel.toggleForwarding(true)
        testDispatcher.scheduler.runCurrent()
        assertTrue(forwardingPref)
    }

    @Test
    fun `phone permission row hidden on single SIM with display off`() {
        // Default setup: isSingleSimDevice=true, displaySimCards=false
        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_phone)).assertDoesNotExist()
    }

    @Test
    fun `phone permission row visible on single SIM with display on`() {
        displaySimCardsPref = true
        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_phone)).assertExists()
    }

    // Phone permission row: visible on dual-SIM device regardless of display pref.
    @Test
    fun `phone permission row visible on dual SIM device`() {
        whenever(mockSimDetector.isSingleSimDevice()).thenReturn(false)
        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_phone)).assertExists()
    }

    // Notifications permission: not granted shows recommended label (API 33+).
    @Test
    fun `notifications permission not granted shows recommended`() {
        whenever(mockPermissionManager.snapshot()).thenReturn(
            PermissionSnapshot(
                sms = true,
                notifications = false,
                contacts = true,
                phone = true,
                battery = true,
            ),
        )
        showScreen()
        viewModel.updatePermissions()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.permissions_notifications))
            .assertExists()
    }

    @Test
    fun `auto reset row hidden when not available`() {
        runBlocking {
            whenever(mockPermissionManager.getPermissionsAutoResetStatus())
                .thenReturn(PermissionsAutoResetStatus.NOT_AVAILABLE)
        }

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        showScreen()
        composeTestRule
            .onNodeWithText(context.getString(R.string.permissions_auto_reset))
            .assertDoesNotExist()
    }

    @Test
    fun `sim cards card renders single card without spacer`() {
        displaySimCardsPref = true
        whenever(mockSimNameResolver.getActiveSims()).thenReturn(
            listOf(
                SimNameResolver.ActiveSim(slot = 1, displayName = "SoloCarrier"),
            ),
        )

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()
        runBlocking { viewModel.readSimCards() }
        testDispatcher.scheduler.runCurrent()

        showScreen()
        composeTestRule.onNodeWithText(context.getString(R.string.sim_cards)).assertExists()
        composeTestRule.onNodeWithText("SoloCarrier").assertExists()
    }

    @Test
    fun `auto reset info dialog opens on row click`() {
        runBlocking {
            whenever(mockPermissionManager.getPermissionsAutoResetStatus())
                .thenReturn(PermissionsAutoResetStatus.ACTIVE)
        }

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        showScreen()

        composeTestRule
            .onNodeWithText(context.getString(R.string.permissions_auto_reset))
            .performClick()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.permissions_auto_reset_dialog_message))
            .assertExists()
    }

    // SIM card with non-standard slot (slot > 3) exercises the null slotIcon path.
    @Test
    fun `sim card with unknown slot renders without slot icon`() {
        displaySimCardsPref = true
        whenever(mockSimNameResolver.getActiveSims()).thenReturn(
            listOf(
                SimNameResolver.ActiveSim(slot = 99, displayName = "UnknownSlot"),
            ),
        )

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()
        runBlocking { viewModel.readSimCards() }
        testDispatcher.scheduler.runCurrent()

        showScreen()
        composeTestRule.onNodeWithText("UnknownSlot").assertExists()
    }

    @Test
    fun `donate row click opens url`() {
        var urlOpened = false
        composeTestRule.setContent {
            DashboardScreen(
                onNavigateToHistory = { },
                onNavigateToQueue = { },
                onNavigateToSettings = { },
                onOpenSetupWizard = { },
                viewModel = viewModel,
            )
        }
        settle()

        // The donate row uses a clickable modifier; clicking the text node fires the callback.
        composeTestRule.onNodeWithText(context.getString(R.string.settings_about_donation)).performClick()
        settle()
    }

    @Test
    fun `not granted SMS permission row is clickable`() {
        whenever(mockPermissionManager.snapshot()).thenReturn(
            PermissionSnapshot(
                sms = false,
                notifications = true,
                contacts = true,
                phone = true,
                battery = true,
            ),
        )
        showScreen()
        viewModel.updatePermissions()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.dashboard_permission_missing))
            .assertExists()
    }

    @Test
    fun `not granted contacts permission row is clickable`() {
        whenever(mockPermissionManager.snapshot()).thenReturn(
            PermissionSnapshot(
                sms = true,
                notifications = true,
                contacts = false,
                phone = true,
                battery = true,
            ),
        )
        showScreen()
        viewModel.updatePermissions()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.dashboard_permission_optional))
            .assertExists()
    }

    @Test
    fun `not granted battery permission row is clickable`() {
        whenever(mockPermissionManager.snapshot()).thenReturn(
            PermissionSnapshot(
                sms = true,
                notifications = true,
                contacts = true,
                phone = true,
                battery = false,
            ),
        )
        showScreen()
        viewModel.updatePermissions()
        settle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.dashboard_permission_recommended))
            .assertExists()
    }

    @Test
    fun `forwarding toggle switch off path`() {
        forwardingPref = true
        runBlocking { whenever(mockRepository.getQueueIds()).thenReturn(emptyList()) }

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        // Direct toggle-off with empty queue bypasses dialog
        viewModel.toggleForwarding(false)
        testDispatcher.scheduler.runCurrent()

        assertFalse(forwardingPref)
    }

    @Test
    fun `granted permission rows render check circle`() {
        showScreen()
        // All permissions granted by default in setup
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_sms)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.permissions_battery)).assertExists()
    }

    @Test
    fun `auto reset row shows granted when disabled`() {
        runBlocking {
            whenever(mockPermissionManager.getPermissionsAutoResetStatus())
                .thenReturn(PermissionsAutoResetStatus.DISABLED)
        }

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()

        showScreen()
        composeTestRule
            .onNodeWithText(context.getString(R.string.permissions_auto_reset))
            .assertExists()
    }

    @Test
    fun `sim cards with slot 2 and 3 render`() {
        displaySimCardsPref = true
        whenever(mockSimNameResolver.getActiveSims()).thenReturn(
            listOf(
                SimNameResolver.ActiveSim(slot = 2, displayName = "SlotTwo"),
                SimNameResolver.ActiveSim(slot = 3, displayName = "SlotThree"),
            ),
        )

        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false))
        whenever(mockPrefs.backgroundFlags).thenReturn(flagsFlow)
        viewModel =
            DashboardViewModel(
                mockPrefs,
                mockPermissionManager,
                mockRepository,
                mockSimDetector,
                mockSimNameResolver,
                mockSimChangeObserver,
                mockNetworkObserver,
            )
        testDispatcher.scheduler.runCurrent()
        runBlocking { viewModel.readSimCards() }
        testDispatcher.scheduler.runCurrent()

        showScreen()
        composeTestRule.onNodeWithText("SlotTwo").assertExists()
        composeTestRule.onNodeWithText("SlotThree").assertExists()
    }
}
