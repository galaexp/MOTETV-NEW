package com.gala.motetv.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.media.CastSessionState
import com.gala.motetv.media.LocalMediaCastController
import com.gala.motetv.ui.components.GlassButton
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.components.LocalMediaCastCard
import com.gala.motetv.ui.components.TvStatusCard
import com.gala.motetv.ui.theme.BackgroundLight
import com.gala.motetv.ui.theme.ElectricBlueDark
import com.gala.motetv.ui.theme.ElectricBlueLight
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassBorderStrong
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.GlassSurfaceSubtle
import com.gala.motetv.ui.theme.SkipAdAccent
import com.gala.motetv.ui.theme.SkipAdAccentDark
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography
import com.google.android.apps.tv.remote.protocol.Direction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var isPlaying by remember { mutableStateOf(false) }
    var skipAdFeedback by remember { mutableStateOf<String?>(null) }
    var autoSkipEnabled by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BackgroundLight,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                TvStatusCard(
                    device = device,
                    connectionState = connectionState,
                    onOpenDevices = onOpenDevices,
                    onReconnect = onReconnect,
                    onPowerClick = {
                        onSendKey(AndroidTvKeyCodes.KEYCODE_POWER, Direction.SHORT)
                    }
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = "Media Controller",
                style = Typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.align(Alignment.Start)
            )

            // Local Media Casting (VLC)
            if (castController != null) {
                LocalMediaCastCard(
                    castController = castController,
                    sessionState = sessionState,
                    onPickMediaClick = onPickMediaClick
                )
            }

            // Primary Playback Surface
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("media_controls_surface"),
                shape = RoundedCornerShape(24.dp),
                elevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Now Playing Control",
                        style = Typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Controls media playback on ${device?.name ?: "TV"}",
                        style = Typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Transport buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous Track
                        IconButton(
                            onClick = {
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_PREVIOUS, Direction.SHORT)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
                                .testTag("btn_media_prev")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = TextPrimary
                            )
                        }

                        // Rewind
                        IconButton(
                            onClick = {
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_REWIND, Direction.SHORT)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
                                .testTag("btn_media_rw")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = "Rewind",
                                tint = TextPrimary
                            )
                        }

                        // Play/Pause Big Center Button
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .shadow(6.dp, CircleShape, spotColor = Color(0x335B6CFF))
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(ElectricBluePrimary, ElectricBlueDark)
                                    )
                                )
                                .border(1.5.dp, ElectricBlueLight, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = {
                                    isPlaying = !isPlaying
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE, Direction.SHORT)
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("btn_media_play_pause_hero")
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        // Fast Forward
                        IconButton(
                            onClick = {
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_FAST_FORWARD, Direction.SHORT)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
                                .testTag("btn_media_ff")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "Fast Forward",
                                tint = TextPrimary
                            )
                        }

                        // Next Track
                        IconButton(
                            onClick = {
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_NEXT, Direction.SHORT)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
                                .testTag("btn_media_next")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Stop button
                    IconButton(
                        onClick = {
                            isPlaying = false
                            onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_STOP, Direction.SHORT)
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(GlassSurface)
                            .border(1.dp, GlassBorder, CircleShape)
                            .testTag("btn_media_stop")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Dedicated YouTube SKIP AD Hero Controller
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("youtube_skip_ad_card"),
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
                        Column {
                            Text(
                                text = "YouTube Skip Ad",
                                style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "Instantly skips skippable YouTube ads",
                                style = Typography.bodySmall,
                                color = TextSecondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(SkipAdAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = null,
                                tint = SkipAdAccentDark,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Big Manual Skip Ad Button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .shadow(2.dp, RoundedCornerShape(16.dp), spotColor = Color(0x33FF9800))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(SkipAdAccent, SkipAdAccentDark)
                                )
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                TvLogger.i(TvLogger.TAG_REMOTE, "[YOUTUBE_SKIP_AD] Media Screen: Skip Ad pressed")
                                skipAdFeedback = "Sent Skip Ad command sequence to TV"
                                coroutineScope.launch {
                                    // Android TV YouTube player sequence:
                                    // Focus ad button (UP) and press SELECT (CENTER)
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_UP, Direction.SHORT)
                                    delay(160)
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER, Direction.SHORT)
                                    delay(2000)
                                    skipAdFeedback = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Skip Ad",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SKIP YOUTUBE AD",
                                    style = Typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (skipAdFeedback != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = skipAdFeedback ?: "",
                            style = Typography.labelSmall,
                            color = SkipAdAccentDark
                        )
                    }
                }
            }

            // Volume Controls Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                elevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TV Volume",
                            style = Typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Adjust TV audio output",
                            style = Typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_MUTE, Direction.SHORT)
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(GlassSurface)
                                .border(1.dp, GlassBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeMute,
                                contentDescription = "Mute",
                                tint = TextSecondary
                            )
                        }

                        IconButton(
                            onClick = {
                                onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_DOWN, Direction.SHORT)
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeDown,
                                contentDescription = "Volume Down",
                                tint = TextPrimary
                            )
                        }

                        IconButton(
                            onClick = {
                                onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_UP, Direction.SHORT)
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Volume Up",
                                tint = TextPrimary
                            )
                        }
                    }
                }
            }

            // Automatic Ad Skipping Architecture Explanation (Phase 12)
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                backgroundColor = GlassSurfaceSubtle,
                elevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = ElectricBluePrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Auto Skip Ad (TV Companion)",
                                style = Typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )
                        }

                        Switch(
                            checked = autoSkipEnabled,
                            onCheckedChange = { autoSkipEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ElectricBluePrimary,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = GlassSurfaceElevated
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "The phone-side Android TV Remote v2 protocol communicates keystrokes and app links over TCP 6466 without direct pixel or video playback inspection. To automatically trigger Skip Ad the exact instant it appears on your TV screen, an optional MoteTV Accessibility Companion service on the TV detects the legitimate Skip Ad button and clicks it without bypassing unskippable ads.",
                        style = Typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
