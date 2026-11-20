package app.kotowski.sms2email.system

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Robolectric with the REAL application context: the channel/PendingIntent/
// NotificationCompat path runs for real, and the assertions read the shadow
// NotificationManager's posted set. The permission state is per-test.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ForwardingNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun shadowManager() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    private fun grantNotifications() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `a denied POST_NOTIFICATIONS is a logged skip, not a posted notification`() {
        // The Robolectric default: the runtime permission is not granted.
        ForwardingNotification.show(context, Intent())
        assertTrue(shadowManager().getAllNotifications().isEmpty())
    }

    @Test
    fun `a granted POST_NOTIFICATIONS posts the ongoing notification`() {
        grantNotifications()
        ForwardingNotification.show(context, Intent())
        val posted = shadowManager().getAllNotifications()
        assertEquals(1, posted.size)
        val id = shadowManager().getActiveNotifications().first().id
        assertNotNull(shadowManager().getNotification(id))
        assertTrue(posted[0].flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `dismiss removes the posted notification`() {
        grantNotifications()
        ForwardingNotification.show(context, Intent())
        assertEquals(1, shadowManager().getAllNotifications().size)
        ForwardingNotification.dismiss(context)
        assertTrue(shadowManager().getAllNotifications().isEmpty())
    }

    // Below 33 the manifest permission is granted at install, so show() has no gate:
    // the same call posts without any runtime-permission grant.
    @Test
    @Config(sdk = [28])
    fun `below 33 the notification posts without a runtime permission grant`() {
        ForwardingNotification.show(context, Intent())
        assertEquals(1, shadowManager().getAllNotifications().size)
    }
}
