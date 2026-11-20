package app.kotowski.sms2email.system

import android.content.Context
import android.telephony.SubscriptionManager
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.R
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimNameResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val permissionManager: PermissionManager,
    ) {
        // Volatile: the singleton is hit from several threads (worker, view-model scopes);
        // without it a concurrent reader could tear the (map, time) cache pair.
        @Volatile
        private var cachedSlotMap: Map<Int, SlotInfo>? = null

        @Volatile
        private var slotCacheTime: Long = 0

        @Volatile
        private var cachedPhonePermission: Boolean? = null
        private val cacheTtl: Long = AppConfig.SIM_NAME_CACHE_TTL_MS

        private data class SlotInfo(
            val slotIndex: Int,
            val displayName: String?,
        )

        data class ActiveSim(
            val slot: Int,
            val displayName: String,
        )

        // The 1-based tray slot number of the subscription (the same number
        // getActiveSims reports): the message filter's slot facet matches its rules
        // against this. Null when the id is not in the current map (permission
        // denied, the SIM since removed), 0 for a SIM without a tray slot (eSIM;
        // never in 1..3, so a slot rule never hits it).
        fun getSlotFor(subscriptionId: Int): Int? = getSlotMap()[subscriptionId]?.let { it.slotIndex + 1 }

        fun getSimDisplayNameSync(subscriptionId: Int): String {
            val slotMap = getSlotMap()
            val info = slotMap[subscriptionId]
            return info?.displayName
                ?: context.getString(R.string.history_unknown_sim)
        }

        // The device's SIM slots as (subscriptionId, display name) pairs: the search's
        // SIM-name facet matches the query against the names and passes the ids to SQL.
        // displayName is null for a slot without a name (the caller skips it).
        fun getSlotEntries(): List<Pair<Int, String?>> =
            getSlotMap()
                .map { (subscriptionId, info) -> subscriptionId to info.displayName }

        // The device's active SIMs in tray order for the dashboard's SIM widget: the 1-based
        // tray slot (the slot badge icons are numbered 1..3) and the display name (the
        // unknown label when the SIM has none). The platform reports slot index -1 for a
        // SIM without a tray slot; in practice this only happens for automotive remote
        // SIMs (a regular SIM or an eSIM always carries a slot while active). Defensively,
        // such rows sort last and map to slot 0, which renders badge-less.
        fun getActiveSims(): List<ActiveSim> {
            val slotMap = getSlotMap()
            if (slotMap.isEmpty()) {
                return emptyList()
            }
            val unknownSim = context.getString(R.string.history_unknown_sim)
            return slotMap.values
                .sortedBy { if (it.slotIndex < 0) Int.MAX_VALUE else it.slotIndex }
                .map { info ->
                    ActiveSim(
                        slot = info.slotIndex + 1,
                        displayName = info.displayName ?: unknownSim,
                    )
                }
        }

        // Drops the cached slot map so the next read re-queries SubscriptionManager. Driven
        // by SimChangeObserver (a subscription change, or its 60 s fallback tick) so a SIM
        // rename/swap shows up without navigating away. The permission is always re-checked
        // in getSlotMap, so only the map + timestamp need resetting.
        fun invalidate() {
            cachedSlotMap = null
            slotCacheTime = 0
        }

        private fun getSlotMap(): Map<Int, SlotInfo> {
            val hasPhonePermission = permissionManager.isGranted(permissionManager.phonePermission)

            if (cachedPhonePermission != hasPhonePermission) {
                cachedSlotMap = null
                slotCacheTime = 0
                cachedPhonePermission = hasPhonePermission
            }

            val now = System.currentTimeMillis()
            // Snapshot once: the check and the use must read the same reference, as a
            // concurrent permission flip may null or replace the field in between.
            val cachedMap = cachedSlotMap
            if (cachedMap != null && (now - slotCacheTime) < cacheTtl) {
                return cachedMap
            }

            val slotMap =
                if (hasPhonePermission) {
                    try {
                        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
                        val activeSubscriptions = subscriptionManager?.activeSubscriptionInfoList
                        if (!activeSubscriptions.isNullOrEmpty()) {
                            activeSubscriptions.associate { info ->
                                info.subscriptionId to
                                    SlotInfo(
                                        slotIndex = info.simSlotIndex,
                                        displayName = info.displayName?.toString(),
                                    )
                            }
                        } else {
                            emptyMap()
                        }
                    } catch (e: SecurityException) {
                        Logger.w("SimNameResolver", "getSlotMap: SecurityException accessing SubscriptionManager: ${e.message}")
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }

            cachedSlotMap = slotMap
            slotCacheTime = now
            return slotMap
        }
    }
