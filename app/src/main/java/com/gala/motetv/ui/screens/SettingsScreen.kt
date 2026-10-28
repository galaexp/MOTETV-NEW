package com.gala.motetv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gala.motetv.auth.AuthManager
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.storage.TvCredentialStore
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.theme.*

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
    authManager: AuthManager? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var hapticEnabled by remember { mutableStateOf(credentialStore.isHapticEnabled()) }
    var renameTarget by remember { mutableStateOf<TvDevice?>(null) }
    var newTvName by remember { mutableStateOf("") }
    val currentUser by authManager?.currentUser?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // HEADER
            Column {
                Text(
                    text = "Settings",
                    style = Typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = "Account, TV connections, and remote preferences",
                    style = Typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }

            // 1. GOOGLE ACCOUNT PROFILE CARD
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Text(
                        text = "ACCOUNT & SYNC",
                        style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = Color(0xFF64748B)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0284C7).copy(alpha = 0.2f))
                                    .border(1.dp, Color(0xFF38BDF8), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = currentUser?.displayName ?: "Google User",
                                    style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = currentUser?.email ?: "Signed in via Firebase",
                                    style = Typography.bodySmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }

                        Button(
                            onClick = { authManager?.signOut() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            border = BorderStroke(1.dp, Color(0xFFEF4444)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Sign Out", style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFFEF4444))
                        }
                    }
                }
            }

            // 2. SAVED ANDROID TVS
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
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
                        Text(
                            text = "PAIRED ANDROID TVS (${savedDevices.size})",
                            style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                            color = Color(0xFF64748B)
                        )

                        TextButton(
                            onClick = onOpenDiscovery,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pair TV", color = Color(0xFF38BDF8), style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    if (savedDevices.isEmpty()) {
                        Text(
                            text = "No saved TVs. Tap 'Pair TV' to discover Android TVs on your Wi-Fi.",
                            style = Typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        savedDevices.forEach { dev ->
                            val isCurrent = currentDevice?.id == dev.id
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isCurrent) Color(0xFF1E293B) else Color(0xFF090D16),
                                border = BorderStroke(1.dp, if (isCurrent) Color(0xFF38BDF8) else Color(0xFF1E293B)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onSelectDevice(dev) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tv,
                                            contentDescription = null,
                                            tint = if (isCurrent) Color(0xFF38BDF8) else Color(0xFF64748B),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = dev.name,
                                                style = Typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color.White
                                            )
                                            Text(
                                                text = "${dev.host}:${dev.remotePort}" + if (isCurrent) " • Active" else "",
                                                style = Typography.labelSmall,
                                                color = if (isCurrent) Color(0xFF10B981) else Color(0xFF94A3B8)
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                renameTarget = dev
                                                newTvName = dev.name
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = { onRemoveDevice(dev.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. REMOTE PREFERENCES
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "PREFERENCES",
                        style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = Color(0xFF64748B)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Haptic Vibration",
                                style = Typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = "Vibrate on button taps and D-pad navigation",
                                style = Typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Switch(
                            checked = hapticEnabled,
                            onCheckedChange = {
                                hapticEnabled = it
                                credentialStore.setHapticEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF0284C7),
                                uncheckedThumbColor = Color(0xFF64748B),
                                uncheckedTrackColor = Color(0xFF1E293B)
                            )
                        )
                    }
                }
            }

            // 4. ABOUT & PROTOCOL SPEC
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ABOUT MOTETV",
                        style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = Color(0xFF64748B)
                    )

                    Text(
                        text = "MoteTV v2.5 Cyber Glass Edition",
                        style = Typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )

                    Text(
                        text = "Features low-latency Android TV Remote Protocol v2 (TCP 6466), MediaProjection real-time Screen Mirroring, Local Media Casting (VLC), and secure Firebase Google Authentication.",
                        style = Typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // RENAME DIALOG
        if (renameTarget != null) {
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                containerColor = Color(0xFF0F172A),
                shape = RoundedCornerShape(24.dp),
                title = { Text("Rename TV", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = newTvName,
                        onValueChange = { newTvName = it },
                        label = { Text("TV Name", color = Color(0xFF94A3B8)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155)
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            renameTarget?.let { dev ->
                                if (newTvName.isNotEmpty()) {
                                    onRenameDevice(dev.id, newTvName)
                                }
                            }
                            renameTarget = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                }
            )
        }
    }
}
