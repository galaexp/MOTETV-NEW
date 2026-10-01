package com.gala.motetv.core.model

data class TvDevice(
    val id: String,
    val name: String,
    val host: String,
    val pairingPort: Int = DEFAULT_PAIRING_PORT,
    val remotePort: Int = DEFAULT_REMOTE_PORT,
    val manufacturer: String = "Generic",
    val model: String = "Android TV",
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val isPaired: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_PAIRING_PORT = 6467
        const val DEFAULT_REMOTE_PORT = 6466
        const val DEFAULT_SERVICE_NAME = "androidtv-remote"
        const val SERVICE_TYPE_V2 = "_androidtvremote2._tcp."
    }
}

enum class DeviceStatus {
    Available,
    Connecting,
    PairingRequired,
    Connected,
    Offline,
    Paired
}
