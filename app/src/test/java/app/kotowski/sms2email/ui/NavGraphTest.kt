package app.kotowski.sms2email.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavGraphTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `Screen routes are unique`() {
        val routes =
            listOf(
                Screen.ScamWarning.route,
                Screen.Onboarding.route,
                Screen.Dashboard.route,
                Screen.History.route,
                Screen.Queue.route,
                Screen.Settings.route,
                Screen.EmailConfigEdit.route,
                Screen.FilterConfigEdit.route,
                Screen.LogViewer.route,
                Screen.SmsDetail.route,
            )
        assertEquals(routes.size, routes.toSet().size, "all Screen routes must be unique")
    }

    @Test
    fun `Screen route constants match expected values`() {
        assertEquals("scam_warning", Screen.ScamWarning.route)
        assertEquals("onboarding", Screen.Onboarding.route)
        assertEquals("dashboard", Screen.Dashboard.route)
        assertEquals("history", Screen.History.route)
        assertEquals("queue", Screen.Queue.route)
        assertEquals("settings", Screen.Settings.route)
        assertEquals("email_config_edit", Screen.EmailConfigEdit.route)
        assertEquals("filter_config_edit", Screen.FilterConfigEdit.route)
        assertEquals("log_viewer", Screen.LogViewer.route)
        assertTrue(Screen.SmsDetail.route.startsWith("sms_detail/"))
    }

    @Test
    fun `SmsDetail createRoute produces valid route`() {
        val route = Screen.SmsDetail.createRoute(42L)
        assertEquals("sms_detail/42", route)
    }

    @Test
    fun `findActivity returns the activity when context is an activity`() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertNotNull(activity.findActivity())
    }

    @Test
    fun `findActivity returns null for application context`() {
        assertNull(context.findActivity())
    }

    @Test
    fun `findActivity traverses context wrappers to find the activity`() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val wrapper = object : ContextWrapper(activity) {}
        assertNotNull(wrapper.findActivity())
    }

    @Test
    fun `findActivity traverses nested context wrappers`() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val inner = object : ContextWrapper(activity) {}
        val outer = object : ContextWrapper(inner) {}
        assertNotNull(outer.findActivity())
    }

    @Test
    fun `findActivity returns null when wrapper chain does not contain an activity`() {
        val wrapper = object : ContextWrapper(context) {}
        assertNull(wrapper.findActivity())
    }
}
