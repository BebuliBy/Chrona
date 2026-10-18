package by.bebuliby.chrona.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Observe only Android's DEFAULT route, including a full VPN. Never binds/excludes/selects a network. */
class DefaultNetworkObserver(context: Context, private val abortOldSockets: () -> Unit,
                             private val stateChanged: (Boolean) -> Unit) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var current: Network? = null
    private var seen = false
    private var registered = false
    private var usable: Boolean? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = available(network)
        override fun onLost(network: Network) = lost(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(this@DefaultNetworkObserver) {
                if (current == network) {
                    val next = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (usable != next) { usable = next; stateChanged(next) }
                }
            }
        }
    }
    @Synchronized private fun available(network: Network) {
        if (current == network) return
        if (seen) abortOldSockets()
        seen = true; current = network; usable = true; stateChanged(true)
    }
    @Synchronized private fun lost(network: Network) {
        if (current != network) return
        current = null; usable = false; abortOldSockets(); stateChanged(false)
    }
    @Synchronized fun start() {
        if (registered) return
        try { manager.registerDefaultNetworkCallback(callback); registered = true }
        catch (_: SecurityException) { /* Existing timed controller/WorkManager remain; no unsafe route fallback. */ }
    }
}
