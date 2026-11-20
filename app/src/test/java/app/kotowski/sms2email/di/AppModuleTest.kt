package app.kotowski.sms2email.di

import app.kotowski.sms2email.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppModuleTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the provided open-app intent targets the main activity`() {
        val intent = AppModule.provideOpenAppIntentProvider().create(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `provideBackgroundScope returns a CoroutineScope`() {
        val scope = AppModule.provideBackgroundScope()
        assertNotNull(scope)
    }

    @Test
    fun `provideIngestRetryDelayMs returns the configured value`() {
        val delay = AppModule.provideIngestRetryDelayMs()
        assertTrue(delay > 0)
    }

    @Test
    fun `provideDatabase returns a non-null database`() {
        val db = AppModule.provideDatabase(context)
        assertNotNull(db)
    }

    @Test
    fun `provideSmsDao returns a non-null dao from the database`() {
        val db = AppModule.provideDatabase(context)
        val dao = AppModule.provideSmsDao(db)
        assertNotNull(dao)
    }

    @Test
    fun `provideSmtpNetworkCheck returns a non-null network check`() {
        val check = AppModule.provideSmtpNetworkCheck()
        assertNotNull(check)
    }
}
