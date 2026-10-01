package com.gala.motetv.discovery

import com.gala.motetv.core.model.TvDevice
import kotlinx.coroutines.flow.StateFlow

interface TvDiscoveryManager {
    val discoveredDevices: StateFlow<List<TvDevice>>
    val isDiscovering: StateFlow<Boolean>
    val lastError: StateFlow<String?>

    fun startDiscovery()
    fun stopDiscovery()
    fun addManualDevice(name: String, host: String, port: Int = TvDevice.DEFAULT_PAIRING_PORT): TvDevice
    fun clearDiscovered()
}
