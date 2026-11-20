package app.kotowski.sms2email.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.util.AndroidVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// A push-based "the SIM list changed" signal: an OnSubscriptionsChangedListener fires on
// insert/remove/swap/rename, plus a 60 s fallback tick in case a change is missed. Each
// event first invalidates the SimNameResolver cache (so the next read is fresh) and then
// emits. The flow is cold (callbackFlow): the listener and the tick exist only while a
// screen is collecting it (its view-model scope), so there is no background polling — a
// background send reads the resolver once through its own 60 s TTL cache instead.
@Singleton
class SimChangeObserver
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val simNameResolver: SimNameResolver,
    ) {
        private val subscriptionManager: SubscriptionManager? =
            appContext.getSystemService(SubscriptionManager::class.java)

        // The fallback poll interval; a test seam (like SimDetector's resettable cache) so
        // the tick can be exercised without waiting the real 60 s.
        internal var pollIntervalMs: Long = AppConfig.SIM_NAME_CACHE_TTL_MS

        val simChanges: Flow<Unit> =
            callbackFlow {
                val listener =
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            simNameResolver.invalidate()
                            trySend(Unit)
                        }
                    }
                registerListener(listener)
                // First collect: force a fresh read so a change that landed while no screen
                // was open is reflected the moment one opens.
                simNameResolver.invalidate()
                trySend(Unit)
                launch {
                    while (isActive) {
                        delay(pollIntervalMs)
                        simNameResolver.invalidate()
                        trySend(Unit)
                    }
                }
                awaitClose {
                    unregisterListener(listener)
                }
            }

        // API 30+ exposes the Executor overload; below it the listener must be
        // registered on a thread with a prepared Looper, so it is registered on the
        // main thread there. The callback is trivial (invalidate + emit), so a
        // main-thread delivery is fine.
        private fun registerListener(listener: SubscriptionManager.OnSubscriptionsChangedListener) {
            val manager = subscriptionManager ?: return
            if (AndroidVersion.is11Plus()) {
                manager.addOnSubscriptionsChangedListener(Dispatchers.IO.asExecutor(), listener)
            } else {
                // Only runs below API 30, where the overload is not deprecated, so the
                // deprecated call is unreachable; the suppress silences the compile warning.
                @Suppress("DEPRECATION")
                Handler(Looper.getMainLooper()).post { manager.addOnSubscriptionsChangedListener(listener) }
            }
        }

        // removeOnSubscriptionsChangedListener has no looper requirement, but on API < 30 the
        // registration is posted to the main looper (see registerListener); posting the removal
        // there too keeps the add/remove pair in order on the same looper, so closing the screen
        // within the registration post window still removes the listener (no leaked listener).
        private fun unregisterListener(listener: SubscriptionManager.OnSubscriptionsChangedListener) {
            val manager = subscriptionManager ?: return
            if (AndroidVersion.is11Plus()) {
                manager.removeOnSubscriptionsChangedListener(listener)
            } else {
                Handler(Looper.getMainLooper()).post { manager.removeOnSubscriptionsChangedListener(listener) }
            }
        }
    }
