package app.kotowski.sms2email.work

import androidx.work.WorkManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.verify

class CleanupWorkerManagerTest {
    @Mock private lateinit var workManager: WorkManager

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
    fun `enqueue calls WorkManager enqueueUniquePeriodicWork`() {
        val manager = CleanupWorkerManager(workManager)
        manager.enqueue()
        verify(workManager).enqueueUniquePeriodicWork(any(), any(), any())
    }

    @Test
    fun `cancel calls WorkManager cancelUniqueWork`() {
        val manager = CleanupWorkerManager(workManager)
        manager.cancel()
        verify(workManager).cancelUniqueWork(HistoryCleanupWorker.WORK_NAME)
    }
}
