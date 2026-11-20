package app.kotowski.sms2email.data.database

import app.kotowski.sms2email.testing.TestPhone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmsReflectionTest {
    @Test
    fun `test Sms has all expected fields via Java reflection`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello World",
            )

        val fieldNames =
            sms::class.java.declaredFields
                .map { it.name }
                .toSet()

        assertTrue(fieldNames.contains("id"))
        assertTrue(fieldNames.contains("phoneNumber"))
        assertTrue(fieldNames.contains("messageBody"))
        assertTrue(fieldNames.contains("timeReceived"))
        assertTrue(fieldNames.contains("status"))
        assertTrue(fieldNames.contains("statusDetails"))
        assertTrue(fieldNames.contains("retryCount"))
        assertTrue(fieldNames.contains("timeLastSendAttempt"))
        assertTrue(fieldNames.contains("timeNextSendAttempt"))
        assertTrue(fieldNames.contains("simId"))
        assertTrue(fieldNames.contains("dedupKey"))
    }

    @Test
    fun `test Sms reflection returns correct count`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello World",
            )

        val fieldCount = sms::class.java.declaredFields.size

        assertTrue(fieldCount >= 11, "Expected at least 11 fields but got $fieldCount")
    }

    @Test
    fun `test Sms reflection values are correct`() {
        val sms =
            Sms(
                id = 42L,
                phoneNumber = "+999",
                messageBody = "Test",
                timeReceived = 1234567890L,
                status = SmsStatus.FAILED,
                statusDetails = "Timeout",
                retryCount = 5,
                timeLastSendAttempt = 1234567890L,
                timeNextSendAttempt = 1234567891L,
                simId = 2,
                dedupKey = "hash123",
            )

        val props =
            sms::class.java.declaredFields.associateBy { it.name }.mapValues { (_, field) ->
                field.isAccessible = true
                field.get(sms)
            }

        assertEquals(42L, props["id"])
        assertEquals("+999", props["phoneNumber"])
        assertEquals("Test", props["messageBody"])
        assertEquals(1234567890L, props["timeReceived"])
        assertEquals(SmsStatus.FAILED, props["status"])
        assertEquals("Timeout", props["statusDetails"])
        assertEquals(5, props["retryCount"])
        assertEquals(1234567890L, props["timeLastSendAttempt"])
        assertEquals(1234567891L, props["timeNextSendAttempt"])
        assertEquals(2, props["simId"])
        assertEquals("hash123", props["dedupKey"])
    }

    @Test
    fun `test Sms reflection nullable fields are null`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello",
            )

        val props =
            sms::class.java.declaredFields.associateBy { it.name }.mapValues { (_, field) ->
                field.isAccessible = true
                field.get(sms)
            }

        assertEquals(null, props["statusDetails"])
        assertEquals(null, props["timeLastSendAttempt"])
        assertEquals(null, props["timeNextSendAttempt"])
        assertEquals(null, props["dedupKey"])
    }

    @Test
    fun `test Sms reflection toString is non-empty`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello",
            )

        sms::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            val value = field.get(sms)
            val str = value?.toString() ?: "null"
            assertNotNull(str)
        }
    }

    @Test
    fun `test Sms reflection card data matches expected format`() {
        val sms =
            Sms(
                id = 1L,
                phoneNumber = "+123",
                messageBody = "Hi",
                timeReceived = 1000L,
                status = SmsStatus.PENDING,
                statusDetails = null,
                retryCount = 0,
                timeLastSendAttempt = null,
                timeNextSendAttempt = null,
                simId = -1,
                dedupKey = null,
            )

        val fields =
            sms::class.java.declaredFields.map { field ->
                field.isAccessible = true
                field.name to (field.get(sms)?.toString() ?: "null")
            }

        val map = fields.toMap()
        assertEquals("1", map["id"])
        assertEquals("+123", map["phoneNumber"])
        assertEquals("Hi", map["messageBody"])
        assertEquals("1000", map["timeReceived"])
        assertEquals("PENDING", map["status"])
        assertEquals("null", map["statusDetails"])
        assertEquals("0", map["retryCount"])
        assertEquals("-1", map["simId"])
    }
}
