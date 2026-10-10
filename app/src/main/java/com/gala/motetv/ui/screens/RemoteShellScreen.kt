package com.gala.motetv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.ui.components.GlassButton
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.components.GlassTopBar
import com.gala.motetv.ui.theme.ElectricBlueLight
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.BackgroundLight
import com.gala.motetv.ui.theme.SurfaceCardLight
import com.gala.motetv.ui.theme.StatusSuccessGreen
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@Composable
fun RemoteShellScreen(
    modifier: Modifier = Modifier,
    discoveredDevice: TvDevice? = null,
    onOpenDiscovery: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {}
) {
    var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BackgroundLight,
        topBar = {
            GlassTopBar(
                connectionState = connectionState,
                onSettingsClick = onOpenDiagnostics,
                onScanClick = onOpenDiscovery
            )
        },
        bottomBar = {
            GlassBottomNavigation(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Target TV Card / Connection Indicator
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("current_device_card"),
                shape = RoundedCornerShape(24.dp),
                backgroundColor = GlassSurfaceElevated
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(ElectricBluePrimary, ElectricBlueLight)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = "TV Device",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = discoveredDevice?.name ?: "Xiaomi Mi TV Stick / Google TV",
                            style = Typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (discoveredDevice != null) {
                                "${discoveredDevice.host} • TCP ${discoveredDevice.pairingPort} (Pairing) / ${discoveredDevice.remotePort} (Remote)"
                            } else {
                                "Tap Scan to discover Android TV / Google TV"
                            },
                            style = Typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Phase 1 Verification Status Banner
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("phase1_status_card"),
                shape = RoundedCornerShape(20.dp),
                backgroundColor = GlassSurface
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = StatusSuccessGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Phase 1: Architecture & Shell Ready",
                            style = Typography.titleSmall ?: Typography.titleMedium,
                            color = StatusSuccessGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Native Kotlin + Compose runtime configured with modern glassmorphism, NsdManager discovery interfaces, and pairing separation. Ready for Phase 2 mDNS TV discovery.",
                        style = Typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Navigation Controller Mockup for the Remote Pad
            Text(
                text = "Remote Controller",
                style = Typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // D-Pad preview placeholder
            GlassDpadPreview()

            Spacer(modifier = Modifier.height(24.dp))

            // Quick Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickActionButton(icon = Icons.AutoMirrored.Filled.VolumeMute, label = "Mute", testTag = "btn_mute") {}
                QuickActionButton(icon = Icons.AutoMirrored.Filled.VolumeDown, label = "Vol -", testTag = "btn_voldown") {}
                QuickActionButton(icon = Icons.AutoMirrored.Filled.VolumeUp, label = "Vol +", testTag = "btn_volup") {}
                QuickActionButton(icon = Icons.Default.PowerSettingsNew, label = "Power", testTag = "btn_power") {}
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action button to start Phase 2 discovery
            GlassButton(
                text = "Scan Local Network (Phase 2)",
                icon = Icons.Default.Search,
                isPrimary = true,
                modifier = Modifier.fillMaxWidth(),
                testTag = "start_scan_button",
                onClick = onOpenDiscovery
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun GlassDpadPreview(modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier
            .size(240.dp)
            .testTag("dpad_container"),
        shape = CircleShape,
        backgroundColor = GlassSurfaceElevated
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Center OK Button
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(ElectricBluePrimary, ElectricBlueLight.copy(alpha = 0.8f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "OK",
                    style = Typography.titleMedium,
                    color = Color.White
                )
            }

            // Directional indicators
            Text(
                text = "▲",
                style = Typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            )
            Text(
                text = "▼",
                style = Typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
            Text(
                text = "◀",
                style = Typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            )
            Text(
                text = "▶",
                style = Typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            )
        }
    }
}

@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.testTag(testTag)
    ) {
        GlassCard(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(16.dp),
            backgroundColor = GlassSurface
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = Typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
fun GlassBottomNavigation(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        "Remote" to Icons.Default.Tv,
        "Devices" to Icons.Default.Devices,
        "Apps" to Icons.Default.Apps,
        "Diagnostics" to Icons.Default.BugReport
    )

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        backgroundColor = SurfaceCardLight.copy(alpha = 0.9f),
        borderColor = GlassBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, (label, icon) ->
                val isSelected = selectedTab == index
                val tint = if (isSelected) ElectricBluePrimary else TextMuted
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = label,
                        style = Typography.labelSmall,
                        color = tint
                    )
                }
            }
        }
    }
}
