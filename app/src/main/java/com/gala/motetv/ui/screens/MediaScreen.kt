package com.gala.motetv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.media.CastSessionState
import com.gala.motetv.media.LocalMediaCastController
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.components.LocalMediaCastCard
import com.gala.motetv.ui.theme.*
import com.google.android.apps.tv.remote.protocol.Direction

@Composable
fun MediaScreen(
    device: TvDevice?,
    connectionState: ConnectionState,
    onSendKey: (Int, Direction) -> Unit,
    onOpenDevices: () -> Unit,
    onReconnect: () -> Unit,
    castController: LocalMediaCastController? = null,
    sessionState: CastSessionState = CastSessionState.Idle,
    onPickMediaClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isConnected = connectionState is ConnectionState.Ready

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
            // HEADER & TV STATUS PILL
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Media Cast",
                        style = Typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "Cast phone movies, music & videos directly to TV",
                        style = Typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isConnected) Color(0x2210B981) else Color(0x22EF4444),
                    border = BorderStroke(1.dp, if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) (device?.name ?: "TV Connected") else "TV Offline",
                            style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }

            // LOCAL MEDIA CAST HERO CARD
            if (castController != null) {
                LocalMediaCastCard(
                    castController = castController,
                    sessionState = sessionState,
                    onPickMediaClick = onPickMediaClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // TV PLAYBACK & VOLUME COCKPIT
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
                        text = "TV PLAYBACK & VOLUME",
                        style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = Color(0xFF64748B)
                    )

                    // Playback row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rewind 10s
                        IconButton(
                            onClick = { onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_REWIND, Direction.SHORT) },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                                .border(1.dp, Color(0xFF334155), CircleShape)
                        ) {
                            Icon(Icons.Default.FastRewind, contentDescription = "Rewind", tint = Color.White)
                        }

                        // Big Play / Pause Button
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0284C7))
                                .border(1.5.dp, Color(0xFF38BDF8), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = { onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE, Direction.SHORT) },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(38.dp))
                            }
                        }

                        // Fast Forward 10s
                        IconButton(
                            onClick = { onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_FAST_FORWARD, Direction.SHORT) },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                                .border(1.dp, Color(0xFF334155), CircleShape)
                        ) {
                            Icon(Icons.Default.FastForward, contentDescription = "Forward", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Volume Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_DOWN, Direction.SHORT) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("VOL -", color = Color.White)
                        }

                        Button(
                            onClick = { onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_MUTE, Direction.SHORT) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            border = BorderStroke(1.dp, Color(0xFFEF4444)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeMute, contentDescription = null, tint = Color(0xFFEF4444))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Mute", color = Color(0xFFEF4444))
                        }

                        Button(
                            onClick = { onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_UP, Direction.SHORT) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("VOL +", color = Color.White)
                        }
                    }
                }
            }

            // TV STREAMING GUIDE
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("VLC Direct Streaming", style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
                    }

                    Text(
                        text = "MoteTV runs a local high-performance HTTP streaming server directly on your phone. When you pick a video, it dispatches the direct stream link into VLC on your TV for instant, crystal-clear playback without cloud latency or transcoding.",
                        style = Typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
