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
    private val context: Context,
    private val credentialStore: TvCredentialStore
) {

    suspend fun runDiagnostics(
        selectedDevice: TvDevice?,
        currentConnectionState: ConnectionState
    ): DiagnosticReport = withContext(Dispatchers.IO) {
        val items = mutableListOf<DiagnosticItem>()

        // 1. Wi-Fi / Local Network Check
        val mirrorServer = ScreenMirrorServer()
        val localIp = mirrorServer.getLocalIpAddress()
        if (localIp != null && localIp != "127.0.0.1" && !localIp.startsWith("169.254.")) {
            items.add(
                DiagnosticItem(
                    id = "wifi_network",
                    name = "Wi-Fi LAN Connection",
                    status = DiagnosticStatus.PASSED,
                    explanation = "Phone is connected to local network (IP: $localIp)",
                    suggestedAction = "Network active"
                )
            )
        } else {
            items.add(
                DiagnosticItem(
                    id = "wifi_network",
                    name = "Wi-Fi LAN Connection",
                    status = DiagnosticStatus.FAILED,
                    explanation = "No active local Wi-Fi IP detected",
                    suggestedAction = "Connect phone to the same 2.4/5GHz Wi-Fi as your Android TV"
                )
            )
        }

        // 2. Configured TV Device & IP Check
        val host = selectedDevice?.host
        if (!host.isNullOrBlank()) {
            val isValidIp = host.matches(Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$"))
            if (isValidIp) {
                items.add(
                    DiagnosticItem(
                        id = "tv_ip_format",
                        name = "TV IP Address Format",
                        status = DiagnosticStatus.PASSED,
                        explanation = "Target TV IP is valid ($host)",
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
                            explanation = "TV remote service detected on $host (TCP 6466/6467)",
                            suggestedAction = "TV endpoint online"
                        )
                    )
                } else {
                    items.add(
                        DiagnosticItem(
                            id = "tv_reachability",
                            name = "Android TV Endpoint Reachability",
                            status = DiagnosticStatus.FAILED,
                            explanation = "Cannot reach Android TV service at $host:6466 or 6467",
                            suggestedAction = "Ensure TV is powered on, awake, and not blocking local network discovery"
                        )
                    )
                }
            } else {
                items.add(
                    DiagnosticItem(
                        id = "tv_ip_format",
                        name = "TV IP Address Format",
                        status = DiagnosticStatus.FAILED,
                        explanation = "Invalid IP address format: '$host'",
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
                    explanation = "No Android TV selected or configured",
                    suggestedAction = "Tap 'Pair TV' to scan or add TV via IP"
                )
            )
        }

        // 4. Client TLS Credentials & Pairing Status
        val devId = selectedDevice?.id ?: "default"
        val isPaired = credentialStore.isDevicePaired(devId)
        val fp = credentialStore.getServerFingerprint(devId)

        if (isPaired && !fp.isNullOrBlank()) {
            items.add(
                DiagnosticItem(
                    id = "tls_credentials",
                    name = "TLS Pairing Credentials",
                    status = DiagnosticStatus.PASSED,
                    explanation = "Paired identity and server certificate fingerprint cached",
                    suggestedAction = "Credentials ready"
                )
            )
        } else {
            items.add(
                DiagnosticItem(
                    id = "tls_credentials",
                    name = "TLS Pairing Credentials",
                    status = DiagnosticStatus.FAILED,
                    explanation = "TV not yet paired or pairing credentials missing",
                    suggestedAction = "Perform 6-digit code TV pairing flow"
                )
            )
        }

        // 5. Active Protocol Connection State
        val connStatus = when (currentConnectionState) {
            is ConnectionState.Ready -> DiagnosticStatus.PASSED
            is ConnectionState.Connecting, is ConnectionState.Pairing -> DiagnosticStatus.NOT_AVAILABLE
            else -> DiagnosticStatus.FAILED
        }

        items.add(
            DiagnosticItem(
                id = "connection_state",
                name = "Active Protocol Session",
                status = connStatus,
                explanation = "Current State: ${currentConnectionState::class.simpleName}",
                suggestedAction = if (connStatus == DiagnosticStatus.PASSED) "Active" else "Tap Connect in header to reconnect"
            )
        )

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
