package app.kotowski.sms2email.service

import android.telephony.SubscriptionManager
import app.kotowski.sms2email.R
import app.kotowski.sms2email.testing.TestPhone
import app.kotowski.sms2email.util.sanitizeHeaderValue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EmailBuilderTest {
    @Mock
    private lateinit var mockContext: android.content.Context

    private val template = "[%s] SMS from %s"
    private val templateWithSim = "[%s] SMS from %s%s"
    private val templateNoPrefix = "SMS from %s"
    private val templateNoPrefixWithSim = "SMS from %s%s"

    private val testTime = 1700000000000L
    private val testBody = "Your OTP is 1234"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.getString(R.string.subject_template)).thenReturn(template)
        `when`(mockContext.getString(R.string.subject_template_with_sim)).thenReturn(templateWithSim)
        `when`(mockContext.getString(R.string.subject_template_no_prefix)).thenReturn(templateNoPrefix)
        `when`(mockContext.getString(R.string.subject_template_no_prefix_with_sim)).thenReturn(templateNoPrefixWithSim)
        `when`(mockContext.getString(R.string.subject_template, "myapp", TestPhone.SELF)).thenReturn("[myapp] SMS from ${TestPhone.SELF}")
        `when`(mockContext.getString(R.string.subject_template, "a", TestPhone.SELF)).thenReturn("[a] SMS from ${TestPhone.SELF}")
        `when`(mockContext.getString(R.string.subject_template, "myapp", "John Doe")).thenReturn("[myapp] SMS from John Doe")
        `when`(mockContext.getString(R.string.subject_template, "a", "John Doe")).thenReturn("[a] SMS from John Doe")
        `when`(
            mockContext.getString(R.string.subject_template_with_sim, "myapp", TestPhone.SELF, "on CarrierOne"),
        ).thenReturn("[myapp] SMS from ${TestPhone.SELF} on CarrierOne")
        `when`(
            mockContext.getString(R.string.subject_template_with_sim, "a", TestPhone.SELF, "on CarrierOne"),
        ).thenReturn("[a] SMS from ${TestPhone.SELF} on CarrierOne")
        `when`(
            mockContext.getString(R.string.subject_template_with_sim, "myapp", "John Doe", "on CarrierOne"),
        ).thenReturn("[myapp] SMS from John Doe on CarrierOne")
        `when`(mockContext.getString(R.string.subject_template_no_prefix, TestPhone.SELF)).thenReturn("SMS from ${TestPhone.SELF}")
        `when`(mockContext.getString(R.string.subject_template_no_prefix, "John Doe")).thenReturn("SMS from John Doe")
        `when`(
            mockContext.getString(R.string.subject_template_no_prefix_with_sim, TestPhone.SELF, "on CarrierOne"),
        ).thenReturn("SMS from ${TestPhone.SELF} on CarrierOne")
        `when`(
            mockContext.getString(R.string.subject_template_no_prefix_with_sim, "John Doe", "on CarrierOne"),
        ).thenReturn("SMS from John Doe on CarrierOne")
        `when`(mockContext.getString(R.string.app_name)).thenReturn("SMS to Email")
        `when`(mockContext.getString(R.string.email_subject_sim_suffix, "CarrierOne")).thenReturn("on CarrierOne")
        `when`(mockContext.getString(R.string.email_subject_sim_suffix, "MyCarrier")).thenReturn("on MyCarrier")
    }

    @Test
    fun `format with prefix and no SIM`() {
        assertEquals("[myapp] SMS from ${TestPhone.SELF}", EmailBuilder.format(mockContext, "myapp", TestPhone.SELF, null))
    }

    @Test
    fun `format with prefix and SIM suffix`() {
        assertEquals(
            "[myapp] SMS from ${TestPhone.SELF} on CarrierOne",
            EmailBuilder.format(mockContext, "myapp", TestPhone.SELF, "on CarrierOne"),
        )
    }

    @Test
    fun `format with empty prefix and no SIM`() {
        assertEquals("SMS from ${TestPhone.SELF}", EmailBuilder.format(mockContext, "", TestPhone.SELF, null))
    }

    @Test
    fun `format with empty prefix and SIM suffix`() {
        assertEquals("SMS from ${TestPhone.SELF} on CarrierOne", EmailBuilder.format(mockContext, "", TestPhone.SELF, "on CarrierOne"))
    }

    @Test
    fun `format with whitespace prefix treated as empty`() {
        assertEquals("SMS from John Doe", EmailBuilder.format(mockContext, "   ", "John Doe", null))
    }

    @Test
    fun `json email subject is always app name`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "myapp",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = "deadbeef",
            )
        assertEquals("SMS to Email", parts.subject)
    }

    @Test
    fun `json email subject with empty prefix is also app name`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertEquals("SMS to Email", parts.subject)
    }

    @Test
    fun `json body is the full contract with every field present`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = "John Doe",
                simId = 1,
                simName = "CarrierOne",
                subjectPrefix = "myapp",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = "deadbeef",
            )
        val json = JSONObject(parts.body)
        // Order is unspecified (org.json iterates a HashMap); the contract is the key set.
        assertEquals(
            setOf(
                "version",
                "message_id",
                "time_received",
                "source",
                "sim_id",
                "sim_name",
                "from_phone",
                "from_contact",
                "message_body",
            ),
            json.keys().asSequence().toSet(),
        )
        assertEquals(1, json.getInt("version"))
        assertEquals("deadbeef", json.getString("message_id"))
        assertEquals("2023-11-14T22:13:20Z", json.getString("time_received"))
        assertEquals("myapp", json.getString("source"))
        assertEquals(1, json.getInt("sim_id"))
        assertEquals("CarrierOne", json.getString("sim_name"))
        assertEquals(TestPhone.SELF, json.getString("from_phone"))
        assertEquals("John Doe", json.getString("from_contact"))
        assertEquals(testBody, json.getString("message_body"))
        assertTrue(parts.json)
    }

    @Test
    fun `plain parts are marked non-json and json parts are marked json`() {
        `when`(mockContext.resources).thenReturn(RuntimeEnvironment.getApplication().resources)
        val plain =
            EmailBuilder.buildEmail(
                context = mockContext,
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
        val json =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertFalse(plain.json)
        assertTrue(json.json)
    }

    @Test
    fun `json email ignores the include time setting`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = true,
                messageId = null,
            )
        // The time rides the fixed time_received field only, never a plain-text line.
        val json = JSONObject(parts.body)
        assertEquals("2023-11-14T22:13:20Z", json.getString("time_received"))
        assertEquals(testBody, json.getString("message_body"))
        assertTrue(parts.json)
    }

    @Test
    fun `json body carries null message_id when the row has none`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains("\"message_id\": null"))
    }

    @Test
    fun `json body carries the source for a set prefix`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "myapp",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains("\"source\": \"myapp\""))
    }

    @Test
    fun `json body carries null source when the prefix is blank`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "   ",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains("\"source\": null"))
    }

    @Test
    fun `json body carries null sim_id for a row without a subscription`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains("\"sim_id\": null"))
    }

    @Test
    fun `json body carries null sim_name when it is unresolved`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = 1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains("\"sim_name\": null"))
        assertTrue(parts.body.contains("\"sim_id\": 1"))
    }

    @Test
    fun `json body carries null from_contact when there is no contact name`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains("\"from_contact\": null"))
    }

    @Test
    fun `json body time_received is the RFC 3339 UTC instant`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains("\"time_received\": \"2023-11-14T22:13:20Z\""))
    }

    @Test
    fun `json body preserves special characters in message`() {
        val specialBody = "Line1\nLine2\t\"quotes\""
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = specialBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains("\\n"))
        assertTrue(parts.body.contains("\\t"))
        assertTrue(parts.body.contains("\\\"quotes\\\""))
    }

    @Test
    fun `json body escapes backspace form feed and null control chars`() {
        val controlBody = "a\u0008b\u000cc\u0000d"
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = controlBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        assertTrue(parts.body.contains("\\b"))
        assertTrue(parts.body.contains("\\f"))
        assertTrue(parts.body.contains("\\u0000"))
    }

    @Test
    fun `json body is valid JSON when message contains all C0 control characters`() {
        val controlBody = (0..0x1F).joinToString("") { it.toChar().toString() }
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = controlBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        val rawControls = parts.body.filter { it.code in 0x00..0x1F && it != '\n' }
        assertTrue(rawControls.isEmpty(), "raw C0 control characters in JSON body: ${rawControls.map { it.code }}")
        val json = JSONObject(parts.body)
        assertEquals(controlBody, json.getString("message_body"))
    }

    @Test
    fun `json body is valid JSON structure`() {
        val parts =
            EmailBuilder.buildEmail(
                context = mockContext,
                phoneNumber = TestPhone.SELF,
                contactName = null,
                simId = -1,
                simName = null,
                subjectPrefix = "",
                timeReceived = testTime,
                messageBody = testBody,
                useJsonFormat = true,
                includeTimeInBody = false,
                messageId = null,
            )
        val body = parts.body.trim()
        assertTrue(body.startsWith("{"))
        assertTrue(body.endsWith("}"))
    }

    @Test
    fun `sanitized malicious subject does not inject a header`() {
        val serialized = serializeSubject(sanitizeHeaderValue("123\r\nBcc: evil@example.com"))
        assertFalse(serialized.lines().any { it.trimStart().startsWith("Bcc:") }, "injected header found:\n$serialized")
    }

    @Test
    fun `raw malicious subject still injects a header as control`() {
        val serialized = serializeSubject("123\r\nBcc: evil@example.com")
        assertTrue(serialized.lines().any { it.trimStart().startsWith("Bcc:") }, "expected injected header:\n$serialized")
    }

    private fun serializeSubject(subject: String): String {
        val session = Session.getInstance(Properties())
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress("from@example.com"))
        msg.setRecipient(Message.RecipientType.TO, InternetAddress("to@example.com"))
        msg.setSubject(subject)
        msg.setText("body", "utf-8")
        msg.saveChanges()
        val out = ByteArrayOutputStream()
        msg.writeTo(out)
        return out.toString("UTF-8")
    }
}
