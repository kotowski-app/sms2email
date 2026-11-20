package app.kotowski.sms2email.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import app.kotowski.sms2email.R
import app.kotowski.sms2email.system.SimNameResolver.ActiveSim
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Robolectric only provides a working android.os.Process (ContextCompat.checkSelfPermission
// reads the process pid/uid); the context and the telephony services are mocked.
// SubscriptionInfo is mocked instead of built: its Builder is gone in the compile SDK.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimNameResolverTest {
    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockSubscriptionManager: SubscriptionManager

    private lateinit var resolver: SimNameResolver

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        resolver = SimNameResolver(mockContext, PermissionManager(mockContext))
    }

    private fun slot(
        subscriptionId: Int,
        slotIndex: Int,
        displayName: CharSequence?,
    ): SubscriptionInfo {
        val info: SubscriptionInfo = mock()
        given(info.subscriptionId).willReturn(subscriptionId)
        given(info.simSlotIndex).willReturn(slotIndex)
        given(info.displayName).willReturn(displayName)
        return info
    }

    private fun stubSlots(
        phonePermissionGranted: Boolean,
        vararg slots: SubscriptionInfo,
    ) {
        given(mockContext.checkPermission(eq(Manifest.permission.READ_PHONE_STATE), anyInt(), anyInt()))
            .willReturn(if (phonePermissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
        given(mockContext.getSystemService(SubscriptionManager::class.java)).willReturn(mockSubscriptionManager)
        given(mockSubscriptionManager.activeSubscriptionInfoList).willReturn(slots.toList())
        given(mockContext.getString(R.string.history_unknown_sim)).willReturn("Missing SIM")
    }

    @Test
    fun `getSlotEntries returns the active slots as subscription id to display name pairs`() {
        stubSlots(true, slot(1, 0, "Home"), slot(2, 1, null))

        assertEquals(listOf(1 to "Home", 2 to null), resolver.getSlotEntries())
    }

    @Test
    fun `getSlotEntries returns an empty list without the phone permission`() {
        // The slots are active, but without READ_PHONE_STATE they must not be read.
        stubSlots(false, slot(1, 0, "Home"))

        assertTrue(resolver.getSlotEntries().isEmpty())
    }

    @Test
    fun `getSlotEntries returns an empty list when there are no active subscriptions`() {
        stubSlots(true)

        assertTrue(resolver.getSlotEntries().isEmpty())
    }

    @Test
    fun `getSimDisplayNameSync returns the display name of a known slot`() {
        stubSlots(true, slot(1, 0, "Home"))

        assertEquals("Home", resolver.getSimDisplayNameSync(1))
    }

    @Test
    fun `getSimDisplayNameSync falls back to the unknown label for an unknown slot`() {
        stubSlots(true, slot(1, 0, "Home"))

        assertEquals("Missing SIM", resolver.getSimDisplayNameSync(99))
    }

    @Test
    fun `getActiveSims sorts by slot index, uses 1-based slots and falls back to the unknown label`() {
        stubSlots(true, slot(2, 1, "Work"), slot(1, 0, null))

        assertEquals(
            listOf(
                ActiveSim(slot = 1, displayName = "Missing SIM"),
                ActiveSim(slot = 2, displayName = "Work"),
            ),
            resolver.getActiveSims(),
        )
    }

    @Test
    fun `getActiveSims sorts SIMs without a tray slot last and maps them to slot 0`() {
        stubSlots(true, slot(3, -1, "Remote"), slot(1, 0, null), slot(2, 1, "Work"))

        assertEquals(
            listOf(
                ActiveSim(slot = 1, displayName = "Missing SIM"),
                ActiveSim(slot = 2, displayName = "Work"),
                ActiveSim(slot = 0, displayName = "Remote"),
            ),
            resolver.getActiveSims(),
        )
    }

    @Test
    fun `getActiveSims keeps the platform order of SIMs without a tray slot`() {
        stubSlots(true, slot(3, -1, "Remote A"), slot(4, -1, "Remote B"), slot(1, 0, "Home"))

        assertEquals(
            listOf(
                ActiveSim(slot = 1, displayName = "Home"),
                ActiveSim(slot = 0, displayName = "Remote A"),
                ActiveSim(slot = 0, displayName = "Remote B"),
            ),
            resolver.getActiveSims(),
        )
    }

    @Test
    fun `getActiveSims returns an empty list when there are no active subscriptions`() {
        stubSlots(true)

        assertTrue(resolver.getActiveSims().isEmpty())
    }

    @Test
    fun `getSlotFor maps the tray index to the 1-based slot number`() {
        stubSlots(true, slot(5, 0, "Home"), slot(6, 2, "Work"))

        assertEquals(1, resolver.getSlotFor(5))
        assertEquals(3, resolver.getSlotFor(6))
    }

    @Test
    fun `getSlotFor maps a SIM without a tray slot to 0 (never in the 1-3 range)`() {
        stubSlots(true, slot(7, -1, "Remote"))

        assertEquals(0, resolver.getSlotFor(7))
    }

    @Test
    fun `getSlotFor returns null for an unknown subscription id`() {
        stubSlots(true, slot(5, 0, "Home"))

        assertNull(resolver.getSlotFor(99))
    }

    @Test
    fun `getSlotFor returns null without the phone permission (the empty slot map)`() {
        stubSlots(false, slot(5, 0, "Home"))

        assertNull(resolver.getSlotFor(5))
    }

    @Test
    fun `invalidate forces a fresh read of the changed slot list`() {
        stubSlots(true, slot(1, 0, "Home"))
        assertEquals("Home", resolver.getSimDisplayNameSync(1))

        // The slot is renamed; within the TTL the cache still serves the stale name.
        val renamed = slot(1, 0, "Work")
        given(mockSubscriptionManager.activeSubscriptionInfoList).willReturn(listOf(renamed))
        assertEquals("Home", resolver.getSimDisplayNameSync(1))

        resolver.invalidate()
        assertEquals("Work", resolver.getSimDisplayNameSync(1))
    }

    @Test
    fun `readers never throw while the phone permission flips on another thread`() {
        val permState = AtomicInteger(PackageManager.PERMISSION_GRANTED)
        given(mockContext.checkPermission(eq(Manifest.permission.READ_PHONE_STATE), anyInt(), anyInt()))
            .willAnswer { permState.get() }
        val home = slot(1, 0, "Home")
        given(mockContext.getSystemService(SubscriptionManager::class.java)).willReturn(mockSubscriptionManager)
        given(mockSubscriptionManager.activeSubscriptionInfoList).willReturn(listOf(home))
        given(mockContext.getString(R.string.history_unknown_sim)).willReturn("Missing SIM")

        val running = AtomicBoolean(true)
        val readers =
            (1..8).map { idx ->
                thread(start = false) {
                    while (running.get()) {
                        if (idx % 2 == 0) {
                            val entries = resolver.getSlotEntries()
                            assertTrue(entries.isEmpty() || entries == listOf(1 to "Home"))
                        } else {
                            val name = resolver.getSimDisplayNameSync(1)
                            assertTrue(name == "Home" || name == "Missing SIM")
                        }
                    }
                }
            }
        readers.forEach { it.start() }
        val deadline = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < deadline) {
            permState.set(PackageManager.PERMISSION_DENIED)
            Thread.sleep(1)
            permState.set(PackageManager.PERMISSION_GRANTED)
            Thread.sleep(1)
        }
        running.set(false)
        readers.forEach { it.join() }
    }

    @Test
    fun `returns empty map when SubscriptionManager is null`() {
        given(mockContext.checkPermission(eq(Manifest.permission.READ_PHONE_STATE), anyInt(), anyInt()))
            .willReturn(PackageManager.PERMISSION_GRANTED)
        given(mockContext.getSystemService(SubscriptionManager::class.java)).willReturn(null)
        given(mockContext.getString(R.string.history_unknown_sim)).willReturn("Missing SIM")

        assertTrue(resolver.getSlotEntries().isEmpty())
    }

    @Test
    fun `handles SecurityException from activeSubscriptionInfoList`() {
        given(mockContext.checkPermission(eq(Manifest.permission.READ_PHONE_STATE), anyInt(), anyInt()))
            .willReturn(PackageManager.PERMISSION_GRANTED)
        given(mockContext.getSystemService(SubscriptionManager::class.java)).willReturn(mockSubscriptionManager)
        given(mockSubscriptionManager.activeSubscriptionInfoList).willThrow(SecurityException("no access"))
        given(mockContext.getString(R.string.history_unknown_sim)).willReturn("Missing SIM")

        assertTrue(resolver.getSlotEntries().isEmpty())
    }
}
