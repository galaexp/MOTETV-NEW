package com.gala.motetv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.AdbConnectionMode
import com.gala.motetv.adb.AdbConnectionState
import com.gala.motetv.ui.theme.*

@Composable
fun AdbConnectionCard(
    adbManager: AdbConnectionManager,
    defaultTvIp: String?,
    onConnectedCallback: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val adbState by adbManager.connectionState.collectAsStateWithLifecycle()
    val discoveredServices by adbManager.discoveredServices.collectAsStateWithLifecycle()
    val isDiscovering by adbManager.isDiscovering.collectAsStateWithLifecycle()
    val savedEndpoint by adbManager.savedEndpoint.collectAsStateWithLifecycle()
    val diagnosticDetail by adbManager.lastDiagnosticDetail.collectAsStateWithLifecycle()

    var hostInput by remember(defaultTvIp, savedEndpoint) {
        mutableStateOf(savedEndpoint?.host?.ifBlank { null } ?: defaultTvIp ?: "")
    }
    var selectedMode by remember(savedEndpoint) {
        mutableStateOf(savedEndpoint?.mode ?: AdbConnectionMode.NETWORK_LEGACY_5555)
    }
    var portInput by remember(savedEndpoint, selectedMode) {
        mutableStateOf(
            if (savedEndpoint != null && savedEndpoint!!.mode == selectedMode) {
                savedEndpoint!!.port.toString()
            } else if (selectedMode == AdbConnectionMode.NETWORK_LEGACY_5555) {
                "5555"
            } else {
                ""
            }
        )
    }
    var showPairingHelp by remember { mutableStateOf(false) }

    LaunchedEffect(adbState) {
        if (adbState is AdbConnectionState.Connected) {
            onConnectedCallback()
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TV ADB CONNECTION (SEPARATE FROM REMOTE)",
                        style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                        color = TextMuted
                    )
                    val statusColor = when (adbState) {
                        is AdbConnectionState.Connected -> StatusSuccessGreen
                        is AdbConnectionState.Connecting, is AdbConnectionState.AwaitingTvAuthorization -> StatusWarningAmber
                        is AdbConnectionState.Error -> StatusErrorRed
                        is AdbConnectionState.Disconnected -> TextSecondary
                    }
                    val statusText = when (val st = adbState) {
                        is AdbConnectionState.Connected -> "Connected to ${st.host}:${st.port}"
                        is AdbConnectionState.Connecting -> "Connecting to ${st.host}:${st.port}..."
                        is AdbConnectionState.AwaitingTvAuthorization -> "Approve prompt on TV (Key ${st.keyFingerprint.take(11)}...)"
                        is AdbConnectionState.Error -> st.userMessage
                        is AdbConnectionState.Disconnected -> "Disconnected — Remote v2 (6466) stays unaffected"
                    }
                    Text(
                        text = statusText,
                        style = Typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = statusColor
                    )
                }

                IconButton(
                    onClick = { showPairingHelp = !showPairingHelp },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("adb_help_toggle")
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Wireless Debugging Capability Info",
                        tint = ElectricBlueLight
                    )
                }
            }

            // Mode selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdbConnectionMode.entries.forEach { mode ->
                    val selected = selectedMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedMode = mode
                            if (mode == AdbConnectionMode.NETWORK_LEGACY_5555 && portInput.isBlank()) {
                                portInput = "5555"
                            } else if (mode == AdbConnectionMode.WIRELESS_DEBUGGING_TLS && portInput == "5555") {
                                portInput = ""
                            }
                        },
                        label = {
                            Text(
                                text = if (mode == AdbConnectionMode.NETWORK_LEGACY_5555) "Network ADB (5555)" else "Wireless TLS",
                                style = Typography.labelSmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricBluePrimary.copy(alpha = 0.35f),
                            selectedLabelColor = TextPrimary,
                            labelColor = TextSecondary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("adb_mode_${mode.name.lowercase()}")
                    )
                }
            }

            if (showPairingHelp || selectedMode == AdbConnectionMode.WIRELESS_DEBUGGING_TLS) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = StatusWarningAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = adbManager.pairingCapability.explanation,
                            style = Typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Host & Port Inputs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("TV IP Address") },
                    placeholder = { Text("192.168.1.x") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricBlueLight,
                        unfocusedBorderColor = GlassBorderStrong
                    ),
                    modifier = Modifier
                        .weight(2f)
                        .testTag("adb_host_input")
                )

                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("Port") },
                    placeholder = { Text("5555") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricBlueLight,
                        unfocusedBorderColor = GlassBorderStrong
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("adb_port_input")
                )
            }

            // Discovered mDNS Wireless Debugging services
            if (discoveredServices.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "DISCOVERED WIRELESS ADB SERVICES",
                        style = Typography.labelSmall,
                        color = TextMuted
                    )
                    discoveredServices.forEach { svc ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = GlassSurfacePressed,
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    hostInput = svc.host
                                    portInput = svc.port.toString()
                                    selectedMode = AdbConnectionMode.WIRELESS_DEBUGGING_TLS
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${svc.serviceName} (${svc.host}:${svc.port})",
                                    style = Typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (svc.isPairingService) "Pairing" else "Connect",
                                    style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ElectricBlueLight
                                )
                            }
                        }
                    }
                }
            }

            // Primary Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (isDiscovering) adbManager.stopWirelessDiscovery() else adbManager.startWirelessDiscovery()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GlassBorderStrong),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("adb_scan_mdns_button")
                ) {
                    Icon(
                        imageVector = if (isDiscovering) Icons.Default.Stop else Icons.Default.WifiFind,
                        contentDescription = null,
                        tint = ElectricBlueLight,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isDiscovering) "Stop" else "Find TLS",
                        color = ElectricBlueLight,
                        style = Typography.labelLarge
                    )
                }

                val isConnected = adbState is AdbConnectionState.Connected
                Button(
                    onClick = {
                        if (isConnected) {
                            adbManager.disconnect()
                        } else {
                            val parsedPort = portInput.toIntOrNull() ?: 5555
                            adbManager.connect(hostInput, parsedPort, selectedMode)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) StatusErrorRed.copy(alpha = 0.85f) else ElectricBluePrimary
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("adb_connect_toggle_button")
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.LinkOff else Icons.Default.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isConnected) "Disconnect ADB" else "Connect ADB",
                        style = Typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Text(
                text = diagnosticDetail,
                style = Typography.labelSmall,
                color = TextMuted
            )
        }
    }
}
