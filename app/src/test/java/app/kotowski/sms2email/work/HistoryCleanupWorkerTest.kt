package app.kotowski.sms2email.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryCleanupWorkerTest {
    @Mock private lateinit var smsRepository: SmsRepository

    @Mock private lateinit var prefs: EncryptedPrefs
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var closeable: AutoCloseable

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    @Test
    fun `worker returns success when auto-cleanup is disabled`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings())
            val worker =
                TestListenableWorkerBuilder<HistoryCleanupWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: androidx.work.WorkerParameters,
                            ) = HistoryCleanupWorker(appContext, workerParameters, smsRepository, prefs)
                        },
                    ).build()
            val result = worker.startWork().get()
            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository, never()).deleteExpired(any())
        }

    @Test
    fun `worker returns success when retention days is 30`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 30))
            whenever(smsRepository.deleteExpired(any())).thenReturn(5)
            val worker =
                TestListenableWorkerBuilder<HistoryCleanupWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: androidx.work.WorkerParameters,
                            ) = HistoryCleanupWorker(appContext, workerParameters, smsRepository, prefs)
                        },
                    ).build()
            val result = worker.startWork().get()
            assertEquals(ListenableWorker.Result.success(), result)
            verify(smsRepository).deleteExpired(30L)
        }

    @Test
    fun `worker returns success for 1 day retention`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 1))
            whenever(smsRepository.deleteExpired(any())).thenReturn(12)
            val worker =
                TestListenableWorkerBuilder<HistoryCleanupWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: androidx.work.WorkerParameters,
                            ) = HistoryCleanupWorker(appContext, workerParameters, smsRepository, prefs)
                        },
                    ).build()
            val result = worker.startWork().get()
            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `worker returns success for 365 day retention`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(autoCleanupRetentionDays = 365))
            whenever(smsRepository.deleteExpired(any())).thenReturn(0)
            val worker =
                TestListenableWorkerBuilder<HistoryCleanupWorker>(context)
                    .setWorkerFactory(
                        object : androidx.work.WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: androidx.work.WorkerParameters,
                            ) = HistoryCleanupWorker(appContext, workerParameters, smsRepository, prefs)
                        },
                    ).build()
            val result = worker.startWork().get()
            assertEquals(ListenableWorker.Result.success(), result)
        }
}
