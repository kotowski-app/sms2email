package app.kotowski.sms2email.system

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import app.kotowski.sms2email.data.prefs.AppSettings
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.testing.TestPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ContactResolverTest {
    private lateinit var resolver: ContactResolver

    @Before
    fun setUp() {
        resolver = ContactResolver(RuntimeEnvironment.getApplication(), defaultPrefs())
    }

    // The settings behind the resolver's override read: auto on (the product
    // default), so the chain drives the region unless a test changes it.
    private fun defaultPrefs(): EncryptedPrefs {
        val prefs: EncryptedPrefs = mock()
        whenever(prefs.load()).thenReturn(AppSettings())
        return prefs
    }

    // -- input guards (real Robolectric app; the provider stubs answer nothing) --

    @Test
    fun `resolve returns null for empty string`() {
        assertNull(resolver.resolve(""))
    }

    @Test
    fun `resolve returns null for whitespace only`() {
        assertNull(resolver.resolve("   "))
    }

    @Test
    fun `resolve returns null for single digit`() {
        assertNull(resolver.resolve("1"))
    }

    @Test
    fun `resolve returns null for two digits`() {
        assertNull(resolver.resolve("12"))
    }

    @Test
    fun `resolve returns null for special chars only`() {
        assertNull(resolver.resolve("---"))
    }

    @Test
    fun `resolve returns null for plus sign only`() {
        assertNull(resolver.resolve("+"))
    }

    @Test
    fun `resolve returns null for dash only`() {
        assertNull(resolver.resolve("-"))
    }

    @Test
    fun `resolve returns null for two digits with special chars`() {
        assertNull(resolver.resolve("1-2"))
    }

    @Test
    fun `resolve with three digits does not crash`() {
        assertNull(resolver.resolve("123"))
    }

    @Test
    fun `resolve with valid phone format does not crash`() {
        assertNull(resolver.resolve(TestPhone.SELF))
    }

    @Test
    fun `resolve strips non-digit characters before query`() {
        assertNull(resolver.resolve("+1 (555) 010-0099"))
    }

    @Test
    fun `resolve with spaces between digits does not crash`() {
        assertNull(resolver.resolve("1 2 3 4 5"))
    }

    // -- provider / scan behavior against mocked cursors --
    //
    // Robolectric's contacts provider is a stub (PhoneLookup and Data URIs answer
    // nothing), so the fast-path and E.164 fallback logic is tested against mocked
    // cursors. The mocked TelephonyManager pins the default region to GB so local
    // (no country code) numbers parse deterministically.

    private class ProviderMocks(
        simCountryIso: String? = "GB",
        networkCountryIso: String? = null,
        initialSettings: AppSettings = AppSettings(),
    ) {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val telephonyManager: TelephonyManager = mock()
        val prefs: EncryptedPrefs = mock()

        // The mutable settings the resolver's override read sees; a test changes it
        // mid-test to exercise the region cache's invalidation.
        var settings: AppSettings = initialSettings

        init {
            whenever(context.contentResolver).thenReturn(contentResolver)
            whenever(context.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(telephonyManager)
            whenever(telephonyManager.simCountryIso).thenReturn(simCountryIso)
            whenever(telephonyManager.networkCountryIso).thenReturn(networkCountryIso)
            whenever(prefs.load()).thenAnswer { settings }
        }

        val resolver = ContactResolver(context, prefs)

        private val phoneLookupBase = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.toString()

        private var phoneLookupHits: Map<String, String> = emptyMap()
        private var dataRows: List<Pair<String?, String?>> = emptyList()

        // Fast path: cleaned numbers that hit the provider, mapped to their name.
        // Everything else is a provider miss. Data scan: (number, display name) rows.
        fun stub(
            rows: List<Pair<String?, String?>> = emptyList(),
            phoneLookup: Map<String, String> = emptyMap(),
        ) {
            phoneLookupHits = phoneLookup
            dataRows = rows.toList()
            whenever(
                contentResolver.query(
                    any<Uri>(),
                    any<Array<String>>(),
                    anyOrNull<String>(),
                    anyOrNull<Array<String>>(),
                    anyOrNull<String>(),
                ),
            ).thenAnswer { invocation ->
                val uri = invocation.getArgument<Uri>(0)
                if (uri.toString().startsWith(phoneLookupBase)) {
                    val hit =
                        phoneLookupHits.entries
                            .firstOrNull { entry -> uri.toString().endsWith(Uri.encode(entry.key)) }
                            ?.value
                    phoneLookupCursor(hit)
                } else {
                    dataCursor(dataRows)
                }
            }
        }

        // Fast path only: the data scan query is left unstubbed and answers null
        // (a failing scan).
        fun stubFastPath(phoneLookup: Map<String, String>) {
            phoneLookupHits = phoneLookup
            whenever(
                contentResolver.query(
                    any<Uri>(),
                    any<Array<String>>(),
                    anyOrNull<String>(),
                    anyOrNull<Array<String>>(),
                    anyOrNull<String>(),
                ),
            ).thenAnswer { invocation ->
                val uri = invocation.getArgument<Uri>(0)
                if (uri.toString().startsWith(phoneLookupBase)) {
                    val hit =
                        phoneLookupHits.entries
                            .firstOrNull { entry -> uri.toString().endsWith(Uri.encode(entry.key)) }
                            ?.value
                    phoneLookupCursor(hit)
                } else {
                    null
                }
            }
        }

        private fun phoneLookupCursor(name: String?): Cursor {
            val c: Cursor = mock()
            whenever(c.moveToFirst()).thenReturn(name != null)
            whenever(c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)).thenReturn(0)
            whenever(c.getString(0)).thenReturn(name)
            return c
        }

        // A fresh cursor per query: the resolver consumes the cursor, and a second
        // query must not see the first traversal's end-of-file. The scan iterates with
        // moveToNext starting from before-first, so moveToFirst answers false.
        private fun dataCursor(rows: List<Pair<String?, String?>>): Cursor {
            val c: Cursor = mock()
            whenever(c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)).thenReturn(0)
            whenever(c.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)).thenReturn(1)
            whenever(c.moveToFirst()).thenReturn(false)
            var index = -1
            whenever(c.moveToNext()).thenAnswer {
                index++
                index < rows.size
            }
            whenever(c.getString(0)).thenAnswer { rows[index].first }
            whenever(c.getString(1)).thenAnswer { rows[index].second }
            return c
        }
    }

    @Test
    fun `resolve returns the provider hit without scanning contacts`() {
        val p = ProviderMocks()
        p.stub(phoneLookup = mapOf("442079460001" to "тест-1"))

        assertEquals("тест-1", p.resolver.resolve("+44 20 7946 0001"))

        verify(p.contentResolver, never())
            .query(
                eq(ContactsContract.Data.CONTENT_URI),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            )
    }

    @Test
    fun `resolve skips the fast path for a short sender that prefix-matches a stored contact`() {
        // The bug: the provider filter is a prefix match, so a 5-digit service code
        // returned an unrelated stored contact whose number starts with those digits.
        // Short senders must skip the fast path; the E.164 fallback rejects the
        // implausible code (GB region).
        val p = ProviderMocks()
        p.stub(phoneLookup = mapOf("12345" to "Short Code"), rows = listOf("12345550100" to "Short Code"))

        assertNull(p.resolver.resolve("12345"))
    }

    @Test
    fun `resolve uses the fast path at the fast-path minimum length`() {
        val p = ProviderMocks()
        p.stub(phoneLookup = mapOf("1234567" to "Seven"))

        assertEquals("Seven", p.resolver.resolve("1234567"))

        verify(p.contentResolver, never())
            .query(
                eq(ContactsContract.Data.CONTENT_URI),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            )
    }

    @Test
    fun `resolve falls back to the E164 scan when the stored number uses the trunk prefix`() {
        // The bug: contact stored as "020 7946 0001" does not match the incoming
        // "+44 20 7946 0001" through the provider query, but both normalize to
        // the same E.164.
        val p = ProviderMocks()
        p.stub(rows = listOf("020 7946 0001" to "тест-1"))

        assertEquals("тест-1", p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `resolve matches a stored international number against a local incoming number`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane Doe"))

        assertEquals("Jane Doe", p.resolver.resolve("02079460001"))
        assertEquals("Jane Doe", p.resolver.resolve("020 7946 0001"))
    }

    @Test
    fun `resolve returns null when no contact normalizes to the same E164`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+1 (555) 010-0000" to "John Doe"))

        assertNull(p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `resolve skips contact rows whose number does not normalize`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("unknown" to "Ghost"))

        assertNull(p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `resolve returns null when the scan query fails`() {
        val p = ProviderMocks()
        p.stubFastPath(emptyMap())

        assertNull(p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `resolve reuses the scan within the cache TTL`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane Doe"))

        assertEquals("Jane Doe", p.resolver.resolve("02079460001"))
        assertEquals("Jane Doe", p.resolver.resolve("+442079460001"))

        verify(p.contentResolver, times(1))
            .query(
                eq(ContactsContract.Data.CONTENT_URI),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            )
    }

    @Test
    fun `invalidate forces a fresh scan on the next read`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane Doe"))

        assertEquals("Jane Doe", p.resolver.resolve("02079460001"))
        p.resolver.invalidate()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane D."))
        assertEquals("Jane D.", p.resolver.resolve("02079460001"))

        verify(p.contentResolver, times(2))
            .query(
                eq(ContactsContract.Data.CONTENT_URI),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            )
    }

    // -- default region resolution (sim -> network -> device locale) --

    @Test
    fun `resolve normalizes a stored local number with the network country when the SIM reports none`() {
        // The bug: a SIM without a country plus an explicit EN/RU app language left the
        // region empty, so a stored local (no country code) number never normalized.
        val p = ProviderMocks(simCountryIso = null, networkCountryIso = "fr")
        p.stub(rows = listOf("01 23 45 67 89" to "sender-2"))

        assertEquals("sender-2", p.resolver.resolve("+33 1 23 45 67 89"))
    }

    @Test
    fun `resolve prefers the SIM country over the network country`() {
        val p = ProviderMocks(simCountryIso = "gb", networkCountryIso = "fr")
        p.stub(rows = listOf("020 7946 0001" to "тест-1"))

        assertEquals("тест-1", p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `resolve falls back to the device locale country when the SIM and network report none`() {
        withDeviceLocale(Locale.US) {
            val p = ProviderMocks(simCountryIso = null, networkCountryIso = null)
            p.stub(rows = listOf("(555) 010-0000" to "John Doe"))

            assertEquals("John Doe", p.resolver.resolve("+1 555 010 0000"))
        }
    }

    @Test
    fun `resolve falls back to the device locale country when the network country throws`() {
        withDeviceLocale(Locale.US) {
            val p = ProviderMocks(simCountryIso = null)
            whenever(p.telephonyManager.networkCountryIso)
                .thenThrow(UnsupportedOperationException("no radio"))
            p.stub(rows = listOf("(555) 010-0000" to "John Doe"))

            assertEquals("John Doe", p.resolver.resolve("+1 555 010 0000"))
        }
    }

    @Test
    fun `resolve falls back to the device locale country when the app language pins a countryless JVM default`() {
        // LanguageHelper pins Locale.setDefault to the picked app language (language-only
        // "en"/"ru", empty country); reading Locale.getDefault().country there would get
        // "", so local numbers never normalized.
        val original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("en"))
        try {
            withDeviceLocale(Locale.US) {
                val p = ProviderMocks(simCountryIso = null, networkCountryIso = null)
                p.stub(rows = listOf("(555) 010-0000" to "John Doe"))

                assertEquals("John Doe", p.resolver.resolve("+1 555 010 0000"))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `resolve returns null for a local number when no source provides a country`() {
        withDeviceLocale(Locale.forLanguageTag("en")) {
            val p = ProviderMocks(simCountryIso = null, networkCountryIso = null)
            p.stub(rows = listOf("(555) 010-0000" to "John Doe"))

            assertNull(p.resolver.resolve("(555) 010-0000"))
        }
    }

    // -- the manual country override (the user setting over the detected chain) --

    @Test
    fun `a valid manual country code wins over the SIM country`() {
        // Lowercase input: the code is canonicalized (uppercased) before use.
        val p = ProviderMocks(simCountryIso = "gb")
        p.settings = p.settings.copy(autoCountry = false, countryCode = "fr")
        p.stub(rows = listOf("01 23 45 67 89" to "sender-2"))

        assertEquals("sender-2", p.resolver.resolve("+33 1 23 45 67 89"))
    }

    @Test
    fun `an invalid manual country code falls back to the chain`() {
        val p = ProviderMocks(simCountryIso = "gb")
        p.settings = p.settings.copy(autoCountry = false, countryCode = "xx")
        p.stub(rows = listOf("020 7946 0001" to "тест-1"))

        assertEquals("тест-1", p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `a blank manual country code falls back to the chain`() {
        val p = ProviderMocks(simCountryIso = "gb")
        p.settings = p.settings.copy(autoCountry = false, countryCode = "  ")
        p.stub(rows = listOf("020 7946 0001" to "тест-1"))

        assertEquals("тест-1", p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `auto on ignores the stored manual country code`() {
        val p = ProviderMocks(simCountryIso = "gb")
        p.settings = p.settings.copy(autoCountry = true, countryCode = "fr")
        p.stub(rows = listOf("01 23 45 67 89" to "sender-2"))

        assertNull(p.resolver.resolve("+33 1 23 45 67 89"))
    }

    @Test
    fun `detectedRegion returns the chain value independent of the override`() {
        val p = ProviderMocks(simCountryIso = "gb")
        p.settings = p.settings.copy(autoCountry = false, countryCode = "fr")

        assertEquals("GB", p.resolver.detectedRegion())
    }

    @Test
    fun `changing the manual code re-resolves the region and the scan follows`() {
        // The first scan runs under the SIM region (GB); the setting change must
        // invalidate both the region and the scan built with the old region.
        val p = ProviderMocks(simCountryIso = "gb")
        p.stub(rows = listOf("020 7946 0001" to "тест-1", "15550100001" to "sender-2"))

        assertEquals("тест-1", p.resolver.resolve("02079460001"))
        assertNull(p.resolver.resolve("15550100001"))

        p.settings = p.settings.copy(autoCountry = false, countryCode = "us")

        assertEquals("sender-2", p.resolver.resolve("15550100001"))
        assertNull(p.resolver.resolve("02079460001"))
    }

    @Test
    fun `a SIM swap re-resolves the detected region and the scan follows`() {
        // The bug: a SIM swap left the stale region cached for the whole process, so a
        // local number normalized with the old country. The zero TTL forces a re-resolve
        // on every read; the swap must invalidate both the region and the scan built
        // with the old region.
        val p = ProviderMocks(simCountryIso = "gb")
        p.resolver.detectedRegionTtlMs = 0L
        p.stub(rows = listOf("020 7946 0001" to "тест-1", "15550100001" to "sender-2"))

        assertEquals("тест-1", p.resolver.resolve("02079460001"))
        assertNull(p.resolver.resolve("15550100001"))

        whenever(p.telephonyManager.simCountryIso).thenReturn("us")

        assertEquals("sender-2", p.resolver.resolve("15550100001"))
        assertNull(p.resolver.resolve("02079460001"))
    }

    @Test
    fun `detectedRegion is not re-resolved within the TTL`() {
        // The default TTL (60 seconds) keeps the cached region: a SIM swap inside the
        // window must not change the detected region.
        val p = ProviderMocks(simCountryIso = "gb")

        assertEquals("GB", p.resolver.detectedRegion())

        whenever(p.telephonyManager.simCountryIso).thenReturn("us")

        assertEquals("GB", p.resolver.detectedRegion())
    }

    @Test
    fun `toSearchNumber follows the manual country code`() {
        // Both sides of the search-facet contract normalize with the effective region.
        val p = ProviderMocks(simCountryIso = "gb")
        p.settings = p.settings.copy(autoCountry = false, countryCode = "us")

        assertEquals("+15550100000", p.resolver.toSearchNumber("555 010 0000"))
    }

    @Test
    fun `resolveAll resolves fast-path hits and fallback misses with a single scan`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane Doe"), phoneLookup = mapOf("15550100000" to "John Doe"))

        val names = p.resolver.resolveAll(listOf("020 7946 0001", "1 555 010 0000"))

        assertEquals(
            mapOf("020 7946 0001" to "Jane Doe", "1 555 010 0000" to "John Doe"),
            names,
        )
        verify(p.contentResolver, times(1))
            .query(
                eq(ContactsContract.Data.CONTENT_URI),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            )
    }

    @Test
    fun `resolveAll keeps only fast-path hits when the scan query fails`() {
        val p = ProviderMocks()
        p.stubFastPath(mapOf("15550100000" to "John Doe"))

        val names = p.resolver.resolveAll(listOf("1 555 010 0000", "02079460001"))

        assertEquals(mapOf("1 555 010 0000" to "John Doe"), names)
    }

    @Test
    fun `resolveAll returns empty for empty input without querying`() {
        val p = ProviderMocks()

        assertEquals(emptyMap<String, String>(), p.resolver.resolveAll(emptyList()))

        verify(p.contentResolver, never())
            .query(any<Uri>(), anyOrNull<Array<String>>(), anyOrNull<String>(), anyOrNull<Array<String>>(), anyOrNull<String>())
    }

    @Test
    fun `resolveAll skips too-short numbers without querying`() {
        val p = ProviderMocks()
        p.stub()

        assertEquals(emptyMap<String, String>(), p.resolver.resolveAll(listOf("12", "---", "")))

        verify(p.contentResolver, never())
            .query(any<Uri>(), anyOrNull<Array<String>>(), anyOrNull<String>(), anyOrNull<Array<String>>(), anyOrNull<String>())
    }

    @Test
    fun `resolveAll skips the fast path for short senders`() {
        // A 5-digit sender prefix-matches the stored contact in the provider stub;
        // the gate must send it to the E.164 fallback, which rejects it (GB region).
        val p = ProviderMocks()
        p.stub(phoneLookup = mapOf("12345" to "Short Code"), rows = listOf("12345550100" to "Short Code"))

        assertEquals(emptyMap<String, String>(), p.resolver.resolveAll(listOf("12345")))
    }

    @Test
    fun `resolveAll returns partial results when some numbers fail E164 normalization`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane Doe"))

        val names = p.resolver.resolveAll(listOf("020 7946 0001", "invalid_number_999"))

        assertEquals(mapOf("020 7946 0001" to "Jane Doe"), names)
    }

    @Test
    fun `resolveNumbersByName matches multiple contacts with overlapping names`() {
        val p = ProviderMocks()
        p.stub(
            rows =
                listOf(
                    "+1 (555) 010-0000" to "John Doe",
                    "+1 (555) 010-0001" to "John Smith",
                    "+33 1 23 45 67 89" to "Jane Doe",
                ),
        )

        val numbers = p.resolver.resolveNumbersByName("john")

        assertEquals(2, numbers.size)
        assertTrue(numbers.contains("+15550100000"))
        assertTrue(numbers.contains("+15550100001"))
    }

    @Test
    fun `resolveNumbersByName returns empty when query matches no names`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane Doe"))

        assertEquals(emptyList<String>(), p.resolver.resolveNumbersByName("xyz_nonexistent"))
    }

    @Test
    fun `resolve returns null when the number is too short for fast path and fails E164`() {
        val p = ProviderMocks()
        p.stub(rows = emptyList())

        assertNull(p.resolver.resolve("12345"))
    }

    // -- resolveNumbersByName --
    //
    // Robolectric's contacts provider is a stub (Contacts/Data URIs return null), so the
    // name-matching logic is tested against a mocked cursor.

    @Test
    fun `resolveNumbersByName returns an empty list for an empty query without querying`() {
        val p = ProviderMocks()
        p.stub()

        assertEquals(emptyList<String>(), p.resolver.resolveNumbersByName(""))

        verify(p.contentResolver, never())
            .query(any<Uri>(), anyOrNull<Array<String>>(), anyOrNull<String>(), anyOrNull<Array<String>>(), anyOrNull<String>())
    }

    @Test
    fun `resolveNumbersByName matches the display name case-insensitively`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+1 (555) 010-0000" to "John Doe", "+33 1 23 45 67 89" to "Jane Doe"))

        assertEquals(listOf("+15550100000"), p.resolver.resolveNumbersByName("john"))
        assertEquals(listOf("+15550100000"), p.resolver.resolveNumbersByName("JOHN"))
    }

    @Test
    fun `resolveNumbersByName matches a cyrillic name case-insensitively`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "тест-1", "+33 1 23 45 67 89" to "sender-2"))

        assertEquals(listOf("+442079460001"), p.resolver.resolveNumbersByName("тест"))
        assertEquals(listOf("+442079460001"), p.resolver.resolveNumbersByName("ТЕСТ"))
    }

    @Test
    fun `resolveNumbersByName normalizes to E164 and dedupes`() {
        // Two differently formatted numbers (leading zero, no country code) with the
        // same digits, both name-matched: both fold to one E.164.
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "John Doe", "020 7946 0001" to "John Doe Jr."))

        assertEquals(listOf("+442079460001"), p.resolver.resolveNumbersByName("john"))
    }

    @Test
    fun `resolveNumbersByName normalizes a local number with the SIM region`() {
        // No country code in the stored number: the mocked SIM region (GB)
        // disambiguates it exactly like the display path.
        val p = ProviderMocks()
        p.stub(rows = listOf("020 7946 0001" to "тест-1"))

        assertEquals(listOf("+442079460001"), p.resolver.resolveNumbersByName("тест"))
    }

    @Test
    fun `resolveNumbersByName falls back to the digits-only fold when the number does not normalize`() {
        // The 11-digit string is not a plausible GB national number: the facet
        // keeps the digits so the name search still finds the row.
        val p = ProviderMocks()
        p.stub(rows = listOf("15550100001" to "John Doe"))

        assertEquals(listOf("15550100001"), p.resolver.resolveNumbersByName("john"))
    }

    @Test
    fun `resolveNumbersByName returns the E164 the display path matches on`() {
        // The two sides of `searchNumber IN (...)` cannot drift: what the name search
        // returns is the key resolve() looks up the display name with.
        val p = ProviderMocks()
        p.stub(rows = listOf("020 7946 0001" to "тест-1"))

        assertEquals(listOf("+442079460001"), p.resolver.resolveNumbersByName("тест"))
        assertEquals("тест-1", p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `resolveNumbersByName skips rows without a matching name`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+33 1 23 45 67 89" to "Jane Doe B."))

        assertEquals(emptyList<String>(), p.resolver.resolveNumbersByName("john"))
    }

    @Test
    fun `resolveNumbersByName skips numbers without digits`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("unknown" to "John Doe"))

        assertEquals(emptyList<String>(), p.resolver.resolveNumbersByName("john"))
    }

    @Test
    fun `resolveNumbersByName returns an empty list when the query fails`() {
        val p = ProviderMocks()
        p.stubFastPath(emptyMap())

        assertEquals(emptyList<String>(), p.resolver.resolveNumbersByName("john"))
    }

    @Test
    fun `resolveNumbersByName queries the phone data rows by mimetype`() {
        val p = ProviderMocks()
        p.stub()

        p.resolver.resolveNumbersByName("john")

        verify(p.contentResolver).query(
            eq(ContactsContract.Data.CONTENT_URI),
            any<Array<String>>(),
            eq("${ContactsContract.Data.MIMETYPE} = ?"),
            eq(arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)),
            eq(null as String?),
        )
    }

    @Test
    fun `resolveAll skips entries whose E164 normalization fails`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane Doe"))

        val names = p.resolver.resolveAll(listOf("020 7946 0001", "99999999"))

        assertEquals(mapOf("020 7946 0001" to "Jane Doe"), names)
    }

    @Test
    fun `resolveNumbersByName iterates over cursor data rows`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "John Doe"))

        assertEquals(listOf("+442079460001"), p.resolver.resolveNumbersByName("john"))
    }

    @Test
    fun `resolveNumbersByName returns empty when column index is negative`() {
        val p = ProviderMocks()
        whenever(
            p.contentResolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenAnswer { invocation ->
            val c: Cursor = mock()
            whenever(c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)).thenReturn(-1)
            whenever(c.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)).thenReturn(-1)
            whenever(c.moveToFirst()).thenReturn(false)
            whenever(c.moveToNext()).thenReturn(false)
            c
        }

        assertEquals(emptyList<String>(), p.resolver.resolveNumbersByName("john"))
    }

    @Test
    fun `resolveNumbersByName skips rows with null name`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to null))

        assertEquals(emptyList<String>(), p.resolver.resolveNumbersByName("john"))
    }

    @Test
    fun `resolveNumbersByName skips non-matching names`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane Smith"))

        assertEquals(emptyList<String>(), p.resolver.resolveNumbersByName("john"))
    }

    @Test
    fun `fastPathLookup returns null when nameIndex is negative`() {
        val p = ProviderMocks()
        whenever(
            p.contentResolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenAnswer { invocation ->
            val c: Cursor = mock()
            whenever(c.moveToFirst()).thenReturn(true)
            whenever(c.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)).thenReturn(-1)
            c
        }

        assertNull(p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `contacts returns cached value within TTL`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to "Jane Doe"))

        assertEquals("Jane Doe", p.resolver.resolve("02079460001"))
        assertEquals("Jane Doe", p.resolver.resolve("02079460001"))

        verify(p.contentResolver, times(1))
            .query(
                eq(ContactsContract.Data.CONTENT_URI),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            )
    }

    @Test
    fun `contacts returns null when query fails`() {
        val p = ProviderMocks()
        whenever(p.contentResolver.query(any<Uri>(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(null)

        assertNull(p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `contacts returns null when column index is negative`() {
        val p = ProviderMocks()
        whenever(
            p.contentResolver.query(
                any<Uri>(),
                any<Array<String>>(),
                anyOrNull<String>(),
                anyOrNull<Array<String>>(),
                anyOrNull<String>(),
            ),
        ).thenAnswer { invocation ->
            val uri = invocation.getArgument<Uri>(0)
            if (uri == ContactsContract.Data.CONTENT_URI) {
                val c: Cursor = mock()
                whenever(c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)).thenReturn(-1)
                whenever(c.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)).thenReturn(1)
                whenever(c.moveToFirst()).thenReturn(false)
                c
            } else {
                null
            }
        }

        assertNull(p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `contacts skips rows with null name`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("+44 20 7946 0001" to null))

        assertNull(p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `contacts skips rows where E164 normalization fails`() {
        val p = ProviderMocks()
        p.stub(rows = listOf("unparseable" to "Ghost"))

        assertNull(p.resolver.resolve("+442079460001"))
    }

    @Test
    fun `toSearchNumber with blank input returns empty string`() {
        assertEquals("", resolver.toSearchNumber(""))
    }

    @Test
    fun `detectedRegionValue returns cached region within TTL`() {
        val p = ProviderMocks(simCountryIso = "gb")

        assertEquals("GB", p.resolver.detectedRegion())

        whenever(p.telephonyManager.simCountryIso).thenReturn("us")

        assertEquals("GB", p.resolver.detectedRegion())
    }

    @Test
    fun `resolveDefaultRegion falls back to device locale when TelephonyManager is null`() {
        withDeviceLocale(Locale.US) {
            val p = ProviderMocks(simCountryIso = null)
            whenever(p.context.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(null)
            p.stub(rows = listOf("(555) 010-0000" to "John Doe"))

            assertEquals("John Doe", p.resolver.resolve("+1 555 010 0000"))
        }
    }

    // Pins the device locale (the system resources' configuration) for the block: the
    // region fallback reads Resources.getSystem(), which the app's Locale.setDefault
    // pinning does not touch.
    private fun withDeviceLocale(
        locale: Locale,
        block: () -> Unit,
    ) {
        val configuration = Resources.getSystem().configuration
        val original = configuration.locales[0]
        configuration.setLocale(locale)
        try {
            block()
        } finally {
            configuration.setLocale(original)
        }
    }
}
