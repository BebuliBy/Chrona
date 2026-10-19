package by.bebuliby.chrona.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** Observe only Android's DEFAULT route, including a full VPN. Never binds/excludes/selects a network. */
class DefaultNetworkObserver(context: Context, private val stateChanged: (Boolean) -> Unit) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var current: Network? = null
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = available(network)
        override fun onLost(network: Network) = lost(network)
        // VALIDATED is Android's probe result, not reachability of our API through a VPN.
    }
    @Synchronized private fun available(network: Network) {
        if (current == network) return
        current = network; stateChanged(true)
    }
    @Synchronized private fun lost(network: Network) {
        if (current != network) return
        current = null; stateChanged(false) // Hint only. Let bounded HTTP requests finish on their own.
    }
    @Synchronized fun start() {
        if (registered) return
        try { manager.registerDefaultNetworkCallback(callback); registered = true }
        catch (_: SecurityException) { /* Existing timed controller/WorkManager remain; no unsafe route fallback. */ }
    }
}
