package mv.muraka.core.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import mv.muraka.core.domain.NetworkMonitor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the device believes it has a route to the internet.
 *
 * Used to *decide when to try*, never to decide what to tell the contributor. A phone
 * reporting a connection to a resort captive portal will still fail every request, so the
 * queue's own state — not this flow — is what the sync screen shows.
 *
 * `NET_CAPABILITY_VALIDATED` rather than merely `INTERNET`: the former means Android
 * actually reached something, which on a boat's marina Wi-Fi is a materially different
 * claim from "associated with an access point".
 */
@Singleton
class NetworkMonitorImpl @Inject constructor(@param:ApplicationContext private val context: Context) : NetworkMonitor {

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            // No connectivity service at all is not something to crash over; assume
            // online and let the requests themselves be the judge.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            private val validated = mutableSetOf<Network>()

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    validated += network
                } else {
                    validated -= network
                }
                trySend(validated.isNotEmpty())
            }

            override fun onLost(network: Network) {
                validated -= network
                trySend(validated.isNotEmpty())
            }
        }

        manager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
        )

        trySend(manager.isCurrentlyValidated())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().conflate()

    private fun ConnectivityManager.isCurrentlyValidated(): Boolean = getNetworkCapabilities(activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}
