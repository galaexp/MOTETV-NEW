package com.gala.motetv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.storage.TvCredentialStore
import com.gala.motetv.ui.components.DiagnosticsBottomSheet
import com.gala.motetv.ui.components.GlassButton
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.theme.BackgroundLight
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.StatusErrorRed
import com.gala.motetv.ui.theme.StatusSuccessGreen
import com.gala.motetv.ui.theme.SurfaceCardLight
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@Composable
fun SettingsScreen(
    currentDevice: TvDevice?,
    connectionState: ConnectionState,
    savedDevices: List<TvDevice>,
    credentialStore: TvCredentialStore,
    onSelectDevice: (TvDevice) -> Unit,
    onOpenDiscovery: () -> Unit,
    onAddManualDevice: (String, String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onRenameDevice: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    var showDiagnostics by remember { mutableStateOf(false) }
    var showAddManualDialog by remember { mutableStateOf(false) }
    var renameDeviceTarget by remember { mutableStateOf<TvDevice?>(null) }
    var newDeviceName by remember { mutableStateOf("") }

    var hapticEnabled by remember { mutableStateOf(credentialStore.isHapticEnabled()) }
    var activeProfile by remember { mutableStateOf(credentialStore.getActiveProfile()) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BackgroundLight,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Settings & Devices",
                    style = Typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = "Configure MoteTV preferences and saved TVs",
                    style = Typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Saved TV Devices Section
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_saved_tvs_card"),
                shape = RoundedCornerShape(22.dp),
                elevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = null,
                                tint = ElectricBluePrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Saved TVs",
                                style = Typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                        }

                        Row {
                            IconButton(onClick = onOpenDiscovery, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = "Scan",
                                    tint = ElectricBluePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { showAddManualDialog = true }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add IP",
                                    tint = ElectricBluePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (savedDevices.isEmpty() && currentDevice == null) {
                        Text(
                            text = "No saved TV devices yet. Tap the Scan button to find your Android TV or Xiaomi Mi TV Stick.",
                            style = Typography.bodySmall,
                            color = TextSecondary
                        )
                    } else {
                        val allDisplayDevices = (savedDevices + listOfNotNull(currentDevice)).distinctBy { it.id }

                        allDisplayDevices.forEach { dev ->
                            val isCurrent = currentDevice?.id == dev.id
                            val isPaired = credentialStore.isDevicePaired(dev.id)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isCurrent) ElectricBluePrimary.copy(alpha = 0.08f) else GlassSurface)
                                    .border(1.dp, if (isCurrent) ElectricBluePrimary.copy(alpha = 0.3f) else GlassBorder, RoundedCornerShape(14.dp))
                                    .clickable { onSelectDevice(dev) }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = dev.name,
                                                style = Typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = TextPrimary
                                            )
                                            if (isCurrent) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(StatusSuccessGreen)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${dev.host} • ${if (isPaired) "Paired" else "Unpaired"}",
                                            style = Typography.bodySmall.copy(fontSize = 12.sp),
                                            color = TextSecondary
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                renameDeviceTarget = dev
                                                newDeviceName = dev.name
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Rename",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { onRemoveDevice(dev.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = StatusErrorRed,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Remote Profiles Section (Phase 10)
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_profiles_card"),
                shape = RoundedCornerShape(22.dp),
                elevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Text(
                        text = "Remote Profiles",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )
                    Text(
                        text = "Customize layout priorities based on your activity",
                        style = Typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val profiles = listOf("Default", "Movie Night", "YouTube", "Gaming")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        profiles.forEach { prof ->
                            val isSelected = activeProfile == prof
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) ElectricBluePrimary else GlassSurfaceElevated)
                                    .border(1.dp, if (isSelected) ElectricBluePrimary else GlassBorder, RoundedCornerShape(12.dp))
                                    .clickable {
                                        activeProfile = prof
                                        credentialStore.setActiveProfile(prof)
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = prof,
                                    style = Typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isSelected) Color.White else TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            // Preferences Section
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                elevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Preferences",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )

                    // Haptics toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Vibration,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Haptic Feedback",
                                    style = Typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Subtle vibration on button press",
                                    style = Typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        Switch(
                            checked = hapticEnabled,
                            onCheckedChange = {
                                hapticEnabled = it
                                credentialStore.setHapticEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ElectricBluePrimary,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = GlassSurfaceElevated
                            )
                        )
                    }
                }
            }

            // Diagnostics and Security Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                elevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = StatusSuccessGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Protocol Security",
                                style = Typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                        }
                    }

                    Text(
                        text = "• Protocol: Android TV Remote v2\n• Pairing Channel: TCP 6467 (Mutual TLS, SHA-256 Secret exchange)\n• Remote Channel: TCP 6466 (Mutual TLS, Varint protobuf streaming)\n• Hardware Tested: Xiaomi Mi TV Stick",
                        style = Typography.bodySmall.copy(lineHeight = 18.sp),
                        color = TextSecondary
                    )

                    GlassButton(
                        text = "View Diagnostics Log",
                        icon = Icons.Default.Terminal,
                        onClick = { showDiagnostics = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // About MoteTV Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                elevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "MoteTV v2.0",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "Light Glassmorphism Android TV Remote",
                        style = Typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // Add Manual Device Dialog
    if (showAddManualDialog) {
        var manualName by remember { mutableStateOf("Living Room TV") }
        var manualIp by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddManualDialog = false },
            containerColor = SurfaceCardLight,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Add TV by IP Address", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the local IP address of your Android TV device:", style = Typography.bodySmall, color = TextSecondary)
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it },
                        label = { Text("Device Name") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("IP Address (e.g. 192.168.1.117)") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                GlassButton(
                    text = "Connect",
                    isPrimary = true,
                    enabled = manualIp.isNotBlank(),
                    onClick = {
                        onAddManualDevice(manualName.trim(), manualIp.trim())
                        showAddManualDialog = false
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showAddManualDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    // Rename Device Dialog
    renameDeviceTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameDeviceTarget = null },
            containerColor = SurfaceCardLight,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Rename TV", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newDeviceName,
                    onValueChange = { newDeviceName = it },
                    label = { Text("New Name") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                GlassButton(
                    text = "Save",
                    isPrimary = true,
                    enabled = newDeviceName.isNotBlank(),
                    onClick = {
                        onRenameDevice(target.id, newDeviceName.trim())
                        renameDeviceTarget = null
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { renameDeviceTarget = null }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    if (showDiagnostics) {
        DiagnosticsBottomSheet(onDismiss = { showDiagnostics = false })
    }
}
