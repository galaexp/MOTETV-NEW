package com.gala.motetv.remote

/**
 * Diagnostic snapshot of the live Android TV Remote connection.
 */
data class ConnectionDiagnostics(
    val selectedDeviceName: String? = null,
    val selectedDeviceHost: String? = null,
    val connectionStateName: String = "Disconnected",
    val sessionId: Long = 0L,
    val reconnectAttemptCount: Int = 0,
    val coalescedReconnectCount: Int = 0,
    val lastSuccessfulConnectTimeMs: Long = 0L,
    val lastReceivedResponseTimeMs: Long = 0L,
    val lastOutboundFrameTimeMs: Long = 0L,
    val lastDisconnectReason: String = "None",
    val recentReconnectReasons: List<String> = emptyList(),
    val pendingCommandCount: Int = 0,
    val isPingOutstanding: Boolean = false,
    val lastPingSentTimeMs: Long = 0L,
    val droppedCommandCount: Int = 0,
    val evictedCommandCount: Int = 0,
    val lastQueueExpirationTimeMs: Long = 0L,
    val uncertainDeliveryCount: Int = 0,
    val lastUncertainCommand: String? = null
)
