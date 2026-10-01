package com.gala.motetv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class NsdTvDiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : TvDiscoveryManager {

    private val nsdManager: NsdManager? by lazy {
        try {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_DISCOVERY, "Failed to get NsdManager", e)
            null
        }
    }

    private val wifiManager: WifiManager? by lazy {
        try {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (e: Exception) {
            null
        }
    }

    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<TvDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, TvDevice>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile
    private var isResolving = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun startDiscovery() {
        if (_isDiscovering.value) {
            TvLogger.d(TvLogger.TAG_DISCOVERY, "Discovery already running")
            return
        }

        val manager = nsdManager
        if (manager == null) {
            val err = "NsdManager not available on this device"
            TvLogger.e(TvLogger.TAG_DISCOVERY, err)
            _lastError.value = err
            return
        }

        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                TvLogger.i(TvLogger.TAG_DISCOVERY, "mDNS discovery started for type: $regType")
                _isDiscovering.value = true
                _lastError.value = null
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                TvLogger.i(
                    TvLogger.TAG_DISCOVERY,
                    "Discovered mDNS service candidate: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}"
                )
                queueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                TvLogger.w(TvLogger.TAG_DISCOVERY, "mDNS service lost: ${serviceInfo.serviceName}")
                val removed = deviceMap.remove(serviceInfo.serviceName)
                if (removed != null) {
                    updateDevicesList()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                TvLogger.i(TvLogger.TAG_DISCOVERY, "mDNS discovery stopped for type: $serviceType")
                _isDiscovering.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                val err = "Start discovery failed for $serviceType (code $errorCode)"
                TvLogger.e(TvLogger.TAG_DISCOVERY, err)
                _lastError.value = err
                _isDiscovering.value = false
                safeStopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                val err = "Stop discovery failed for $serviceType (code $errorCode)"
                TvLogger.e(TvLogger.TAG_DISCOVERY, err)
                _lastError.value = err
                _isDiscovering.value = false
            }
        }

        discoveryListener = listener

        try {
            manager.discoverServices(
                TvDevice.SERVICE_TYPE_V2,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_DISCOVERY, "Exception starting mDNS discovery", e)
            _lastError.value = "Discovery error: ${e.message}"
            _isDiscovering.value = false
            releaseMulticastLock()
        }
    }

    override fun stopDiscovery() {
        safeStopDiscovery()
    }

    private fun safeStopDiscovery() {
        val listener = discoveryListener
        if (listener != null) {
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                TvLogger.w(TvLogger.TAG_DISCOVERY, "Exception stopping discovery: ${e.message}")
            }
            discoveryListener = null
        }
        _isDiscovering.value = false
        resolveQueue.clear()
        isResolving = false
        releaseMulticastLock()
    }

    private fun queueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.add(serviceInfo)
        processNextResolve()
    }

    @Synchronized
    private fun processNextResolve() {
        if (isResolving) return
        val nextService = resolveQueue.poll() ?: return

        val manager = nsdManager ?: return
        isResolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                TvLogger.w(
                    TvLogger.TAG_DISCOVERY,
                    "Resolve failed for ${serviceInfo.serviceName} (error code: $errorCode)"
                )
                isResolving = false
                processNextResolve()
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                isResolving = false
                val hostAddress = resolvedInfo.host?.hostAddress
                val port = resolvedInfo.port
                val serviceName = resolvedInfo.serviceName

                if (hostAddress != null && hostAddress.isNotEmpty()) {
                    var model = "Android TV"
                    var manufacturer = "Generic"
                    
                    // Parse TXT records if available
                    try {
                        val attributes = resolvedInfo.attributes
                        if (attributes != null && attributes.isNotEmpty()) {
                            attributes["model"]?.let { model = String(it) }
                            attributes["manufacturer"]?.let { manufacturer = String(it) }
                            attributes["fn"]?.let { friendlyName ->
                                model = String(friendlyName)
                            }
                        }
                    } catch (_: Throwable) {
                        // attributes accessor may throw on older APIs
                    }

                    // Infer Xiaomi / Google TV from name if not in TXT records
                    if (serviceName.contains("Mi", ignoreCase = true) || serviceName.contains("Xiaomi", ignoreCase = true)) {
                        manufacturer = "Xiaomi"
                        model = "Mi TV Stick"
                    }

                    val device = TvDevice(
                        id = serviceName,
                        name = cleanDeviceName(serviceName),
                        host = hostAddress,
                        pairingPort = TvDevice.DEFAULT_PAIRING_PORT,
                        remotePort = TvDevice.DEFAULT_REMOTE_PORT,
                        manufacturer = manufacturer,
                        model = model,
                        serviceName = serviceName,
                        isPaired = false,
                        lastSeen = System.currentTimeMillis()
                    )

                    TvLogger.i(
                        TvLogger.TAG_DISCOVERY,
                        "Device Resolved: ${device.name} @ $hostAddress (Pairing: ${device.pairingPort}, Remote: ${device.remotePort})"
                    )

                    deviceMap[serviceName] = device
                    updateDevicesList()
                }

                processNextResolve()
            }
        }

        try {
            manager.resolveService(nextService, resolveListener)
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_DISCOVERY, "Failed to initiate resolveService", e)
            isResolving = false
            processNextResolve()
        }
    }

    override fun addManualDevice(name: String, host: String, port: Int): TvDevice {
        val trimmedHost = host.trim()
        val cleanName = if (name.isBlank()) "Android TV ($trimmedHost)" else name.trim()
        val manualId = "manual_$trimmedHost"

        val device = TvDevice(
            id = manualId,
            name = cleanName,
            host = trimmedHost,
            pairingPort = TvDevice.DEFAULT_PAIRING_PORT,
            remotePort = TvDevice.DEFAULT_REMOTE_PORT,
            manufacturer = "Manual",
            model = "Android TV",
            serviceName = "androidtv-remote",
            isPaired = false,
            lastSeen = System.currentTimeMillis()
        )

        deviceMap[manualId] = device
        TvLogger.i(TvLogger.TAG_DISCOVERY, "Added manual device: $cleanName @ $trimmedHost")
        updateDevicesList()
        return device
    }

    override fun clearDiscovered() {
        deviceMap.clear()
        updateDevicesList()
    }

    private fun updateDevicesList() {
        _discoveredDevices.value = deviceMap.values.sortedByDescending { it.lastSeen }
    }

    private fun cleanDeviceName(rawName: String): String {
        return rawName.replace(Regex("[_\\-]+"), " ").trim()
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("MoteTvDiscoveryLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
            TvLogger.d(TvLogger.TAG_DISCOVERY, "Acquired WiFi MulticastLock")
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_DISCOVERY, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                TvLogger.d(TvLogger.TAG_DISCOVERY, "Released WiFi MulticastLock")
            }
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_DISCOVERY, "Could not release MulticastLock: ${e.message}")
        }
    }
}
