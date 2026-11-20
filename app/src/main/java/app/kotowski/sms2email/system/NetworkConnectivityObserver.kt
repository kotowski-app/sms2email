package app.kotowski.sms2email.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.kotowski.sms2email.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

enum class NetworkStatus {
    Available,
    Unavailable,
}

@Singleton
class NetworkConnectivityObserver
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
    ) {
        private val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        private companion object {
            const val TAG = "NetworkConnectivityObserver"
        }

        val networkStatus: Flow<NetworkStatus> =
            callbackFlow {
                // Emits only a real evaluation: a throw means "no new information" and the
                // collector keeps its last known state instead of receiving a fabricated one.
                fun emitEvaluation() {
                    evaluateAllNetworks()?.let { trySend(it) }
                }

                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            emitEvaluation()
                        }

                        override fun onLost(network: Network) {
                            super.onLost(network)
                            emitEvaluation()
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) {
                            super.onCapabilitiesChanged(network, capabilities)
                            emitEvaluation()
                        }
                    }

                val request =
                    NetworkRequest
                        .Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()

                // A registration throw (OEM ROM quirk, connectivity service mid-restart)
                // must not escape the flow and crash the collector's process: log it and
                // keep collecting with no callback instead.
                var registered = false
                try {
                    connectivityManager.registerNetworkCallback(request, callback)
                    registered = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(
                        TAG,
                        "registerNetworkCallback failed, no live connectivity updates until the next screen open: ${e.javaClass.simpleName}: ${e.message}",
                        e,
                    )
                }

                emitEvaluation()

                awaitClose {
                    if (registered) {
                        try {
                            connectivityManager.unregisterNetworkCallback(callback)
                        } catch (e: Exception) {
                            Logger.w(
                                TAG,
                                "unregisterNetworkCallback failed, ignoring: ${e.javaClass.simpleName}: ${e.message}",
                                e,
                            )
                        }
                    }
                }
            }.distinctUntilChanged()

        private fun statusOf(capabilities: NetworkCapabilities): NetworkStatus =
            if (capabilities.isReliableInternet()) NetworkStatus.Available else NetworkStatus.Unavailable

        private fun evaluateNetwork(network: Network): NetworkStatus {
            val networkCapabilities =
                connectivityManager.getNetworkCapabilities(network)
                    ?: return NetworkStatus.Unavailable
            return statusOf(networkCapabilities)
        }

        // null = could not evaluate (the connectivity service threw, e.g. while
        // restarting): no new information, callers keep the last known state.
        // Deprecated, but the only API that also sees non-default networks.
        @Suppress("DEPRECATION")
        private fun evaluateAllNetworks(): NetworkStatus? =
            try {
                val anyAvailable =
                    connectivityManager.getAllNetworks().any { evaluateNetwork(it) == NetworkStatus.Available }
                if (anyAvailable) NetworkStatus.Available else NetworkStatus.Unavailable
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "evaluateAllNetworks failed, keeping the last known connectivity state")
                null
            }

        // Optimistic on failure (the UI default is "connected"): a service blip does not
        // mean the network is down, and a false "no internet" would otherwise be shown
        // for the whole screen lifetime.
        fun isConnectedCurrently(): Boolean = evaluateAllNetworks()?.let { it == NetworkStatus.Available } ?: true
    }
