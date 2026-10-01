package com.gala.motetv.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.gala.motetv.core.logging.TvLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isWifi = MutableStateFlow(false)
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun startMonitoring(onNetworkChanged: () -> Unit = {}) {
        val manager = connectivityManager ?: return

        updateCurrentState(manager)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                TvLogger.i(TvLogger.TAG_GENERAL, "Network became available")
                updateCurrentState(manager)
                onNetworkChanged()
            }

            override fun onLost(network: Network) {
                TvLogger.w(TvLogger.TAG_GENERAL, "Network lost")
                updateCurrentState(manager)
                onNetworkChanged()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                _isWifi.value = hasWifi
            }
        }

        networkCallback = callback
        try {
            manager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "Could not register network callback: ${e.message}")
        }
    }

    fun stopMonitoring() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                TvLogger.w(TvLogger.TAG_GENERAL, "Could not unregister network callback: ${e.message}")
            }
            networkCallback = null
        }
    }

    private fun updateCurrentState(manager: ConnectivityManager) {
        val activeNetwork = manager.activeNetwork
        val capabilities = activeNetwork?.let { manager.getNetworkCapabilities(it) }
        val connected = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wifi = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        _isConnected.value = connected
        _isWifi.value = wifi
    }
}
