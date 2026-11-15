package com.asdfg.soundmaster.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable

/** Tracks actual Wi-Fi networks, including Wi-Fi without validated internet and behind a VPN. */
internal class AdbNetworkObserver(context: Context, private val changed: (Boolean) -> Unit) : Closeable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val networks = mutableSetOf<Network>()
    private var closed = false
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network, true)
        override fun onLost(network: Network) = update(network, false)
    }
    init {
        if (manager == null) changed(true) // bounded mDNS/manual fallback on unusual ROMs
        else try {
            synchronized(networks) {
                networks.addAll(manager.allNetworks.filter {
                    manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                })
                manager.registerNetworkCallback(NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
                registered = true
            }
            changed(synchronized(networks) { networks.isNotEmpty() })
        } catch (_: Exception) { changed(true) }
    }
    private fun update(network: Network, available: Boolean) {
        val ready = synchronized(networks) {
            if (closed) return
            if (available) networks.add(network) else networks.remove(network)
            networks.isNotEmpty()
        }
        changed(ready) // never call application code while holding the network-set lock
    }
    override fun close() = synchronized(networks) {
        closed = true
        if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
        networks.clear()
    }
}
