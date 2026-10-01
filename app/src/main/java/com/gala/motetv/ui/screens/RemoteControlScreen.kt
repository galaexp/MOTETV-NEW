package com.gala.motetv.ui.screens

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.protocol.androidtv.model.AndroidTvKeyCodes
import com.gala.motetv.protocol.androidtv.model.KeyDirection
import com.gala.motetv.ui.components.ConnectionStatusBadge
import com.gala.motetv.ui.components.DiagnosticsBottomSheet
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.theme.ElectricBlueDark
import com.gala.motetv.ui.theme.ElectricBlueLight
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassBorderStrong
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.NavyBackgroundDark
import com.gala.motetv.ui.theme.StatusErrorRed
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@Composable
fun RemoteControlScreen(
    device: TvDevice?,
    connectionState: ConnectionState,
    onSendKey: (Int, KeyDirection) -> Unit,
    onSendImeText: (String) -> Unit,
    onOpenDevices: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vibrator = remember {
        try {
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        } catch (_: Exception) {
            null
        }
    }

    fun performHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18)
            }
        } catch (_: Exception) {}
    }

    var selectedModeTab by remember { mutableIntStateOf(0) } // 0 = D-Pad, 1 = Touchpad
    var showDiagnostics by remember { mutableStateOf(false) }
    var showKeyboardInput by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = NavyBackgroundDark,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(ElectricBluePrimary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = ElectricBluePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = device?.name ?: "No TV Connected",
                            style = Typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        ConnectionStatusBadge(
                            connectionState = connectionState,
                            onRetry = onReconnect
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showDiagnostics = true },
                        modifier = Modifier.testTag("btn_top_diagnostics")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Diagnostics",
                            tint = TextSecondary
                        )
                    }
                    IconButton(
                        onClick = onOpenDevices,
                        modifier = Modifier.testTag("btn_top_devices")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Switch TV",
                            tint = TextSecondary
                        )
                    }
                    IconButton(
                        onClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_POWER, KeyDirection.SHORT)
                        },
                        modifier = Modifier.testTag("btn_power_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = StatusErrorRed
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Selector: D-Pad vs Touchpad
            TabRow(
                selectedTabIndex = selectedModeTab,
                containerColor = GlassSurface,
                contentColor = ElectricBluePrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedModeTab]),
                        color = ElectricBluePrimary,
                        height = 3.dp
                    )
                }
            ) {
                Tab(
                    selected = selectedModeTab == 0,
                    onClick = { selectedModeTab = 0 },
                    text = { Text("D-Pad Remote", style = Typography.labelLarge) },
                    modifier = Modifier.testTag("tab_dpad")
                )
                Tab(
                    selected = selectedModeTab == 1,
                    onClick = { selectedModeTab = 1 },
                    text = { Text("Touchpad Swipe", style = Typography.labelLarge) },
                    modifier = Modifier.testTag("tab_touchpad")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Control Area
            if (selectedModeTab == 0) {
                // Tactical Glass D-Pad
                GlassDpad(
                    onDirectionClick = { keyCode ->
                        performHaptic()
                        onSendKey(keyCode, KeyDirection.SHORT)
                    },
                    modifier = Modifier.testTag("remote_dpad")
                )
            } else {
                // Smooth Gesture Touchpad
                TouchpadSurface(
                    onSwipe = { keyCode ->
                        performHaptic()
                        onSendKey(keyCode, KeyDirection.SHORT)
                    },
                    onTap = {
                        performHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER, KeyDirection.SHORT)
                    },
                    modifier = Modifier.testTag("remote_touchpad")
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Primary Navigation Cluster (Back, Home, Menu, Mic)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = "Back",
                    testTag = "btn_nav_back",
                    onClick = {
                        performHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_BACK, KeyDirection.SHORT)
                    }
                )
                NavCircleButton(
                    icon = Icons.Default.Home,
                    label = "Home",
                    isPrimary = true,
                    testTag = "btn_nav_home",
                    onClick = {
                        performHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_HOME, KeyDirection.SHORT)
                    }
                )
                NavCircleButton(
                    icon = Icons.Default.Mic,
                    label = "Assistant",
                    testTag = "btn_nav_mic",
                    onClick = {
                        performHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_ASSIST, KeyDirection.SHORT)
                    }
                )
                NavCircleButton(
                    icon = Icons.Default.Keyboard,
                    label = "Keyboard",
                    testTag = "btn_nav_keyboard",
                    onClick = {
                        showKeyboardInput = !showKeyboardInput
                    }
                )
            }

            // Keyboard input drawer
            AnimatedVisibility(
                visible = showKeyboardInput,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = keyboardText,
                            onValueChange = { keyboardText = it },
                            placeholder = { Text("Type text to send to TV...") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = ElectricBluePrimary,
                                unfocusedBorderColor = GlassBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_ime_text")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (keyboardText.isNotBlank()) {
                                    performHaptic()
                                    onSendImeText(keyboardText)
                                    keyboardText = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ElectricBluePrimary)
                                .testTag("btn_send_ime")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send Text",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Volume & Media Playback Cluster
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_media_controls"),
                shape = RoundedCornerShape(20.dp),
                backgroundColor = GlassSurface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Media controls
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(
                            onClick = {
                                performHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_REWIND, KeyDirection.SHORT)
                            },
                            modifier = Modifier.testTag("btn_media_rewind")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = "Rewind",
                                tint = TextPrimary
                            )
                        }
                        IconButton(
                            onClick = {
                                performHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE, KeyDirection.SHORT)
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(ElectricBluePrimary)
                                .testTag("btn_media_play_pause")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = {
                                performHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_FAST_FORWARD, KeyDirection.SHORT)
                            },
                            modifier = Modifier.testTag("btn_media_fast_forward")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "Fast Forward",
                                tint = TextPrimary
                            )
                        }
                    }

                    // Volume controls
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                performHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_MUTE, KeyDirection.SHORT)
                            },
                            modifier = Modifier.testTag("btn_vol_mute")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeMute,
                                contentDescription = "Mute",
                                tint = TextSecondary
                            )
                        }
                        IconButton(
                            onClick = {
                                performHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_DOWN, KeyDirection.SHORT)
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .testTag("btn_vol_down")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Volume Down",
                                tint = TextPrimary
                            )
                        }
                        IconButton(
                            onClick = {
                                performHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_UP, KeyDirection.SHORT)
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .testTag("btn_vol_up")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Volume Up",
                                tint = TextPrimary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Quick App Launchers Grid
            Text(
                text = "Quick App Launchers",
                style = Typography.titleSmall,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppShortcutButton(
                    name = "YouTube",
                    color = Color(0xFFFF0000),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        performHaptic()
                        onSendImeText("https://youtube.com")
                    }
                )
                AppShortcutButton(
                    name = "Netflix",
                    color = Color(0xFFE50914),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        performHaptic()
                        onSendImeText("netflix")
                    }
                )
                AppShortcutButton(
                    name = "Prime",
                    color = Color(0xFF00A8E1),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        performHaptic()
                        onSendImeText("prime video")
                    }
                )
                AppShortcutButton(
                    name = "Spotify",
                    color = Color(0xFF1DB954),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        performHaptic()
                        onSendImeText("spotify")
                    }
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    if (showDiagnostics) {
        DiagnosticsBottomSheet(onDismiss = { showDiagnostics = false })
    }
}

@Composable
fun GlassDpad(
    onDirectionClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(240.dp)
            .clip(CircleShape)
            .background(GlassSurface)
            .border(1.5.dp, GlassBorderStrong, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // UP
        IconButton(
            onClick = { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_UP) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .size(52.dp)
                .testTag("dpad_up")
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = "Up",
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }

        // DOWN
        IconButton(
            onClick = { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(52.dp)
                .testTag("dpad_down")
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = "Down",
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }

        // LEFT
        IconButton(
            onClick = { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_LEFT) },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp)
                .size(52.dp)
                .testTag("dpad_left")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Left",
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }

        // RIGHT
        IconButton(
            onClick = { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT) },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp)
                .size(52.dp)
                .testTag("dpad_right")
        ) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "Right",
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }

        // CENTER / OK
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(ElectricBluePrimary, ElectricBlueDark)
                    )
                )
                .border(1.dp, ElectricBlueLight, CircleShape)
                .testTag("dpad_center"),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER) },
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "OK",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun TouchpadSurface(
    onSwipe: (Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragAccumX by remember { mutableFloatStateOf(0f) }
    var dragAccumY by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = 45f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GlassSurface)
            .border(1.5.dp, GlassBorderStrong, RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragAccumX = 0f
                        dragAccumY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumX += dragAmount.x
                        dragAccumY += dragAmount.y

                        if (dragAccumX > swipeThreshold) {
                            onSwipe(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT)
                            dragAccumX = 0f
                        } else if (dragAccumX < -swipeThreshold) {
                            onSwipe(AndroidTvKeyCodes.KEYCODE_DPAD_LEFT)
                            dragAccumX = 0f
                        }

                        if (dragAccumY > swipeThreshold) {
                            onSwipe(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN)
                            dragAccumY = 0f
                        } else if (dragAccumY < -swipeThreshold) {
                            onSwipe(AndroidTvKeyCodes.KEYCODE_DPAD_UP)
                            dragAccumY = 0f
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                tint = ElectricBluePrimary.copy(alpha = 0.6f),
                modifier = Modifier.size(42.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Swipe to navigate • Tap to select",
                style = Typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun NavCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    isPrimary: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                    if (isPrimary) {
                        Brush.linearGradient(listOf(ElectricBluePrimary, ElectricBlueDark))
                    } else {
                        Brush.linearGradient(listOf(GlassSurfaceElevated, GlassSurface))
                    }
                )
                .border(1.dp, if (isPrimary) ElectricBlueLight else GlassBorder, CircleShape)
                .testTag(testTag)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isPrimary) Color.White else TextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = Typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
fun AppShortcutButton(
    name: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .testTag("btn_app_${name.lowercase()}"),
        shape = RoundedCornerShape(14.dp),
        onClick = onClick
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name,
                style = Typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = color
            )
        }
    }
}
