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
    private var lastActiveNetwork: Network? = null
    private var lastCallbackTimeMs: Long = 0L

    fun startMonitoring(
        onNetworkAvailable: (isWifi: Boolean) -> Unit = {},
        onNetworkLost: () -> Unit = {},
        onNetworkChanged: () -> Unit = {}
    ) {
        val manager = connectivityManager ?: return

        updateCurrentState(manager)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val now = System.currentTimeMillis()
                // Debounce rapid callbacks (500ms) unless the active network physically changed
                val isSameNetwork = network == lastActiveNetwork
                if (isSameNetwork && (now - lastCallbackTimeMs < 500)) {
                    return
                }
                lastCallbackTimeMs = now
                lastActiveNetwork = network

                updateCurrentState(manager)
                val wifi = _isWifi.value
                TvLogger.i(TvLogger.TAG_GENERAL, "Network became available (Wi-Fi=$wifi, netId=$network)")
                onNetworkAvailable(wifi)
                onNetworkChanged()
            }

            override fun onLost(network: Network) {
                val wasActive = (network == lastActiveNetwork) || (manager.activeNetwork == null)
                TvLogger.w(TvLogger.TAG_GENERAL, "Network lost: $network (wasActive=$wasActive)")
                if (wasActive) {
                    lastActiveNetwork = null
                    updateCurrentState(manager)
                    onNetworkLost()
                    onNetworkChanged()
                } else {
                    updateCurrentState(manager)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                _isWifi.value = hasWifi
                _isConnected.value = hasInternet
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
        lastActiveNetwork = activeNetwork
        val capabilities = activeNetwork?.let { manager.getNetworkCapabilities(it) }
        val connected = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wifi = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        _isConnected.value = connected
        _isWifi.value = wifi
    }
}
