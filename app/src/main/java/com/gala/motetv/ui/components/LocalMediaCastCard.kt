package com.gala.motetv.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.media.CastSessionState
import com.gala.motetv.media.LocalMediaCastController
import com.gala.motetv.ui.theme.ElectricBlueDark
import com.gala.motetv.ui.theme.ElectricBlueLight
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassBorderStrong
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.StatusSuccessGreen
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@Composable
fun LocalMediaCastCard(
    castController: LocalMediaCastController,
    sessionState: CastSessionState,
    onPickMediaClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("card_local_media_cast"),
        shape = RoundedCornerShape(24.dp),
        elevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (sessionState is CastSessionState.Streaming) {
                                    StatusSuccessGreen.copy(alpha = 0.15f)
                                } else {
                                    ElectricBluePrimary.copy(alpha = 0.12f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (sessionState is CastSessionState.Streaming) Icons.Default.CastConnected else Icons.Default.Cast,
                            contentDescription = "Cast",
                            tint = if (sessionState is CastSessionState.Streaming) StatusSuccessGreen else ElectricBluePrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Cast File to TV (VLC)",
                            style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Direct Wi-Fi stream • No mirroring",
                            style = Typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                if (sessionState is CastSessionState.Streaming) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(StatusSuccessGreen.copy(alpha = 0.15f))
                            .border(1.dp, StatusSuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "STREAMING",
                            style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = StatusSuccessGreen
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (sessionState) {
                is CastSessionState.Idle -> {
                    Text(
                        text = "Select any video or audio file on your phone. MoteTV will stream it directly to VLC on your Android TV at full quality without screen mirroring.",
                        style = Typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onPickMediaClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_pick_media_file"),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Select File",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Select Media File to Cast",
                            style = Typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }
                }

                is CastSessionState.Selected -> {
                    val media = sessionState.media
                    MediaDetailsBox(mediaName = media.name, mediaSize = media.size, mediaType = media.mimeType)

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPickMediaClick,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Change File", style = Typography.labelMedium, color = TextPrimary)
                        }

                        Button(
                            onClick = { castController.startCast(media) },
                            modifier = Modifier
                                .weight(1.4f)
                                .height(46.dp)
                                .testTag("btn_start_cast_vlc"),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play on TV", tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play on TV (VLC)", style = Typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)
                        }
                    }
                }

                is CastSessionState.Streaming -> {
                    val media = sessionState.media
                    MediaDetailsBox(mediaName = media.name, mediaSize = media.size, mediaType = media.mimeType)

                    Spacer(modifier = Modifier.height(10.dp))

                    // Stream URL box with Copy
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GlassSurface)
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Local Stream URL", style = Typography.labelSmall, color = TextMuted)
                                Text(
                                    text = sessionState.streamUrl,
                                    style = Typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = ElectricBlueDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Stream URL", sessionState.streamUrl)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Stream URL copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Playback Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rewind 10s
                        IconButton(
                            onClick = { castController.seekBackward10s() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
                                .testTag("btn_cast_rw")
                        ) {
                            Icon(Icons.Default.FastRewind, contentDescription = "Rewind", tint = TextPrimary)
                        }

                        // Play / Pause
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .shadow(4.dp, CircleShape, spotColor = Color(0x335B6CFF))
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(ElectricBluePrimary, ElectricBlueDark)))
                                .border(1.dp, ElectricBlueLight, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = { castController.togglePlayPause() },
                                modifier = Modifier.fillMaxSize().testTag("btn_cast_play_pause")
                            ) {
                                Icon(
                                    imageVector = if (sessionState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // Fast Forward 10s
                        IconButton(
                            onClick = { castController.seekForward10s() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
                                .testTag("btn_cast_ff")
                        ) {
                            Icon(Icons.Default.FastForward, contentDescription = "Fast Forward", tint = TextPrimary)
                        }

                        // Volume Down
                        IconButton(
                            onClick = { castController.volumeDown() },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(GlassSurface)
                                .border(1.dp, GlassBorder, CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Vol Down", tint = TextSecondary, modifier = Modifier.size(20.dp))
                        }

                        // Volume Up
                        IconButton(
                            onClick = { castController.volumeUp() },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(GlassSurface)
                                .border(1.dp, GlassBorder, CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Vol Up", tint = TextSecondary, modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action buttons: Re-send VLC, Direct HTTP fallback, Stop Cast
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { castController.startCast(media, useDirectHttpFallback = true) },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Retry HTTP", style = Typography.labelSmall, color = TextSecondary)
                        }

                        Button(
                            onClick = { castController.stopCast() },
                            modifier = Modifier.weight(1.2f).height(42.dp).testTag("btn_stop_cast"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop Cast", style = Typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
                        }
                    }
                }

                is CastSessionState.Error -> {
                    Text(
                        text = "Error: ${sessionState.message}",
                        style = Typography.bodySmall,
                        color = Color(0xFFDC2626)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onPickMediaClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Try Another File", color = Color.White)
                    }
                }
            }

            // Quick hint to install VLC if needed
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Requires VLC installed on Android TV • ",
                    style = Typography.labelSmall,
                    color = TextMuted
                )
                IconButton(
                    onClick = { castController.launchVlcAppStoreOnTv() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GetApp,
                        contentDescription = "Install VLC on TV",
                        tint = ElectricBluePrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "Install VLC",
                    style = Typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = ElectricBluePrimary
                )
            }
        }
    }
}

@Composable
private fun MediaDetailsBox(mediaName: String, mediaSize: Long, mediaType: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassSurfaceElevated)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = ElectricBluePrimary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mediaName,
                    style = Typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(mediaSize)} • $mediaType",
                    style = Typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "Unknown size"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}
