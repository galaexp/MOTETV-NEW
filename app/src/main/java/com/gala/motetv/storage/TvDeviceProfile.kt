package com.gala.motetv.storage

data class TvDeviceProfile(
    val id: String,
    val name: String,
    val host: String,
    val pairingPort: Int = 6467,
    val remotePort: Int = 6466,
    val serviceName: String = "androidtv-remote",
    val roomLabel: String = "Living Room",
    val isDefault: Boolean = false,
    val isPaired: Boolean = false,
    val lastConnectedTimestamp: Long = 0L,
    val manufacturer: String = "Android TV",
    val model: String = "TV Device"
)
