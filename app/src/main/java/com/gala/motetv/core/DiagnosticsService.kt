package com.gala.motetv.core

import android.content.Context
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.mirror.ScreenMirrorServer
import com.gala.motetv.storage.TvCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.regex.Pattern

enum class DiagnosticStatus {
    PASSED, FAILED, NOT_AVAILABLE
}

data class DiagnosticItem(
    val id: String,
    val name: String,
    val status: DiagnosticStatus,
    val explanation: String,
    val suggestedAction: String
)

data class DiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val items: List<DiagnosticItem>,
    val isOverallHealthy: Boolean
)

class DiagnosticsService(
    private val context: Context?,
    private val credentialStore: TvCredentialStore
) {
    companion object {
        // Strict IPv4 Regex validating octets 0..255
        private val STRICT_IPV4_PATTERN = Pattern.compile(
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
        )
    }

    fun isValidIpAddress(ip: String): Boolean {
        if (ip.isBlank()) return false
        return STRICT_IPV4_PATTERN.matcher(ip.trim()).matches()
    }

    suspend fun runDiagnostics(
        selectedDevice: TvDevice?,
        currentConnectionState: ConnectionState,
        connectionDiagnostics: com.gala.motetv.remote.ConnectionDiagnostics? = null
    ): DiagnosticReport = withContext(Dispatchers.IO) {
        val items = mutableListOf<DiagnosticItem>()

        // 1. Local Wi-Fi Adapter Check
        val mirrorServer = ScreenMirrorServer()
        val localIp = mirrorServer.getLocalIpAddress()
        if (localIp != null && localIp != "127.0.0.1" && !localIp.startsWith("169.254.")) {
            items.add(
                DiagnosticItem(
                    id = "wifi_network",
                    name = "Local Wi-Fi Network Adapter",
                    status = DiagnosticStatus.PASSED,
                    explanation = "Phone is connected to local network (Local IP: $localIp)",
                    suggestedAction = "Adapter active. Ensure TV is on the same subnet."
                )
            )
        } else {
            items.add(
                DiagnosticItem(
                    id = "wifi_network",
                    name = "Local Wi-Fi Network Adapter",
                    status = DiagnosticStatus.FAILED,
                    explanation = "No active local Wi-Fi IPv4 address detected",
                    suggestedAction = "Connect phone to the same 2.4/5GHz Wi-Fi network as your TV"
                )
            )
        }

        // 2. Configured TV Device & IP Check (Strict IPv4 Validation)
        val host = selectedDevice?.host
        if (!host.isNullOrBlank()) {
            val isValidIp = isValidIpAddress(host)
            if (isValidIp) {
                items.add(
                    DiagnosticItem(
                        id = "tv_ip_format",
                        name = "TV IP Address Format",
                        status = DiagnosticStatus.PASSED,
                        explanation = "Target TV IP address format is valid ($host)",
                        suggestedAction = "IP format valid"
                    )
                )

                // 3. TCP Endpoint Reachability Check
                val isPort6466Open = testTcpSocket(host, selectedDevice.remotePort.takeIf { it > 0 } ?: 6466)
                val isPort6467Open = testTcpSocket(host, selectedDevice.pairingPort.takeIf { it > 0 } ?: 6467)

                if (isPort6466Open || isPort6467Open) {
                    items.add(
                        DiagnosticItem(
                            id = "tv_reachability",
                            name = "Android TV Endpoint Reachability",
                            status = DiagnosticStatus.PASSED,
                            explanation = "TV remote service endpoint reached at $host (TCP 6466/6467)",
                            suggestedAction = "TV endpoint online"
                        )
                    )
                } else {
                    items.add(
                        DiagnosticItem(
                            id = "tv_reachability",
                            name = "Android TV Endpoint Reachability",
                            status = DiagnosticStatus.FAILED,
                            explanation = "Cannot open TCP connection to $host:6466 or 6467",
                            suggestedAction = "Verify TV power, IP address, and that TV is awake on same network"
                        )
                    )
                }
            } else {
                items.add(
                    DiagnosticItem(
                        id = "tv_ip_format",
                        name = "TV IP Address Format",
                        status = DiagnosticStatus.FAILED,
                        explanation = "Invalid IPv4 format or out-of-range octets: '$host'",
                        suggestedAction = "Check TV Settings > Network and enter a valid IPv4 address"
                    )
                )
            }
        } else {
            items.add(
                DiagnosticItem(
                    id = "tv_ip_format",
                    name = "Target TV Device Selection",
                    status = DiagnosticStatus.FAILED,
                    explanation = "No Android TV device selected or configured",
                    suggestedAction = "Tap 'Pair TV' to scan or add TV via IP"
                )
            )
        }

        // 4. Cached Client TLS Credentials (Distinguished from Live Session)
        val devId = selectedDevice?.id ?: "default"
        val isPaired = credentialStore.isDevicePaired(devId)
        val fp = credentialStore.getServerFingerprint(devId)

        if (isPaired && !fp.isNullOrBlank()) {
            items.add(
                DiagnosticItem(
                    id = "tls_credentials",
                    name = "Cached TLS Credentials",
                    status = DiagnosticStatus.PASSED,
                    explanation = "Cached client certificate and server fingerprint present in local store",
                    suggestedAction = "Cached credentials ready"
                )
            )
        } else {
            items.add(
                DiagnosticItem(
                    id = "tls_credentials",
                    name = "Cached TLS Credentials",
                    status = DiagnosticStatus.FAILED,
                    explanation = "No paired client certificate or server fingerprint cached for device",
                    suggestedAction = "Perform 6-digit code TV pairing flow"
                )
            )
        }

        // 5. Live Protocol Session Handshake Check
        val connStatus = when (currentConnectionState) {
            is ConnectionState.Ready -> DiagnosticStatus.PASSED
            is ConnectionState.Connecting, is ConnectionState.Pairing -> DiagnosticStatus.NOT_AVAILABLE
            else -> DiagnosticStatus.FAILED
        }

        items.add(
            DiagnosticItem(
                id = "connection_state",
                name = "Live Protocol Handshake",
                status = connStatus,
                explanation = if (currentConnectionState is ConnectionState.Ready) {
                    "Live TLS session established and active with TV"
                } else {
                    "No active protocol session (Current State: ${currentConnectionState::class.simpleName})"
                },
                suggestedAction = if (connStatus == DiagnosticStatus.PASSED) "Active" else "Tap Connect in header to initiate session"
            )
        )

        // 6. Live Engine Telemetry (Session ID, Health Monitor, Queued Commands)
        if (connectionDiagnostics != null) {
            val diag = connectionDiagnostics
            val sessionActive = diag.sessionId > 0 && currentConnectionState is ConnectionState.Ready
            val timeSinceLastResponseSec = if (diag.lastReceivedResponseTimeMs > 0) {
                (System.currentTimeMillis() - diag.lastReceivedResponseTimeMs) / 1000
            } else -1L
            val timeSinceLastOutboundSec = if (diag.lastOutboundFrameTimeMs > 0) {
                (System.currentTimeMillis() - diag.lastOutboundFrameTimeMs) / 1000
            } else -1L

            items.add(
                DiagnosticItem(
                    id = "engine_session",
                    name = "Active Engine Session",
                    status = if (sessionActive) DiagnosticStatus.PASSED else DiagnosticStatus.NOT_AVAILABLE,
                    explanation = "Session ID #${diag.sessionId} | Attempts: ${diag.reconnectAttemptCount} | Coalesced: ${diag.coalescedReconnectCount} | Last reason: ${diag.lastDisconnectReason}",
                    suggestedAction = if (sessionActive) "Session running" else "Will connect on demand"
                )
            )

            items.add(
                DiagnosticItem(
                    id = "engine_health",
                    name = "Connection Health & Heartbeat",
                    status = if (diag.isPingOutstanding) DiagnosticStatus.NOT_AVAILABLE else DiagnosticStatus.PASSED,
                    explanation = "Inbound RX: ${if (timeSinceLastResponseSec >= 0) "${timeSinceLastResponseSec}s ago" else "None"} | Outbound TX: ${if (timeSinceLastOutboundSec >= 0) "${timeSinceLastOutboundSec}s ago" else "None"} | Outstanding ping: ${diag.isPingOutstanding}",
                    suggestedAction = if (diag.isPingOutstanding) "Awaiting ping response from TV" else "TV heartbeats normal"
                )
            )

            items.add(
                DiagnosticItem(
                    id = "command_queue",
                    name = "Remote Command Queue",
                    status = if (diag.pendingCommandCount == 0 && diag.uncertainDeliveryCount == 0) DiagnosticStatus.PASSED else DiagnosticStatus.NOT_AVAILABLE,
                    explanation = "Pending: ${diag.pendingCommandCount}/25 | Expired: ${diag.droppedCommandCount} | Evicted: ${diag.evictedCommandCount} | Uncertain TX: ${diag.uncertainDeliveryCount}${diag.lastUncertainCommand?.let { " ($it)" } ?: ""}",
                    suggestedAction = when {
                        diag.uncertainDeliveryCount > 0 -> "Mid-stream writes are not blindly replayed"
                        diag.pendingCommandCount > 0 -> "Draining buffer to TV"
                        else -> "Queue idle"
                    }
                )
            )
        }

        val isHealthy = items.none { it.status == DiagnosticStatus.FAILED }
        DiagnosticReport(items = items, isOverallHealthy = isHealthy)
    }

    private fun testTcpSocket(host: String, port: Int, timeoutMs: Int = 2000): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun generateRedactedReport(report: DiagnosticReport): String {
        val sb = StringBuilder()
        sb.appendLine("MoteTV Connection Diagnostics Report")
        sb.appendLine("Timestamp: ${report.timestamp}")
        sb.appendLine("Overall Result: ${if (report.isOverallHealthy) "PASSED" else "NEEDS ATTENTION"}")
        sb.appendLine("----------------------------------------")
        report.items.forEach { item ->
            sb.appendLine("[${item.status.name}] ${item.name}")
            sb.appendLine("Explanation: ${item.explanation}")
            sb.appendLine("Action: ${item.suggestedAction}")
            sb.appendLine()
        }
        return sb.toString()
    }
}
