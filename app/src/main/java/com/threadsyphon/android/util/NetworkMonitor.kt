package com.threadsyphon.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class NetworkMonitor(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isWifiConnected(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun hasValidatedInternet(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun downloadsAllowed(wifiOnly: Boolean, allowMobileData: Boolean): Boolean {
        if (!hasValidatedInternet()) return false
        if (allowMobileData || !wifiOnly) return true
        return isWifiConnected()
    }

    fun wifiOnlyFlow(): Flow<Boolean> = callbackFlow {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isWifiConnected()) }
            override fun onLost(network: Network) { trySend(isWifiConnected()) }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(isWifiConnected())
            }
        }
        cm.registerNetworkCallback(request, callback)
        trySend(isWifiConnected())
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    companion object {
        fun mayDownload(context: Context, allowMobileData: Boolean): Boolean {
            val monitor = NetworkMonitor(context)
            return monitor.downloadsAllowed(wifiOnly = !allowMobileData, allowMobileData = allowMobileData)
        }
    }
}
