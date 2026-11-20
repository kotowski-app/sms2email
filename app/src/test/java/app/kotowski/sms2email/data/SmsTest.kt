package app.kotowski.sms2email.data.database

import app.kotowski.sms2email.testing.TestPhone
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsTest {
    @Test
    fun `test Sms creation with defaults`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello World",
            )

        assertEquals(0, sms.id)
        assertEquals(TestPhone.SELF, sms.phoneNumber)
        assertEquals("Hello World", sms.messageBody)
        assertEquals(SmsStatus.PENDING, sms.status)
        assertEquals(null, sms.statusDetails)
        assertEquals(0, sms.retryCount)
        assertEquals(-1, sms.simId)
        assertEquals(null, sms.timeFirstSendAttempt)
        assertEquals(null, sms.timeLastSendAttempt)
        assertEquals(null, sms.timeNextSendAttempt)
        assertTrue(sms.timeReceived > 0)
    }

    @Test
    fun `test Sms with all fields`() {
        val sms =
            Sms(
                id = 1L,
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
                timeReceived = 1609459200000L,
                status = SmsStatus.FAILED,
                statusDetails = "Connection timeout",
                retryCount = 3,
            )

        assertEquals(1L, sms.id)
        assertEquals(SmsStatus.FAILED, sms.status)
        assertEquals("Connection timeout", sms.statusDetails)
        assertEquals(3, sms.retryCount)
    }

    @Test
    fun `test Sms with timeLastSendAttempt`() {
        val sentAt = 1609459200000L
        val sms =
            Sms(
                id = 1L,
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
                status = SmsStatus.SENT,
                timeLastSendAttempt = sentAt,
            )

        assertEquals(SmsStatus.SENT, sms.status)
        assertEquals(sentAt, sms.timeLastSendAttempt)
    }

    @Test
    fun `test Sms timeLastSendAttempt defaults to null`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
            )

        assertEquals(null, sms.timeLastSendAttempt)
    }

    @Test
    fun `test Sms copy preserves timeLastSendAttempt`() {
        val sentAt = 1609459200000L
        val original =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello",
                timeLastSendAttempt = sentAt,
            )

        val updated = original.copy(status = SmsStatus.SENT)

        assertEquals(sentAt, updated.timeLastSendAttempt)
        assertEquals(SmsStatus.SENT, updated.status)
    }

    @Test
    fun `test Sms copy`() {
        val original =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello",
                status = SmsStatus.PENDING,
            )

        val updated =
            original.copy(
                status = SmsStatus.SENT,
                statusDetails = null,
            )

        assertEquals(SmsStatus.SENT, updated.status)
        assertEquals(original.phoneNumber, updated.phoneNumber)
        assertEquals(original.messageBody, updated.messageBody)
    }

    @Test
    fun `test Sms with simId`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
                simId = 0,
            )

        assertEquals(0, sms.simId)
    }

    @Test
    fun `test Sms imported SMS has SENT status`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Old message",
                status = SmsStatus.SENT,
                simId = 1,
            )

        assertEquals(SmsStatus.SENT, sms.status)
        assertEquals(1, sms.simId)
    }

    @Test
    fun `test SmsStatus enum values`() {
        val values = SmsStatus.values()
        assertEquals(7, values.size)
        assertTrue(values.contains(SmsStatus.IMPORTED))
        assertTrue(values.contains(SmsStatus.PENDING))
        assertTrue(values.contains(SmsStatus.SENDING))
        assertTrue(values.contains(SmsStatus.SENT))
        assertTrue(values.contains(SmsStatus.RETRYING))
        assertTrue(values.contains(SmsStatus.FAILED))
        assertTrue(values.contains(SmsStatus.IGNORED))
    }

    @Test
    fun `test MANUAL_FORWARDABLE includes parked IGNORED and SENT but never in-flight SENDING`() {
        assertTrue(SmsStatus.MANUAL_FORWARDABLE.contains(SmsStatus.IMPORTED))
        assertTrue(SmsStatus.MANUAL_FORWARDABLE.contains(SmsStatus.PENDING))
        assertTrue(SmsStatus.MANUAL_FORWARDABLE.contains(SmsStatus.RETRYING))
        assertTrue(SmsStatus.MANUAL_FORWARDABLE.contains(SmsStatus.FAILED))
        assertTrue(SmsStatus.MANUAL_FORWARDABLE.contains(SmsStatus.SENT))
        // rows parked when forwarding was turned off (or that arrived while off) must be
        // re-sendable by hand from the UI
        assertTrue(SmsStatus.MANUAL_FORWARDABLE.contains(SmsStatus.IGNORED))
        // a send already in flight must never be re-triggered from the UI
        assertFalse(SmsStatus.MANUAL_FORWARDABLE.contains(SmsStatus.SENDING))
    }

    @Test
    fun `test IN_FLIGHT is exactly the queue partition, never terminal`() {
        assertEquals(
            listOf(SmsStatus.PENDING, SmsStatus.SENDING, SmsStatus.RETRYING),
            SmsStatus.IN_FLIGHT,
        )
        assertTrue(SmsStatus.IN_FLIGHT.none { it in SmsStatus.TERMINAL })
    }

    @Test
    fun `test IN_FLIGHT plus TERMINAL partition every status exactly once`() {
        val union = (SmsStatus.IN_FLIGHT + SmsStatus.TERMINAL).toSet()
        assertEquals(SmsStatus.entries.toSet(), union)
        assertEquals(SmsStatus.entries.size, (SmsStatus.IN_FLIGHT + SmsStatus.TERMINAL).size)
    }

    @Test
    fun `test AUTO_SENDABLE is in-flight minus in-progress SENDING`() {
        assertEquals(listOf(SmsStatus.PENDING, SmsStatus.RETRYING), SmsStatus.AUTO_SENDABLE)
        assertTrue(SmsStatus.AUTO_SENDABLE.all { it in SmsStatus.IN_FLIGHT })
        assertFalse(SmsStatus.AUTO_SENDABLE.contains(SmsStatus.SENDING))
    }

    @Test
    fun `test SmsListScope statuses are the queue and history partitions`() {
        assertEquals(SmsStatus.IN_FLIGHT, SmsListScope.QUEUE.statuses)
        assertEquals(SmsStatus.TERMINAL, SmsListScope.HISTORY.statuses)
        // the two scopes must stay disjoint and cover every status (the list queries
        // bind these names, a row is in exactly one screen)
        assertTrue((SmsListScope.QUEUE.statuses + SmsListScope.HISTORY.statuses).toSet() == SmsStatus.entries.toSet())
        assertTrue(SmsListScope.QUEUE.statuses.none { it in SmsListScope.HISTORY.statuses })
    }

    @Test
    fun `test Sms with dedupKey`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
                simId = 0,
                dedupKey = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
            )

        assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", sms.dedupKey)
    }

    @Test
    fun `test Sms dedupKey defaults to null`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
            )

        assertEquals(null, sms.dedupKey)
    }

    @Test
    fun `test Sms copy preserves dedupKey`() {
        val original =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello",
                dedupKey = "abc123def456",
            )

        val updated = original.copy(status = SmsStatus.SENT)

        assertEquals("abc123def456", updated.dedupKey)
        assertEquals(SmsStatus.SENT, updated.status)
    }

    @Test
    fun `test Sms with timeNextSendAttempt`() {
        val retryTime = 1609459200000L
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
                status = SmsStatus.RETRYING,
                timeNextSendAttempt = retryTime,
            )

        assertEquals(SmsStatus.RETRYING, sms.status)
        assertEquals(retryTime, sms.timeNextSendAttempt)
    }

    @Test
    fun `test Sms timeNextSendAttempt defaults to null`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
            )

        assertEquals(null, sms.timeNextSendAttempt)
    }

    @Test
    fun `test Sms copy preserves timeNextSendAttempt`() {
        val retryTime = 1609459200000L
        val original =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello",
                timeNextSendAttempt = retryTime,
            )

        val updated = original.copy(status = SmsStatus.SENDING)

        assertEquals(retryTime, updated.timeNextSendAttempt)
        assertEquals(SmsStatus.SENDING, updated.status)
    }

    @Test
    fun `test Sms copy can clear timeNextSendAttempt`() {
        val retryTime = 1609459200000L
        val original =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello",
                timeNextSendAttempt = retryTime,
            )

        val updated = original.copy(timeNextSendAttempt = null)

        assertEquals(null, updated.timeNextSendAttempt)
    }

    @Test
    fun `test Sms with timeFirstSendAttempt`() {
        val firstAttempt = 1609459200000L
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
                status = SmsStatus.SENDING,
                timeFirstSendAttempt = firstAttempt,
            )

        assertEquals(SmsStatus.SENDING, sms.status)
        assertEquals(firstAttempt, sms.timeFirstSendAttempt)
    }

    @Test
    fun `test Sms timeFirstSendAttempt defaults to null`() {
        val sms =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Test message",
            )

        assertEquals(null, sms.timeFirstSendAttempt)
    }

    @Test
    fun `test Sms copy preserves timeFirstSendAttempt`() {
        val firstAttempt = 1609459200000L
        val original =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello",
                timeFirstSendAttempt = firstAttempt,
            )

        val updated = original.copy(status = SmsStatus.SENT)

        assertEquals(firstAttempt, updated.timeFirstSendAttempt)
        assertEquals(SmsStatus.SENT, updated.status)
    }

    @Test
    fun `test Sms searchPhone is unicode-lowercased and reduced to letters and digits at construction`() {
        val cyrillic =
            Sms(
                phoneNumber = "Оплата",
                messageBody = "Hello",
            )
        val mixedCase =
            Sms(
                phoneNumber = "Test Bank",
                messageBody = "Hello",
            )

        assertEquals("оплата", cyrillic.searchPhone)
        // sender search is separator-insensitive: the facet keeps letters/digits only
        assertEquals("testbank", mixedCase.searchPhone)
        // the raw address stays untouched for display and email
        assertEquals("Оплата", cyrillic.phoneNumber)
        assertEquals("Test Bank", mixedCase.phoneNumber)
    }

    @Test
    fun `test foldSearchBody folds uppercase I to i under the Turkish default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("i", foldSearchBody("I"))
            assertEquals("istiklal", foldSearchBody("Istiklal"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `test foldSearchPhone folds uppercase I to i under the Turkish default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("i", foldSearchPhone("I"))
            assertEquals("istiklal", foldSearchPhone("Istiklal"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `test Sms searchBody is folded with the root locale at construction`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            val sms =
                Sms(
                    phoneNumber = TestPhone.SELF,
                    messageBody = "Istiklal",
                )
            assertEquals("istiklal", sms.searchBody)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `test Sms copy can reset timeFirstSendAttempt`() {
        val originalFirstAttempt = 1609459200000L
        val original =
            Sms(
                phoneNumber = TestPhone.SELF,
                messageBody = "Hello",
                timeFirstSendAttempt = originalFirstAttempt,
                retryCount = 5,
            )

        val newFirstAttempt = 1709459200000L
        val updated =
            original.copy(
                status = SmsStatus.PENDING,
                retryCount = 0,
                timeFirstSendAttempt = newFirstAttempt,
            )

        assertEquals(newFirstAttempt, updated.timeFirstSendAttempt)
        assertEquals(0, updated.retryCount)
        assertEquals(SmsStatus.PENDING, updated.status)
    }
}
