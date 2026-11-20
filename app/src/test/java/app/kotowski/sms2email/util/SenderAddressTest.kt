package app.kotowski.sms2email.util

import app.kotowski.sms2email.testing.TestPhone
import org.junit.Test
import kotlin.test.assertEquals

class SenderAddressTest {
    @Test
    fun `null sender maps to the fallback`() {
        assertEquals("Unknown sender", normalizeSenderAddress(null, "Unknown sender"))
    }

    @Test
    fun `empty sender maps to the fallback`() {
        assertEquals("Unknown sender", normalizeSenderAddress("", "Unknown sender"))
    }

    @Test
    fun `whitespace-only sender maps to the fallback`() {
        assertEquals("Unknown sender", normalizeSenderAddress("   ", "Unknown sender"))
    }

    @Test
    fun `a non-blank sender is preserved untouched`() {
        assertEquals(TestPhone.SELF, normalizeSenderAddress(TestPhone.SELF, "Unknown sender"))
        assertEquals("Test Bank", normalizeSenderAddress("Test Bank", "Unknown sender"))
        assertEquals("Оплата", normalizeSenderAddress("Оплата", "Unknown sender"))
    }
}
