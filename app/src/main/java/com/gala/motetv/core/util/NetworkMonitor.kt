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

data class NetworkEvaluationResult(
    val accepted: Boolean,
    val isConnected: Boolean,
    val isWifi: Boolean,
    val triggerAvailable: Boolean = false,
    val triggerLost: Boolean = false,
    val triggerChanged: Boolean = false
)

/**
 * Pure, testable network state tracker that verifies active network identity and ignores
 * irrelevant secondary/non-active network callbacks.
 */
class ActiveNetworkTracker<T>(
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = 500L
) {
    var trackedActiveNetwork: T? = null
        private set
    var isConnected: Boolean = false
        private set
    var isWifi: Boolean = false
        private set
    private var lastCallbackTimeMs: Long = 0L

    fun syncFromSystem(activeNetwork: T?, hasInternet: Boolean, hasWifi: Boolean) {
        trackedActiveNetwork = activeNetwork
        isConnected = activeNetwork != null && hasInternet
        isWifi = activeNetwork != null && hasWifi
    }

    fun onAvailable(
        network: T,
        systemActiveNetwork: T?,
        hasInternet: Boolean,
        hasWifi: Boolean
    ): NetworkEvaluationResult {
        val isActiveNetwork = (systemActiveNetwork == null) || (network == systemActiveNetwork)
        if (!isActiveNetwork || !hasInternet) {
            return NetworkEvaluationResult(accepted = false, isConnected = isConnected, isWifi = isWifi)
        }

        val now = timeProvider()
        val isSameNetwork = network == trackedActiveNetwork
        if (isSameNetwork && (now - lastCallbackTimeMs < debounceMs)) {
            return NetworkEvaluationResult(accepted = false, isConnected = isConnected, isWifi = isWifi)
        }

        lastCallbackTimeMs = now
        trackedActiveNetwork = systemActiveNetwork ?: network
        isConnected = true
        isWifi = hasWifi

        return NetworkEvaluationResult(
            accepted = true,
            isConnected = isConnected,
            isWifi = isWifi,
            triggerAvailable = true,
            triggerChanged = true
        )
    }

    fun onLost(
        network: T,
        systemActiveNetwork: T?,
        systemHasInternet: Boolean,
        systemHasWifi: Boolean
    ): NetworkEvaluationResult {
        val wasActive = (network == trackedActiveNetwork) || (systemActiveNetwork == null)
        if (!wasActive) {
            return NetworkEvaluationResult(accepted = false, isConnected = isConnected, isWifi = isWifi)
        }

        trackedActiveNetwork = systemActiveNetwork
        isConnected = systemActiveNetwork != null && systemHasInternet
        isWifi = systemActiveNetwork != null && systemHasWifi

        return NetworkEvaluationResult(
            accepted = true,
            isConnected = isConnected,
            isWifi = isWifi,
            triggerLost = !isConnected,
            triggerAvailable = isConnected,
            triggerChanged = true
        )
    }

    fun onCapabilitiesChanged(
        network: T,
        systemActiveNetwork: T?,
        hasInternet: Boolean,
        hasWifi: Boolean
    ): NetworkEvaluationResult {
        val isActiveNetwork = (network == systemActiveNetwork) ||
            (systemActiveNetwork == null && network == trackedActiveNetwork)
        if (!isActiveNetwork) {
            return NetworkEvaluationResult(accepted = false, isConnected = isConnected, isWifi = isWifi)
        }

        val wasConnected = isConnected
        val wasWifi = isWifi
        trackedActiveNetwork = systemActiveNetwork ?: network
        isConnected = hasInternet
        isWifi = hasWifi

        val lostConnectivity = wasConnected && !isConnected
        val gainedConnectivity = !wasConnected && isConnected
        val switchedTransport = isConnected && (wasWifi != isWifi)

        return NetworkEvaluationResult(
            accepted = true,
            isConnected = isConnected,
            isWifi = isWifi,
            triggerLost = lostConnectivity,
            triggerAvailable = gainedConnectivity || switchedTransport,
            triggerChanged = (wasConnected != isConnected) || (wasWifi != isWifi)
        )
    }
}

class NetworkMonitor(context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val tracker = ActiveNetworkTracker<Network>()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isWifi = MutableStateFlow(false)
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun startMonitoring(
        onNetworkAvailable: (isWifi: Boolean) -> Unit = {},
        onNetworkLost: () -> Unit = {},
        onNetworkChanged: () -> Unit = {}
    ) {
        val manager = connectivityManager ?: return

        syncCurrentState(manager)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val active = manager.activeNetwork
                val caps = manager.getNetworkCapabilities(active ?: network)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

                val result = tracker.onAvailable(network, active, hasInternet, hasWifi)
                if (!result.accepted) {
                    TvLogger.d(TvLogger.TAG_GENERAL, "Ignoring secondary/non-active network available ($network)")
                    return
                }

                publishResult(result, onNetworkAvailable, onNetworkLost, onNetworkChanged, "available ($network)")
            }

            override fun onLost(network: Network) {
                val active = manager.activeNetwork
                val caps = active?.let { manager.getNetworkCapabilities(it) }
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

                val result = tracker.onLost(network, active, hasInternet, hasWifi)
                if (!result.accepted) {
                    TvLogger.d(TvLogger.TAG_GENERAL, "Ignoring lost secondary network ($network)")
                    return
                }

                TvLogger.w(TvLogger.TAG_GENERAL, "Active network lost: $network (remainingConnected=${result.isConnected})")
                publishResult(result, onNetworkAvailable, onNetworkLost, onNetworkChanged, "lost ($network)")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val active = manager.activeNetwork
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

                val result = tracker.onCapabilitiesChanged(network, active, hasInternet, hasWifi)
                if (!result.accepted) {
                    TvLogger.d(TvLogger.TAG_GENERAL, "Ignoring capabilities change on secondary network ($network)")
                    return
                }

                publishResult(result, onNetworkAvailable, onNetworkLost, onNetworkChanged, "capabilitiesChanged ($network)")
            }
        }

        networkCallback = callback
        try {
            manager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "Could not register network callback: ${e.message}")
        }
    }

    private fun publishResult(
        result: NetworkEvaluationResult,
        onNetworkAvailable: (isWifi: Boolean) -> Unit,
        onNetworkLost: () -> Unit,
        onNetworkChanged: () -> Unit,
        source: String
    ) {
        _isConnected.value = result.isConnected
        _isWifi.value = result.isWifi

        if (result.triggerLost) {
            TvLogger.w(TvLogger.TAG_GENERAL, "Active network disconnected [$source]")
            onNetworkLost()
        }
        if (result.triggerAvailable) {
            TvLogger.i(TvLogger.TAG_GENERAL, "Active network ready (Wi-Fi=${result.isWifi}) [$source]")
            onNetworkAvailable(result.isWifi)
        }
        if (result.triggerChanged) {
            onNetworkChanged()
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

    private fun syncCurrentState(manager: ConnectivityManager) {
        val activeNetwork = manager.activeNetwork
        val capabilities = activeNetwork?.let { manager.getNetworkCapabilities(it) }
        val connected = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wifi = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        tracker.syncFromSystem(activeNetwork, connected, wifi)
        _isConnected.value = tracker.isConnected
        _isWifi.value = tracker.isWifi
    }
}
