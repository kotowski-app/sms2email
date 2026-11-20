package app.kotowski.sms2email.work

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.BackgroundFlags
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.OpenAppIntentProvider
import app.kotowski.sms2email.testing.WorkManagerDelegate
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

// The notification half of the reconciler, mirroring the plain-JVM BackgroundStateReconcilerTest
// but with the REAL application context: ForwardingNotification.show()/dismiss() post to the
// shadow NotificationManager, so the tests assert on the posted set (worker steps stay mocked).
// POST_NOTIFICATIONS is granted in setUp; the denied path is covered by ForwardingNotificationTest.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackgroundStateReconcilerNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock
    private lateinit var workManager: WorkManagerImpl

    @Mock
    private lateinit var workInfosFuture: ListenableFuture<List<WorkInfo>>

    @Mock
    private lateinit var cleanupWorkerManager: CleanupWorkerManager

    @Mock
    private lateinit var smsRepository: SmsRepository

    @Mock
    private lateinit var prefs: EncryptedPrefs

    @Mock
    private lateinit var exactWakeArmer: ExactWakeArmer

    private lateinit var closeable: AutoCloseable

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var flagsFlow: MutableStateFlow<BackgroundFlags>

    private lateinit var observer: BackgroundStateReconciler

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        WorkManagerDelegate.install(workManager)
        // The on-branch reconciles the sweep through the WorkManager query; default to
        // "no live work". isDone=true puts await() on its fast path (stubbed no-arg get()).
        whenever(workManager.getWorkInfosForUniqueWork(RetryWorker.WORK_NAME)).thenReturn(workInfosFuture)
        whenever(workInfosFuture.isDone()).thenReturn(true)
        whenever(workInfosFuture.get()).thenReturn(emptyList())
        flagsFlow = MutableStateFlow(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false))
        whenever(prefs.backgroundFlags).thenReturn(flagsFlow)
        runBlocking {
            whenever(smsRepository.getAutoSendableIds()).thenReturn(emptyList())
            whenever(smsRepository.getStaleSending(any())).thenReturn(emptyList())
        }
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        observer =
            BackgroundStateReconciler(
                context,
                prefs,
                smsRepository,
                cleanupWorkerManager,
                OpenAppIntentProvider { Intent() },
                exactWakeArmer,
                CoroutineScope(testDispatcher),
            )
    }

    @After
    fun tearDown() {
        WorkManagerDelegate.uninstall()
        closeable.close()
    }

    private fun startWith(flags: BackgroundFlags) {
        flagsFlow.value = flags
        observer.start()
        testDispatcher.scheduler.runCurrent()
    }

    // The shadow's posted ids (the notification's constant id is private, so the
    // assertions are on the collection, not the constant).
    private fun postedIds(): List<Int> =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .getActiveNotifications()
            .map { it.id }

    private fun assertPosted() {
        assertTrue(postedIds().isNotEmpty(), "the forwarding notification is not posted")
    }

    private fun assertNotPosted() {
        assertTrue(postedIds().isEmpty(), "the forwarding notification is posted")
    }

    @Test
    fun `forwarding on and the sticky setting on posts the notification at startup`() {
        startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false, showStickyNotification = true))

        assertPosted()
    }

    @Test
    fun `forwarding on and the sticky setting off posts nothing`() {
        startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false, showStickyNotification = false))

        assertNotPosted()
    }

    @Test
    fun `forwarding off and the sticky setting on posts nothing`() {
        startWith(BackgroundFlags(forwardingEnabled = false, autoCleanupEnabled = false, showStickyNotification = true))

        assertNotPosted()
    }

    @Test
    fun `turning the sticky setting off dismisses the posted notification`() {
        startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false, showStickyNotification = true))
        assertPosted()

        flagsFlow.value = BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false, showStickyNotification = false)
        testDispatcher.scheduler.runCurrent()

        assertNotPosted()
    }

    @Test
    fun `turning the sticky setting on re-posts the notification`() {
        startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false, showStickyNotification = false))
        assertNotPosted()

        flagsFlow.value = BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false, showStickyNotification = true)
        testDispatcher.scheduler.runCurrent()

        assertPosted()
    }

    @Test
    fun `syncNotification posts from the current on+on flags without a flag change`() {
        flagsFlow.value = BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false, showStickyNotification = true)
        runBlocking { observer.syncNotification() }

        assertPosted()
    }

    @Test
    fun `syncNotification dismisses from the current on+off flags without a flag change`() {
        startWith(BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false, showStickyNotification = true))
        assertPosted()

        // The collector is deliberately not run: the re-sync must act on the CURRENT
        // flags alone, like the permission-grant path in the settings screen.
        flagsFlow.value = BackgroundFlags(forwardingEnabled = true, autoCleanupEnabled = false, showStickyNotification = false)
        runBlocking { observer.syncNotification() }

        assertNotPosted()
    }
}
