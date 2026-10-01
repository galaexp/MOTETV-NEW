package com.gala.motetv.core.model

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Discovering : ConnectionState()
    data class Connecting(val host: String, val port: Int) : ConnectionState()
    data class Pairing(val stage: String) : ConnectionState()
    data object Authenticating : ConnectionState()
    data class Ready(val device: TvDevice) : ConnectionState()
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : ConnectionState()
    data class Failed(val errorCode: String, val userMessage: String, val technicalDetail: String? = null) : ConnectionState()
}
