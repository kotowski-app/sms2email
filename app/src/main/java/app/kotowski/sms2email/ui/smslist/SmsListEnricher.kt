package app.kotowski.sms2email.ui.smslist

import android.content.Context
import app.kotowski.sms2email.R
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.SimNameResolver

internal class SmsListEnricher(
    private val appContext: Context,
    private val prefs: EncryptedPrefs,
    private val contactResolver: ContactResolver,
    private val simNameResolver: SimNameResolver,
) {
    // Contact names are per number and a DB re-emit (a status flip) does not change the
    // window's number set: resolve once per distinct set, not once per emit. A single
    // volatile (set, map) pair — never torn — because the window and the contact-refresh
    // collectors can re-resolve concurrently.
    @Volatile
    private var cachedContacts: Pair<Set<String>, Map<String, String>>? = null

    // No enricher-level cache: a renamed SIM (Settings, carrier profile) must show up,
    // staleness is bounded by the resolver's own 60 s TTL. The keys are subscription
    // ids, not tray slots: sub id 0 is valid (the first SIM ever inserted), -1 is the
    // only absent marker.
    fun buildSimNames(smsList: List<SmsUiModel>): Map<Int, String> {
        if (!prefs.load().displaySimCards) return emptyMap()
        val uniqueSubIds = smsList.filter { it.simId >= 0 }.map { it.simId }.toSet()
        val subNames = mutableMapOf<Int, String>()
        val unknownSim = appContext.getString(R.string.history_unknown_sim)
        for (subId in uniqueSubIds) {
            try {
                subNames[subId] = simNameResolver.getSimDisplayNameSync(subId)
            } catch (e: Exception) {
                subNames[subId] = unknownSim
            }
        }
        return subNames
    }

    // Sub id -> 1-based tray slot. A missing SIM has no entry (slots are known only
    // while a sub is active); 0 (a SIM without a tray slot) is dropped like a missing.
    fun buildSimSlots(smsList: List<SmsUiModel>): Map<Int, Int> {
        if (!prefs.load().displaySimCards) return emptyMap()
        val uniqueSubIds = smsList.filter { it.simId >= 0 }.map { it.simId }.toSet()
        val subSlots = mutableMapOf<Int, Int>()
        for (subId in uniqueSubIds) {
            simNameResolver.getSlotFor(subId)?.takeIf { it >= 1 }?.let { subSlots[subId] = it }
        }
        return subSlots
    }

    suspend fun resolveAllContacts(smsList: List<SmsUiModel>): Map<String, String> {
        if (!prefs.load().useContactResolution) return emptyMap()
        if (smsList.isEmpty()) return emptyMap()
        val uniqueNumbers = smsList.map { it.phoneNumber }.distinct()
        val numberSet = uniqueNumbers.toSet()
        cachedContacts?.let { (cachedSet, names) ->
            if (cachedSet == numberSet) return names
        }
        val contactMap =
            try {
                contactResolver.resolveAll(uniqueNumbers)
            } catch (_: Exception) {
                emptyMap()
            }
        cachedContacts = numberSet to contactMap
        return contactMap
    }

    // Drops the cached window names so the next resolveAllContacts re-resolves even when
    // the number set is unchanged. Driven by the list controller's contact-refresh
    // collector (a contact change, or its 60 s fallback tick).
    fun invalidate() {
        cachedContacts = null
    }

    // The single-row variant for the detail screen: same policy as resolveAllContacts
    // (prefs gate, fail-open), but no cache — it must not touch the window's cached
    // set, a one-number set would evict it.
    suspend fun resolveContact(phoneNumber: String): String? {
        if (!prefs.load().useContactResolution) return null
        return try {
            contactResolver.resolve(phoneNumber)
        } catch (_: Exception) {
            null
        }
    }
}
