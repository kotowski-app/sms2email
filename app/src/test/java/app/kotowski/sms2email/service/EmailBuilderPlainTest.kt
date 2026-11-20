package app.kotowski.sms2email.service

import android.content.Context
import app.kotowski.sms2email.R
import app.kotowski.sms2email.testing.TestPhone
import app.kotowski.sms2email.util.DateUtil
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`doReturn`
import org.mockito.Mockito.spy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EmailBuilderPlainTest {
    private val testTime = 1700000000000L
    private val testBody = "Your OTP is 1234"
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        ctx = spy(app)

        `doReturn`("[myapp] SMS from ${TestPhone.SELF}").`when`(ctx).getString(R.string.subject_template, "myapp", TestPhone.SELF)
        `doReturn`("[myapp] SMS from John Doe").`when`(ctx).getString(R.string.subject_template, "myapp", "John Doe")
        `doReturn`(
            "[myapp] SMS from ${TestPhone.SELF} on CarrierOne",
        ).`when`(ctx).getString(R.string.subject_template_with_sim, "myapp", TestPhone.SELF, "on CarrierOne")
        `doReturn`(
            "[myapp] SMS from John Doe on CarrierOne",
        ).`when`(ctx).getString(R.string.subject_template_with_sim, "myapp", "John Doe", "on CarrierOne")
        `doReturn`("SMS from ${TestPhone.SELF}").`when`(ctx).getString(R.string.subject_template_no_prefix, TestPhone.SELF)
        `doReturn`(
            "SMS from ${TestPhone.SELF} on CarrierOne",
        ).`when`(ctx).getString(R.string.subject_template_no_prefix_with_sim, TestPhone.SELF, "on CarrierOne")
        `doReturn`("on CarrierOne").`when`(ctx).getString(R.string.email_subject_sim_suffix, "CarrierOne")
    }

    @Test
    fun `plain email subject uses localized SIM suffix`() {
        val parts =
            EmailBuilder.buildEmail(
                context = ctx,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = 1,
                simName = "CarrierOne",
                subjectPrefix = "myapp",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = false,
                includeTimeInBody = false,
                messageId = null,
            )
        assertEquals("[myapp] SMS from ${TestPhone.SELF} on CarrierOne", parts.subject)
    }

    @Test
    fun `plain email subject uses the SIM suffix on a single SIM device when the sim name is supplied`() {
        val parts =
            EmailBuilder.buildEmail(
                context = ctx,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = 1,
                simName = "CarrierOne",
                subjectPrefix = "myapp",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = false,
                includeTimeInBody = false,
                messageId = null,
            )
        assertEquals("[myapp] SMS from ${TestPhone.SELF} on CarrierOne", parts.subject)
    }

    @Test
    fun `plain email subject omits the SIM suffix when no sim name is supplied`() {
        val parts =
            EmailBuilder.buildEmail(
                context = ctx,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = 1,
                simName = null,
                subjectPrefix = "myapp",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = false,
                includeTimeInBody = false,
                messageId = null,
            )
        assertEquals("[myapp] SMS from ${TestPhone.SELF}", parts.subject)
    }

    @Test
    fun `plain email subject no prefix with SIM suffix`() {
        val parts =
            EmailBuilder.buildEmail(
                context = ctx,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = 1,
                simName = "CarrierOne",
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = false,
                includeTimeInBody = false,
                messageId = null,
            )
        assertEquals("SMS from ${TestPhone.SELF} on CarrierOne", parts.subject)
    }

    @Test
    fun `plain email subject with contact name and SIM suffix`() {
        val parts =
            EmailBuilder.buildEmail(
                context = ctx,
                phoneNumber = TestPhone.SELF,
                contactName = "John Doe",
                simId = 1,
                simName = "CarrierOne",
                subjectPrefix = "myapp",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = false,
                includeTimeInBody = false,
                messageId = null,
            )
        assertEquals("[myapp] SMS from John Doe on CarrierOne", parts.subject)
    }

    @Test
    fun `plain email body contains message content`() {
        val parts =
            EmailBuilder.buildEmail(
                context = ctx,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = false,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains(testBody))
    }

    @Test
    fun `plain email body omits the time line while the setting is off`() {
        val parts =
            EmailBuilder.buildEmail(
                context = ctx,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = false,
                includeTimeInBody = false,
                messageId = null,
            )
        assertEquals(testBody.trim(), parts.body.trim())
        assertFalse(parts.body.contains(DateUtil.formatDateTime(ctx, testTime)))
    }

    @Test
    fun `plain email body leads with the time line while the setting is on`() {
        val parts =
            EmailBuilder.buildEmail(
                context = ctx,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = false,
                includeTimeInBody = true,
                messageId = null,
            )
        assertTrue(parts.body.startsWith(DateUtil.formatDateTime(ctx, testTime)))
        assertTrue(parts.body.contains(testBody))
    }
}
