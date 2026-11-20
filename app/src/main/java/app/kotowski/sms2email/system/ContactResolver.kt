package app.kotowski.sms2email.system

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.util.querySafely
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val prefs: EncryptedPrefs,
    ) {
        private val phoneNumberUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

        // The detected region (SIM -> network -> device locale), cached with a TTL so a
        // SIM swap or a roam re-resolves it without a process restart. The chain is a few
        // TelephonyManager/locale getters, so it runs at most once per TTL window, never
        // per number (the per-number path reads this cache).
        @Volatile
        private var detectedRegionCache: Pair<String, Long>? = null

        // Test seam (like SimChangeObserver's pollIntervalMs): the detected-region cache
        // TTL, so the re-resolution is exercisable without waiting the real interval.
        internal var detectedRegionTtlMs: Long = AppConfig.DETECTED_REGION_CACHE_TTL_MS

        // The effective region's cache: the key is the user override ("" while auto is
        // on or the code is blank/invalid), the value the region normalization uses.
        // Reading the override is a plain in-memory settings read, so a settings change
        // re-resolves the region without re-running the telephony chain per number, and
        // the scan built with the old region is dropped with the stale key.
        @Volatile
        private var regionCache: Pair<String, String>? = null

        // Volatile: the singleton is hit from several background threads (IO pool,
        // WorkManager); worst case without it would be a lost or duplicated scan.
        @Volatile
        private var contactsCache: Map<String, String>? = null

        @Volatile
        private var contactsCacheAt = 0L

        // The detected region as the UI's auto value: the chain result, independent of
        // the user override.
        fun detectedRegion(): String = detectedRegionValue()

        // Driven by ContactChangeObserver on a contact change, so a rename shows up without
        // waiting for the TTL. Mirrors SimNameResolver.invalidate().
        fun invalidate() {
            contactsCache = null
            contactsCacheAt = 0
        }

        fun resolve(phoneNumber: String): String? {
            val cleanedNumber = clean(phoneNumber)
            if (cleanedNumber.length < AppConfig.CONTACT_MIN_NUMBER_LENGTH) return null

            // The provider filter is a prefix match: a short sender could prefix-match an
            // unrelated contact, so only long-enough numbers take the fast path.
            if (cleanedNumber.length >= AppConfig.CONTACT_FAST_PATH_MIN_LENGTH) {
                fastPathLookup(cleanedNumber)?.let { return it }
            }

            // The provider's own normalization is limited, so a fast-path miss is not
            // definitive: normalize both sides to E.164 and match against the full
            // contact scan (handles trunk prefix / country code / leading zero variants).
            val e164 = toE164(phoneNumber) ?: return null
            return contacts()?.get(e164)
        }

        // Batched variant of resolve: fast-path lookup per number, then a single contact
        // scan (shared by all misses) instead of one scan per number.
        fun resolveAll(phoneNumbers: Collection<String>): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val missed = mutableListOf<String>()
            for (number in phoneNumbers) {
                val cleanedNumber = clean(number)
                if (cleanedNumber.length < AppConfig.CONTACT_MIN_NUMBER_LENGTH) continue
                // Same prefix-match guard as resolve: short senders skip the fast path.
                val fastHit =
                    if (cleanedNumber.length >= AppConfig.CONTACT_FAST_PATH_MIN_LENGTH) {
                        fastPathLookup(cleanedNumber)
                    } else {
                        null
                    }
                if (fastHit != null) {
                    result[number] = fastHit
                } else {
                    missed.add(number)
                }
            }
            if (missed.isEmpty()) return result
            val lookup = contacts() ?: return result
            for (number in missed) {
                val e164 = toE164(number) ?: continue
                lookup[e164]?.let { result[number] = it }
            }
            return result
        }

        // The inverse of resolve: the numbers whose contact display name contains the
        // query. The name match runs in Kotlin (contains ignoreCase) because the
        // provider-side LIKE is ASCII-only in case folding and the app is i18n (e.g. Russian).
        // The numbers come back in the searchNumber facet form (toSearchNumber) so the
        // caller can compare them against the stored rows' searchNumber regardless of
        // formatting. An empty result means "no contact name match".
        fun resolveNumbersByName(query: String): List<String> {
            if (query.isEmpty()) return emptyList()
            return context
                .querySafely(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.Data.DISPLAY_NAME),
                    "${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                    null,
                )?.use { cursor ->
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIndex = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                    if (numberIndex < 0 || nameIndex < 0) return@use emptyList()
                    val result = mutableSetOf<String>()
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex) ?: continue
                        if (!name.contains(query, ignoreCase = true)) continue
                        val number = cursor.getString(numberIndex) ?: continue
                        val searchNumber = toSearchNumber(number)
                        if (searchNumber.isNotEmpty()) {
                            result.add(searchNumber)
                        }
                    }
                    result.toList()
                }.orEmpty()
        }

        // Provider lookup keyed on the number as stored: cheap indexed query, but it only
        // hits when the stored format is close to the given one.
        private fun fastPathLookup(cleanedNumber: String): String? {
            val uri =
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(cleanedNumber),
                )
            val projection =
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                )
            return context.querySafely(uri, projection)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }

        // E.164 -> display name index over all phone contact rows, so matching does not
        // depend on the format a number is stored in. Scanned at most once per TTL: the
        // scan is a full contacts query and contacts change rarely. Returns null (without
        // caching) when the query fails.
        private fun contacts(): Map<String, String>? {
            val now = System.currentTimeMillis()
            contactsCache?.let { cached ->
                if (now - contactsCacheAt < AppConfig.CONTACTS_CACHE_TTL_MS) return cached
            }
            val map = mutableMapOf<String, String>()
            val cursor =
                context.querySafely(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.Data.DISPLAY_NAME),
                    "${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                    null,
                ) ?: return null
            cursor.use {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = it.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                if (numberIndex < 0 || nameIndex < 0) return null
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: continue
                    val e164 = toE164(it.getString(numberIndex) ?: "") ?: continue
                    map.putIfAbsent(e164, name)
                }
            }
            contactsCache = map
            contactsCacheAt = now
            return map
        }

        // The searchNumber facet form of a raw number: E.164 when it normalizes (the
        // display path's key, so a name-matched contact compares equal to the stored
        // sender regardless of country-code / trunk-prefix / leading-zero formatting,
        // exactly like resolve), the digits-only fold otherwise. Shared by both sides of
        // the `searchNumber IN (...)` search (the stored facet via SmsRepository, the
        // name-matched contact numbers here) so the two sides cannot drift.
        fun toSearchNumber(number: String): String = toE164(number) ?: number.filter { it.isDigit() }

        // E.164 normalization of either side of a match (incoming or stored number) via
        // libphonenumber. The default region (the user's country code while set, the
        // detected SIM/network/locale region otherwise) disambiguates local numbers
        // without a country code. Unparseable or implausible numbers normalize to null.
        private fun toE164(number: String): String? {
            if (number.isBlank()) return null
            return try {
                val parsed = phoneNumberUtil.parse(number, defaultRegion())
                if (phoneNumberUtil.isPossibleNumber(parsed)) {
                    phoneNumberUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                } else {
                    null
                }
            } catch (_: NumberParseException) {
                null
            }
        }

        // The region normalization uses: the user's two-letter ISO code while auto is
        // off and the code is valid, the detected region otherwise. The cache is keyed on
        // the override plus the effective region, so a settings change or a re-detected
        // region re-resolves without re-running the telephony chain per number.
        private fun defaultRegion(): String {
            val key = overrideKey()
            val effective = if (key.isEmpty()) detectedRegionValue() else key
            regionCache?.let { (cachedKey, cachedEffective) ->
                if (cachedKey == key && cachedEffective == effective) return effective
            }
            // The scan's E.164 keys were built with the old region: a new override or a
            // re-detected region makes them stale, so the next resolve re-scans.
            if (regionCache != null) {
                contactsCache = null
            }
            regionCache = key to effective
            return effective
        }

        // The effective override as the cache key: "" while auto is on, the code
        // (uppercased) while it is a valid ISO country code, "" when blank or unknown
        // (an invalid code must degrade to the detected region, not break resolution).
        private fun overrideKey(): String {
            val settings = prefs.load()
            if (settings.autoCountry) return ""
            val code = settings.countryCode.trim().uppercase(Locale.ROOT)
            return if (code.isNotEmpty() && Locale.getISOCountries().contains(code)) code else ""
        }

        // The detected region behind the TTL cache: the chain result while fresh, a
        // re-resolve once the TTL has passed (a SIM swap / roam).
        private fun detectedRegionValue(): String {
            val now = System.currentTimeMillis()
            detectedRegionCache?.let { (region, at) ->
                if (now - at < detectedRegionTtlMs) return region
            }
            val region = resolveDefaultRegion()
            detectedRegionCache = region to now
            return region
        }

        // The detected region, the source after the user's manual override: the SIM
        // country ISO disambiguates local numbers without a country code, the network
        // country covers a SIM that reports none (no permission needed), the device
        // locale country is the last resort. Runs at most once per TTL window (never
        // per number): it normalizes every number and the SIM/network rarely change.
        private fun resolveDefaultRegion(): String {
            val telephony =
                runCatching {
                    context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                }.getOrNull()

            @Suppress("DEPRECATION")
            val simIso = telephony?.simCountryIso
            if (!simIso.isNullOrBlank()) return simIso.uppercase(Locale.ROOT)

            // Throws UnsupportedOperationException on devices without the telephony radio
            // feature; an empty result just falls through to the locale.
            val networkIso =
                telephony?.let { telephonyManager ->
                    runCatching { telephonyManager.networkCountryIso }.getOrNull()
                }
            if (!networkIso.isNullOrBlank()) return networkIso.uppercase(Locale.ROOT)

            // The device (system) locale list, not Locale.getDefault(): the app language
            // pins the JVM default to a language-only (e.g. "en") locale with an empty
            // country (LanguageHelper). The first entry carrying a country wins; "" when
            // none does.
            val systemLocales = Resources.getSystem().configuration.locales
            for (index in 0 until systemLocales.size()) {
                val country = systemLocales[index].country
                if (country.isNotBlank()) return country.uppercase(Locale.ROOT)
            }
            return ""
        }

        private fun clean(number: String): String = number.replace(NON_DIGIT_REGEX, "")

        companion object {
            private val NON_DIGIT_REGEX = Regex("[^0-9+]")
        }
    }
