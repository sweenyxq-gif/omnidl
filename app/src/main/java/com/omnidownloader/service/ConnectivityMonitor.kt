package com.omnidownloader.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ConnectivityState(val connected: Boolean, val wifi: Boolean, val metered: Boolean)

@Singleton
class ConnectivityMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    val state: Flow<ConnectivityState> = callbackFlow {
        fun publish() {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            trySend(ConnectivityState(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                manager.isActiveNetworkMetered))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish()
        }
        manager.registerDefaultNetworkCallback(callback); publish()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
}
