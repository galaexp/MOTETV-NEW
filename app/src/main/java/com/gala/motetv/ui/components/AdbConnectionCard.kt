package com.gala.motetv.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.AdbConnectionMode
import com.gala.motetv.adb.AdbConnectionState
import com.gala.motetv.adb.AdbDiagnosticCategory
import com.gala.motetv.adb.AdbPairingState
import com.gala.motetv.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AdbConnectionCard(
    adbManager: AdbConnectionManager,
    defaultTvIp: String?,
    onConnectedCallback: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val adbState by adbManager.connectionState.collectAsStateWithLifecycle()
    val pairingState by adbManager.pairingState.collectAsStateWithLifecycle()
    val diagnosticCategory by adbManager.diagnosticCategory.collectAsStateWithLifecycle()
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
    var pairingPortInput by remember { mutableStateOf("") }
    var pairingCodeInput by remember { mutableStateOf("") }
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
                        is AdbConnectionState.Error -> "${st.category.label}: ${st.userMessage}"
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
                            tint = if (adbManager.pairingCapability.isNativeSpake2Supported) ElectricBlueLight else StatusWarningAmber,
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

            // Host & Connect Port Inputs
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
                    label = {
                        Text(if (selectedMode == AdbConnectionMode.WIRELESS_DEBUGGING_TLS) "Connect Port" else "Port")
                    },
                    placeholder = {
                        Text(if (selectedMode == AdbConnectionMode.WIRELESS_DEBUGGING_TLS) "e.g. 39421" else "5555")
                    },
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

            // Step 1: Wireless Debugging Pairing Code Section (Android 11+ / API 30+)
            if (selectedMode == AdbConnectionMode.WIRELESS_DEBUGGING_TLS && adbManager.pairingCapability.isNativeSpake2Supported) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = GlassSurfacePressed,
                    border = BorderStroke(1.dp, ElectricBlueLight.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "STEP 1: WIRELESS DEBUGGING PAIRING (ONE-TIME)",
                            style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
                            color = ElectricBlueLight
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = pairingPortInput,
                                onValueChange = { pairingPortInput = it.filter { ch -> ch.isDigit() }.take(5) },
                                label = { Text("Pair Port") },
                                placeholder = { Text("37xxx") },
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
                                    .testTag("adb_pair_port_input")
                            )

                            OutlinedTextField(
                                value = pairingCodeInput,
                                onValueChange = { pairingCodeInput = it.filter { ch -> ch.isDigit() }.take(6) },
                                label = { Text("6-Digit Code") },
                                placeholder = { Text("123456") },
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
                                    .testTag("adb_pair_code_input")
                            )

                            val isPairingNow = pairingState is AdbPairingState.Pairing
                            Button(
                                onClick = {
                                    val pairPort = pairingPortInput.toIntOrNull() ?: 0
                                    scope.launch {
                                        adbManager.pairWirelessDebugging(hostInput, pairPort, pairingCodeInput)
                                    }
                                },
                                enabled = !isPairingNow && hostInput.isNotBlank() && pairingCodeInput.length == 6,
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("adb_pair_submit_button")
                            ) {
                                Text(if (isPairingNow) "..." else "Pair", style = Typography.labelLarge)
                            }
                        }

                        when (val pState = pairingState) {
                            is AdbPairingState.Pairing -> Text(
                                text = "Pairing with ${pState.host}:${pState.pairingPort}...",
                                style = Typography.labelSmall,
                                color = StatusWarningAmber
                            )
                            is AdbPairingState.Paired -> Text(
                                text = "${pState.detail} Enter Connect Port above & tap Connect ADB.",
                                style = Typography.labelSmall,
                                color = StatusSuccessGreen
                            )
                            is AdbPairingState.Failed -> Text(
                                text = "${pState.category.label}: ${pState.message}",
                                style = Typography.labelSmall,
                                color = StatusErrorRed
                            )
                            AdbPairingState.Idle -> Unit
                        }
                    }
                }
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
                                    selectedMode = AdbConnectionMode.WIRELESS_DEBUGGING_TLS
                                    if (svc.isPairingService) {
                                        pairingPortInput = svc.port.toString()
                                    } else {
                                        portInput = svc.port.toString()
                                    }
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
                                    text = if (svc.isPairingService) "Pairing Port" else "Connect Port",
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
                            val defaultPort = if (selectedMode == AdbConnectionMode.NETWORK_LEGACY_5555) 5555 else 0
                            val parsedPort = portInput.toIntOrNull() ?: defaultPort
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

            val footerText = if (diagnosticCategory != AdbDiagnosticCategory.NONE && !diagnosticDetail.startsWith(diagnosticCategory.label)) {
                "[${diagnosticCategory.label}] $diagnosticDetail"
            } else {
                diagnosticDetail
            }
            Text(
                text = footerText,
                style = Typography.labelSmall,
                color = if (diagnosticCategory != AdbDiagnosticCategory.NONE) StatusWarningAmber else TextMuted
            )
        }
    }
}
