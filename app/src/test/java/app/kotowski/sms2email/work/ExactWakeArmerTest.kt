package app.kotowski.sms2email.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import app.kotowski.sms2email.LogLevel
import app.kotowski.sms2email.data.database.Sms
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.PermissionManager
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.util.Logger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExactWakeArmerTest {
    @Mock private lateinit var context: Context

    @Mock private lateinit var alarmManager: AlarmManager

    @Mock private lateinit var repository: SmsRepository

    @Mock private lateinit var prefs: EncryptedPrefs

    @Mock private lateinit var permissionManager: PermissionManager

    private lateinit var closeable: AutoCloseable

    private lateinit var armer: ExactWakeArmer

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        // Robolectric's PendingIntent shadow builds a ComponentName from the context's
        // package; the mock context answers none without this.
        whenever(context.packageName).thenReturn("app.kotowski.sms2email")
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        // Gate on by default: the feature is on and the permission is granted.
        whenever(prefs.load()).thenReturn(AppSettings(exactWakeEnabled = true))
        whenever(permissionManager.canScheduleExactAlarms()).thenReturn(true)
        armer = ExactWakeArmer(context, repository, prefs, permissionManager)
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    private fun armedAt(): Long {
        val captor = argumentCaptor<Long>()
        verify(alarmManager).setExactAndAllowWhileIdle(
            eq(AlarmManager.RTC_WAKEUP),
            captor.capture(),
            any<PendingIntent>(),
        )
        return captor.firstValue
    }

    @Test
    fun `exactWakeActive follows the setting and the permission`() {
        assertTrue(armer.exactWakeActive())
        whenever(prefs.load()).thenReturn(AppSettings(exactWakeEnabled = false))
        assertFalse(armer.exactWakeActive())
        whenever(prefs.load()).thenReturn(AppSettings(exactWakeEnabled = true))
        whenever(permissionManager.canScheduleExactAlarms()).thenReturn(false)
        assertFalse(armer.exactWakeActive())
    }

    @Test
    fun `armSweepAlarm sets the exact alarm while the gate is on`() {
        val at = System.currentTimeMillis() + 60_000L

        armer.armSweepAlarm(at)

        assertEquals(at, armedAt())
    }

    @Test
    fun `armSweepAlarm clamps a past time to now`() {
        val before = System.currentTimeMillis()

        armer.armSweepAlarm(before - 60_000L)

        val at = armedAt()
        assertTrue(at >= before)
    }

    @Test
    fun `armSweepAlarm is a no-op while the gate is off`() {
        whenever(permissionManager.canScheduleExactAlarms()).thenReturn(false)

        armer.armSweepAlarm(System.currentTimeMillis() + 60_000L)

        verify(alarmManager, never()).setExactAndAllowWhileIdle(any<Int>(), any<Long>(), any<PendingIntent>())
    }

    // The revoke race: the permission dies between the gate check and the set, so the
    // platform throws SecurityException. The arm must degrade to a log, not propagate.
    @Test
    fun `armSweepAlarm degrades to a log when the platform set throws`() {
        whenever(alarmManager.setExactAndAllowWhileIdle(any<Int>(), any<Long>(), any<PendingIntent>()))
            .thenThrow(SecurityException("revoked"))
        Logger.setLevel(LogLevel.DEBUG)
        LogBuffer.clear()

        armer.armSweepAlarm(System.currentTimeMillis() + 60_000L)

        verify(alarmManager).setExactAndAllowWhileIdle(any<Int>(), any<Long>(), any<PendingIntent>())
        val entry = LogBuffer.entries.last()
        assertEquals("W", entry.level)
        assertEquals("ExactWakeArmer", entry.tag)
        assertTrue(entry.message.startsWith("sweep exact alarm arm failed"))
    }

    @Test
    fun `cancelSweepAlarm cancels the sweep alarm regardless of the gate`() {
        whenever(permissionManager.canScheduleExactAlarms()).thenReturn(false)

        armer.cancelSweepAlarm()

        verify(alarmManager).cancel(any<PendingIntent>())
    }

    @Test
    fun `rearmRetryAlarm cancels but does not arm below the threshold`() {
        armer.rearmRetryAlarm(7L, 4 * 60_000L)

        verify(alarmManager).cancel(any<PendingIntent>())
        verify(alarmManager, never()).setExactAndAllowWhileIdle(any<Int>(), any<Long>(), any<PendingIntent>())
    }

    @Test
    fun `rearmRetryAlarm arms past the threshold while the gate is on`() {
        val before = System.currentTimeMillis()

        armer.rearmRetryAlarm(7L, 10 * 60_000L)

        val at = armedAt()
        assertTrue(at in (before + 10 * 60_000L)..(System.currentTimeMillis() + 10 * 60_000L))
    }

    @Test
    fun `rearmRetryAlarm cancels but does not arm while the gate is off`() {
        whenever(prefs.load()).thenReturn(AppSettings(exactWakeEnabled = false))

        armer.rearmRetryAlarm(7L, 10 * 60_000L)

        verify(alarmManager).cancel(any<PendingIntent>())
        verify(alarmManager, never()).setExactAndAllowWhileIdle(any<Int>(), any<Long>(), any<PendingIntent>())
    }

    // The same revoke race on the per-row arm: the throw must not escape the armer.
    @Test
    fun `rearmRetryAlarm degrades to a log when the platform set throws`() {
        whenever(alarmManager.setExactAndAllowWhileIdle(any<Int>(), any<Long>(), any<PendingIntent>()))
            .thenThrow(SecurityException("revoked"))
        Logger.setLevel(LogLevel.DEBUG)
        LogBuffer.clear()

        armer.rearmRetryAlarm(7L, 10 * 60_000L)

        verify(alarmManager).cancel(any<PendingIntent>())
        verify(alarmManager).setExactAndAllowWhileIdle(any<Int>(), any<Long>(), any<PendingIntent>())
        val entry = LogBuffer.entries.last()
        assertEquals("W", entry.level)
        assertEquals("ExactWakeArmer", entry.tag)
        assertTrue(entry.message.startsWith("retry exact alarm arm failed"))
    }

    @Test
    fun `rearmAllRetryAlarms arms only the rows with a future due time`() =
        runTest {
            val now = System.currentTimeMillis()
            whenever(repository.getRetryingSms()).thenReturn(
                listOf(
                    Sms(
                        id = 1,
                        messageBody = "a",
                        phoneNumber = "+1",
                        status = SmsStatus.RETRYING,
                        timeNextSendAttempt = now + 10 * 60_000L,
                    ),
                    Sms(
                        id = 2,
                        messageBody = "b",
                        phoneNumber = "+2",
                        status = SmsStatus.RETRYING,
                        timeNextSendAttempt = now - 60_000L,
                    ),
                    Sms(
                        id = 3,
                        messageBody = "c",
                        phoneNumber = "+3",
                        status = SmsStatus.RETRYING,
                        timeNextSendAttempt = null,
                    ),
                ),
            )

            armer.rearmAllRetryAlarms()

            // One re-arm (the armed row's stale-token cancel), one alarm.
            verify(alarmManager).cancel(any<PendingIntent>())
            val at = armedAt()
            assertTrue(at >= now + 10 * 60_000L)
        }

    @Test
    fun `rearmAllRetryAlarms is a no-op while the gate is off`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(exactWakeEnabled = false))

            armer.rearmAllRetryAlarms()

            verify(repository, never()).getRetryingSms()
            verify(alarmManager, never()).setExactAndAllowWhileIdle(any<Int>(), any<Long>(), any<PendingIntent>())
        }

    @Test
    fun `cancelAll cancels the sweep token and every retrying row token regardless of the gate`() =
        runTest {
            whenever(repository.getRetryingIds()).thenReturn(listOf(4L, 5L))

            armer.cancelAll()

            verify(repository).getRetryingIds()
            // The sweep token plus one per row.
            verify(alarmManager, times(3)).cancel(any<PendingIntent>())

            // Ungated: a revoke must still disarm the tokens armed while enabled.
            whenever(permissionManager.canScheduleExactAlarms()).thenReturn(false)

            armer.cancelAll()

            verify(alarmManager, times(6)).cancel(any<PendingIntent>())
        }
}
