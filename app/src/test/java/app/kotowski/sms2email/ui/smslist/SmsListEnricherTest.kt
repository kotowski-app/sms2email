package app.kotowski.sms2email.ui.smslist

import android.content.Context
import app.kotowski.sms2email.data.database.SmsStatus
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.SimNameResolver
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsListEnricherTest {
    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var prefs: EncryptedPrefs

    @Mock
    private lateinit var contactResolver: ContactResolver

    @Mock
    private lateinit var simNameResolver: SimNameResolver

    private lateinit var enricher: SmsListEnricher

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(context.getString(any<Int>())).thenReturn("Unknown SIM")
        whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true))
        enricher = SmsListEnricher(context, prefs, contactResolver, simNameResolver)
    }

    private fun sms(
        id: Long,
        number: String,
        simId: Int,
    ): SmsUiModel =
        SmsUiModel(
            id = id,
            phoneNumber = number,
            messageBody = "m",
            simId = simId,
            status = SmsStatus.PENDING,
            statusDetails = null,
            timeReceived = 1_700_000_000_000L,
            timeLastSendAttempt = null,
            timeNextSendAttempt = null,
            retryCount = 0,
        )

    @Test
    fun `buildSimNames returns empty when the display is off`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        assertTrue(enricher.buildSimNames(listOf(sms(1, "+1", 1))).isEmpty())
    }

    @Test
    fun `buildSimNames resolves names for the unique slots only`() {
        whenever(simNameResolver.getSimDisplayNameSync(1)).thenReturn("SIM 1")
        whenever(simNameResolver.getSimDisplayNameSync(2)).thenReturn("SIM 2")
        val names = enricher.buildSimNames(listOf(sms(1, "+1", 1), sms(2, "+2", 2), sms(3, "+3", 1)))
        assertEquals(mapOf(1 to "SIM 1", 2 to "SIM 2"), names)
        verify(simNameResolver, times(1)).getSimDisplayNameSync(1)
        verify(simNameResolver, times(1)).getSimDisplayNameSync(2)
    }

    @Test
    fun `buildSimNames resolves sub id 0 and ignores only -1`() {
        whenever(simNameResolver.getSimDisplayNameSync(1)).thenReturn("SIM 1")
        whenever(simNameResolver.getSimDisplayNameSync(0)).thenReturn("SIM 0")
        val names = enricher.buildSimNames(listOf(sms(1, "+1", 1), sms(2, "+2", 0), sms(3, "+3", -1)))
        assertEquals(mapOf(1 to "SIM 1", 0 to "SIM 0"), names)
    }

    @Test
    fun `buildSimNames falls back to the unknown sim label on resolver error`() {
        whenever(simNameResolver.getSimDisplayNameSync(3)).thenThrow(RuntimeException("no sim"))
        val names = enricher.buildSimNames(listOf(sms(1, "+1", 3)))
        assertEquals(mapOf(3 to "Unknown SIM"), names)
    }

    @Test
    fun `buildSimNames re-resolves names on every call`() {
        whenever(simNameResolver.getSimDisplayNameSync(1)).thenReturn("SIM 1").thenReturn("SIM 1b")
        val first = enricher.buildSimNames(listOf(sms(1, "+1", 1)))
        val second = enricher.buildSimNames(listOf(sms(2, "+2", 1)))
        assertEquals("SIM 1", first[1])
        assertEquals("SIM 1b", second[1])
        verify(simNameResolver, times(2)).getSimDisplayNameSync(1)
    }

    @Test
    fun `buildSimNames re-resolves when the slot set changes`() {
        whenever(simNameResolver.getSimDisplayNameSync(1)).thenReturn("SIM 1")
        whenever(simNameResolver.getSimDisplayNameSync(2)).thenReturn("SIM 2")
        enricher.buildSimNames(listOf(sms(1, "+1", 1)))
        val names = enricher.buildSimNames(listOf(sms(2, "+2", 2)))
        assertEquals(mapOf(2 to "SIM 2"), names)
    }

    @Test
    fun `buildSimSlots returns empty when the display is off`() {
        whenever(prefs.load()).thenReturn(AppSettings())
        assertTrue(enricher.buildSimSlots(listOf(sms(1, "+1", 1))).isEmpty())
    }

    @Test
    fun `buildSimSlots maps the unique sub ids to their resolved tray`() {
        whenever(simNameResolver.getSlotFor(1)).thenReturn(2)
        whenever(simNameResolver.getSlotFor(2)).thenReturn(1)
        val slots = enricher.buildSimSlots(listOf(sms(1, "+1", 1), sms(2, "+2", 2), sms(3, "+3", 1)))
        assertEquals(mapOf(1 to 2, 2 to 1), slots)
        verify(simNameResolver, times(1)).getSlotFor(1)
        verify(simNameResolver, times(1)).getSlotFor(2)
    }

    @Test
    fun `buildSimSlots drops a missing sim and sub id -1`() {
        whenever(simNameResolver.getSlotFor(1)).thenReturn(1)
        whenever(simNameResolver.getSlotFor(3)).thenReturn(null)
        val slots = enricher.buildSimSlots(listOf(sms(1, "+1", 1), sms(2, "+2", 3), sms(3, "+3", -1)))
        assertEquals(mapOf(1 to 1), slots)
    }

    @Test
    fun `buildSimSlots drops slot 0 (a sim without a tray slot)`() {
        whenever(simNameResolver.getSlotFor(1)).thenReturn(0)
        assertTrue(enricher.buildSimSlots(listOf(sms(1, "+1", 1))).isEmpty())
    }

    @Test
    fun `buildSimSlots resolves sub id 0`() {
        whenever(simNameResolver.getSlotFor(0)).thenReturn(1)
        assertEquals(mapOf(0 to 1), enricher.buildSimSlots(listOf(sms(1, "+1", 0))))
    }

    @Test
    fun `buildSimSlots re-resolves on every call`() {
        whenever(simNameResolver.getSlotFor(1)).thenReturn(1).thenReturn(2)
        val first = enricher.buildSimSlots(listOf(sms(1, "+1", 1)))
        val second = enricher.buildSimSlots(listOf(sms(2, "+2", 1)))
        assertEquals(1, first[1])
        assertEquals(2, second[1])
    }

    @Test
    fun `resolveAllContacts returns empty when contact resolution is off`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true, useContactResolution = false))
            assertTrue(enricher.resolveAllContacts(listOf(sms(1, "+1", 0))).isEmpty())
        }

    @Test
    fun `resolveAllContacts passes the unique numbers to the resolver once`() =
        runTest {
            whenever(contactResolver.resolveAll(any<Collection<String>>()))
                .thenReturn(mapOf("+1" to "John Doe"))
            val names = enricher.resolveAllContacts(listOf(sms(1, "+1", 0), sms(2, "+1", 0), sms(3, "+2", 0)))
            assertEquals(mapOf("+1" to "John Doe"), names)
            verify(contactResolver, times(1)).resolveAll(listOf("+1", "+2"))
        }

    @Test
    fun `resolveAllContacts returns empty when the resolver throws`() =
        runTest {
            whenever(contactResolver.resolveAll(any<Collection<String>>()))
                .thenThrow(RuntimeException("nope"))
            assertTrue(enricher.resolveAllContacts(listOf(sms(1, "+1", 0), sms(2, "+2", 0))).isEmpty())
        }

    @Test
    fun `resolveAllContacts keeps only the resolved numbers`() =
        runTest {
            whenever(contactResolver.resolveAll(any<Collection<String>>()))
                .thenReturn(mapOf("+2" to "Jane Doe"))
            val names = enricher.resolveAllContacts(listOf(sms(1, "+1", 0), sms(2, "+2", 0)))
            assertEquals(mapOf("+2" to "Jane Doe"), names)
        }

    @Test
    fun `resolveAllContacts returns empty for an empty list without querying`() =
        runTest {
            assertTrue(enricher.resolveAllContacts(emptyList()).isEmpty())
            verify(contactResolver, times(0)).resolveAll(any<Collection<String>>())
        }

    @Test
    fun `resolveAllContacts caches the result per number set`() =
        runTest {
            whenever(contactResolver.resolveAll(any<Collection<String>>()))
                .thenReturn(mapOf("+1" to "John Doe"))
                .thenReturn(mapOf("+1" to "Jane Doe"))
            val first = enricher.resolveAllContacts(listOf(sms(1, "+1", 0), sms(2, "+1", 0)))
            val second = enricher.resolveAllContacts(listOf(sms(3, "+1", 0)))
            assertEquals(mapOf("+1" to "John Doe"), first)
            assertEquals(mapOf("+1" to "John Doe"), second)
            verify(contactResolver, times(1)).resolveAll(any<Collection<String>>())
        }

    @Test
    fun `resolveAllContacts re-resolves when the number set changes`() =
        runTest {
            whenever(contactResolver.resolveAll(any<Collection<String>>()))
                .thenReturn(mapOf("+1" to "John Doe"))
                .thenReturn(mapOf("+2" to "Jane Doe"))
            enricher.resolveAllContacts(listOf(sms(1, "+1", 0)))
            val names = enricher.resolveAllContacts(listOf(sms(2, "+2", 0)))
            assertEquals(mapOf("+2" to "Jane Doe"), names)
        }

    @Test
    fun `invalidate forces a re-resolve for an unchanged number set`() =
        runTest {
            whenever(contactResolver.resolveAll(any<Collection<String>>()))
                .thenReturn(mapOf("+1" to "sender-1"))
                .thenReturn(mapOf("+1" to "sender-2"))
            val first = enricher.resolveAllContacts(listOf(sms(1, "+1", 0)))
            enricher.invalidate()
            val second = enricher.resolveAllContacts(listOf(sms(2, "+1", 0)))
            assertEquals(mapOf("+1" to "sender-1"), first)
            assertEquals(mapOf("+1" to "sender-2"), second)
            verify(contactResolver, times(2)).resolveAll(any<Collection<String>>())
        }

    @Test
    fun `resolveContact returns null and does not query when contact resolution is off`() =
        runTest {
            whenever(prefs.load()).thenReturn(AppSettings(displaySimCards = true, useContactResolution = false))
            assertNull(enricher.resolveContact("+1"))
            verify(contactResolver, never()).resolve(any())
        }

    @Test
    fun `resolveContact returns the resolved name when contact resolution is on`() =
        runTest {
            whenever(contactResolver.resolve("+1")).thenReturn("John Doe")
            assertEquals("John Doe", enricher.resolveContact("+1"))
            verify(contactResolver).resolve("+1")
        }

    @Test
    fun `resolveContact returns null when the resolver throws`() =
        runTest {
            whenever(contactResolver.resolve("+1")).thenThrow(RuntimeException("nope"))
            assertNull(enricher.resolveContact("+1"))
        }

    @Test
    fun `resolveContact uses the single resolver path, not the batch cache`() =
        runTest {
            whenever(contactResolver.resolve("+1")).thenReturn("John Doe")
            assertEquals("John Doe", enricher.resolveContact("+1"))
            verify(contactResolver, never()).resolveAll(any<Collection<String>>())
        }
}
