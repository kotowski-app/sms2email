package app.kotowski.sms2email.receiver

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.provider.Telephony
import android.telephony.SubscriptionManager
import app.kotowski.sms2email.util.LogBuffer
import app.kotowski.sms2email.work.SmsProcessor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.security.KeyStoreException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SmsReceiverTest {
    @Mock private lateinit var smsProcessor: SmsProcessor
    private lateinit var receiver: SmsReceiver
    private lateinit var closeable: AutoCloseable
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        closeable = MockitoAnnotations.openMocks(this)
        receiver = MockProcessorReceiver(smsProcessor)
    }

    @After
    fun tearDown() {
        closeable.close()
    }

    private fun deliverPdu(
        body: String,
        seq: Int? = null,
        total: Int? = null,
        blankAddress: Boolean = false,
    ): ByteArray {
        val payload = body.toByteArray(Charsets.UTF_16BE)
        val udh =
            if (seq != null) {
                byteArrayOf(0x00, 0x03, 0x12, total!!.toByte(), seq.toByte())
            } else {
                ByteArray(0)
            }
        val udhl = if (seq != null) byteArrayOf(udh.size.toByte()) else ByteArray(0)
        val udl = (udh.size + udhl.size + payload.size).toByte()
        val mti = (if (seq != null) 0x40 else 0x00).toByte()
        // A zero-length OR address is what some service codes report; the framework
        // (Android 14) fails to parse such a PDU (createFromPdu returns null).
        val orAddress =
            if (blankAddress) {
                byteArrayOf(0x00)
            } else {
                byteArrayOf(0x0A, 0x81.toByte(), 0x51, 0x55, 0x10, 0x00, 0x00)
            }
        // The SCTS is 2026-09-08 01:03:00, timezone +08:00. The digits are packed
        // ones-first (AOSP's IccUtils.gsmBcdByteToInt reads the high nibble as the
        // ones digit), so 08 is 0x80, not 0x08 — a standard-BCD byte decodes as an
        // out-of-range date and the framework yields a 0 timestamp.
        val scts = byteArrayOf(0x62, 0x90.toByte(), 0x80.toByte(), 0x10, 0x30, 0x00, 0x23)
        return byteArrayOf(0x00) +
            byteArrayOf(mti) +
            orAddress +
            byteArrayOf(0x00) +
            byteArrayOf(0x08) +
            scts +
            byteArrayOf(udl) +
            udhl +
            udh +
            payload
    }

    private fun receivedIntent(
        vararg pdus: ByteArray,
        subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    ): Intent {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        if (pdus.isNotEmpty()) intent.putExtra("pdus", pdus)
        intent.putExtra("format", "3gpp")
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId)
        }
        return intent
    }

    @Test
    fun `onReceive does nothing for non SMS_RECEIVED action`() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        verify(smsProcessor, never()).processIncomingSms(any(), any())
    }

    @Test
    fun `onReceive does nothing when intent has no pdus`() {
        receiver.onReceive(context, receivedIntent())
        verify(smsProcessor, never()).processIncomingSms(any(), any())
    }

    @Test
    fun `onReceive forwards single sms`() {
        receiver.onReceive(context, receivedIntent(deliverPdu("Hello")))

        val captor = argumentCaptor<SmsProcessor.IncomingSms>()
        verify(smsProcessor).processIncomingSms(captor.capture(), any())
        val sms = captor.firstValue
        assertEquals("Hello", sms.messageBody)
        assertNotNull(sms.phoneNumber)
        assertTrue(sms.phoneNumber.contains("1555010000"))
        assertEquals(SubscriptionManager.INVALID_SUBSCRIPTION_ID, sms.simId)
    }

    @Test
    fun `onReceive passes the PDU service-center timestamp as pduTimestamp`() {
        val pdu = deliverPdu("Hello")
        val intent = receivedIntent(pdu)

        // The framework's own parse of the same PDU is the expected SCTS.
        @Suppress("DEPRECATION")
        val expectedPduTimestamp =
            Telephony.Sms.Intents
                .getMessagesFromIntent(intent)!!
                .first()
                .timestampMillis

        receiver.onReceive(context, intent)

        val captor = argumentCaptor<SmsProcessor.IncomingSms>()
        verify(smsProcessor).processIncomingSms(captor.capture(), any())
        assertEquals(expectedPduTimestamp, captor.firstValue.pduTimestamp)
    }

    @Test
    fun `onReceive passes a non-zero PDU timestamp for a PDU carrying an SCTS`() {
        // The test PDU carries a service-center timestamp, so the raw value must be
        // forwarded (not zeroed) for the "Use PDU timestamp" setting to act on it.
        val expected =
            ZonedDateTime.of(2026, 9, 8, 1, 3, 0, 0, ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
        receiver.onReceive(context, receivedIntent(deliverPdu("Hello")))

        val captor = argumentCaptor<SmsProcessor.IncomingSms>()
        verify(smsProcessor).processIncomingSms(captor.capture(), any())
        assertEquals(expected, captor.firstValue.pduTimestamp)
    }

    @Test
    fun `onReceive drops a PDU the framework fails to parse without crashing`() {
        // A zero-length originating address is what some service codes report; the
        // framework (Android 14) cannot parse such a PDU and yields a null entry in the
        // messages array. The receiver must drop it (nothing to forward: no body, no
        // address) instead of crashing on the null.
        receiver.onReceive(context, receivedIntent(deliverPdu("Hello", blankAddress = true)))

        verify(smsProcessor, never()).processIncomingSms(any(), any())
    }

    @Test
    fun `onReceive swallows exception from processor on single sms`() {
        doThrow(RuntimeException("boom")).whenever(smsProcessor).processIncomingSms(any(), any())
        receiver.onReceive(context, receivedIntent(deliverPdu("Hello")))
        verify(smsProcessor).processIncomingSms(any(), any())
    }

    @Test
    fun `onReceive joins multipart segments in arrival order when framework body is missing`() {
        val part2 = deliverPdu("BBB", seq = 2, total = 3)
        val part3 = deliverPdu("CCC", seq = 3, total = 3)
        val part1 = deliverPdu("AAA", seq = 1, total = 3)
        receiver.onReceive(context, receivedIntent(part2, part3, part1, subId = 7))

        val captor = argumentCaptor<SmsProcessor.IncomingSms>()
        verify(smsProcessor).processIncomingSms(captor.capture(), any())
        val sms = captor.firstValue
        assertEquals("BBBCCCAAA", sms.messageBody)
        assertEquals(7, sms.simId)
    }

    @Test
    fun `onReceive swallows exception from processor on multipart sms`() {
        doThrow(RuntimeException("boom")).whenever(smsProcessor).processIncomingSms(any(), any())
        receiver.onReceive(
            context,
            receivedIntent(
                deliverPdu("AAA", seq = 1, total = 2),
                deliverPdu("BBB", seq = 2, total = 2),
            ),
        )
        verify(smsProcessor).processIncomingSms(any(), any())
    }

    @Test
    fun `onReceive logs and drops the sms when the processor cannot be built`() {
        // A broken keystore makes building the processor throw in onReceive. It is caught
        // there: the SMS is logged and dropped instead of crashing the process.
        FailingProcessorReceiver().onReceive(context, receivedIntent(deliverPdu("Hello")))

        val lastLog = LogBuffer.entries.lastOrNull { it.tag == "SmsReceiver" }?.message
        assertTrue(lastLog?.contains("dropping SMS") == true)
    }

    private fun dispatch(intent: Intent) {
        context.registerReceiver(receiver, IntentFilter(intent.action))
        context.sendBroadcast(intent)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `pending result stays open until processing completes`() {
        dispatch(receivedIntent(deliverPdu("Hello")))

        val shadowReceiver = Shadows.shadowOf(receiver)
        assertTrue(shadowReceiver.wentAsync())
        val originalPendingResult = shadowReceiver.getOriginalPendingResult()
        assertNotNull(originalPendingResult)
        val shadowPendingResult = Shadows.shadowOf(originalPendingResult)
        assertFalse(shadowPendingResult.future.isDone)

        val callbackCaptor = argumentCaptor<() -> Unit>()
        verify(smsProcessor).processIncomingSms(any(), callbackCaptor.capture())
        callbackCaptor.firstValue.invoke()

        shadowPendingResult.future.get(5, TimeUnit.SECONDS)
        assertTrue(shadowPendingResult.future.isDone)
    }

    @Test
    fun `pending result is finished synchronously for unexpected action`() {
        dispatch(Intent(Intent.ACTION_BOOT_COMPLETED))

        val shadowReceiver = Shadows.shadowOf(receiver)
        assertTrue(shadowReceiver.wentAsync())
        val originalPendingResult = shadowReceiver.getOriginalPendingResult()
        assertNotNull(originalPendingResult)
        val shadowPendingResult = Shadows.shadowOf(originalPendingResult)
        assertTrue(shadowPendingResult.future.isDone)
        verify(smsProcessor, never()).processIncomingSms(any(), any())
    }

    @Test
    fun `pending result is finished synchronously when intent has no pdus`() {
        dispatch(receivedIntent())

        val shadowReceiver = Shadows.shadowOf(receiver)
        assertTrue(shadowReceiver.wentAsync())
        val originalPendingResult = shadowReceiver.getOriginalPendingResult()
        assertNotNull(originalPendingResult)
        val shadowPendingResult = Shadows.shadowOf(originalPendingResult)
        assertTrue(shadowPendingResult.future.isDone)
        verify(smsProcessor, never()).processIncomingSms(any(), any())
    }

    @Test
    fun `pending result is finished synchronously when the processor cannot be built`() {
        // The graceful drop must not leave the pending result open: it is finished so the
        // broadcast is acked even though the SMS is dropped.
        val failing = FailingProcessorReceiver()
        context.registerReceiver(failing, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
        context.sendBroadcast(receivedIntent(deliverPdu("Hello")))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val shadowReceiver = Shadows.shadowOf(failing)
        assertTrue(shadowReceiver.wentAsync())
        val originalPendingResult = shadowReceiver.getOriginalPendingResult()
        assertNotNull(originalPendingResult)
        val shadowPendingResult = Shadows.shadowOf(originalPendingResult)
        assertTrue(shadowPendingResult.future.isDone)
    }

    @Test
    fun `pending result is finished when the handling section throws`() {
        // A throw in the handling section (the parse or the processor) must not leave the
        // pending result open: the guard in onReceive catches it and finishes the result so
        // the broadcast is acked even though the SMS is dropped.
        doThrow(RuntimeException("boom")).whenever(smsProcessor).processIncomingSms(any(), any())
        dispatch(receivedIntent(deliverPdu("Hello")))

        val shadowReceiver = Shadows.shadowOf(receiver)
        assertTrue(shadowReceiver.wentAsync())
        val originalPendingResult = shadowReceiver.getOriginalPendingResult()
        assertNotNull(originalPendingResult)
        val shadowPendingResult = Shadows.shadowOf(originalPendingResult)
        assertTrue(shadowPendingResult.future.isDone)
    }

    @Test
    fun `assembleBody uses framework display body when it covers all segments`() {
        assertEquals("AAABBBCCC", receiver.assembleBody("AAABBBCCC", listOf("AAA", "BBB", "CCC")))
    }

    @Test
    fun `assembleBody joins segments in order when display body is missing`() {
        assertEquals("BBBCCCAAA", receiver.assembleBody(null, listOf("BBB", "CCC", "AAA")))
    }

    @Test
    fun `assembleBody joins segments when display body is shorter than segments`() {
        assertEquals("AAABBBCCC", receiver.assembleBody("AAA", listOf("AAA", "BBB", "CCC")))
    }

    @Test
    fun `assembleBody uses display body when it exactly matches segment length`() {
        assertEquals("AAABBB", receiver.assembleBody("AAABBB", listOf("AAA", "BBB")))
    }

    @Test
    fun `assembleBody uses display body for single segment`() {
        assertEquals("Hello", receiver.assembleBody("Hello", listOf("Hello")))
    }

    @Test
    fun `onReceive passes subscription id for multipart sms`() {
        val part1 = deliverPdu("AAA", seq = 1, total = 2)
        val part2 = deliverPdu("BBB", seq = 2, total = 2)
        receiver.onReceive(context, receivedIntent(part1, part2, subId = 5))

        val captor = argumentCaptor<SmsProcessor.IncomingSms>()
        verify(smsProcessor).processIncomingSms(captor.capture(), any())
        assertEquals(5, captor.firstValue.simId)
        assertTrue(captor.firstValue.messageBody.contains("AAA"))
        assertTrue(captor.firstValue.messageBody.contains("BBB"))
    }
}

// Substitutes the processor for the unit tests: the production SmsReceiver resolves it through
// the Hilt EntryPoint (unreachable in a bare Robolectric app), so the tests pin a mock here.
private class MockProcessorReceiver(
    private val processor: SmsProcessor,
) : SmsReceiver() {
    override fun resolveSmsProcessor(context: Context): SmsProcessor = processor
}

// Simulates the broken-keystore failure: building the processor throws, which is exactly what
// MasterKey/prefs construction does on a device with an unavailable keystore.
private class FailingProcessorReceiver : SmsReceiver() {
    override fun resolveSmsProcessor(context: Context): SmsProcessor = throw KeyStoreException("keystore unavailable")
}
