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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.launcher.AppDefinition
import com.gala.motetv.launcher.DefaultApps
import com.gala.motetv.ui.components.DiagnosticsBottomSheet
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.components.MousePointerSurface
import com.gala.motetv.ui.components.NativeTvKeyboardCard
import com.gala.motetv.ui.components.QuickAppBar
import com.gala.motetv.ui.components.TvStatusCard
import com.gala.motetv.ui.theme.BackgroundLight
import com.gala.motetv.ui.theme.ElectricBlueDark
import com.gala.motetv.ui.theme.ElectricBlueLight
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassBorderStrong
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
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
fun RemoteControlScreen(
    device: TvDevice?,
    connectionState: ConnectionState,
    onSendKey: (Int, Direction) -> Unit,
    onSendImeText: (String) -> Unit,
    onSendImeTextWithFallback: (String, Boolean) -> Unit = { text, _ -> onSendImeText(text) },
    onSendChar: (Char) -> Unit = {},
    imeActive: Boolean = false,
    lastImeText: String = "",
    onOpenDevices: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    favoriteApps: List<AppDefinition> = DefaultApps.ALL_DEFAULT_APPS.filter { it.isFavorite },
    onLaunchApp: (AppDefinition) -> Unit = {},
    onLaunchUrl: (String) -> Unit = {},
    onPickMediaClick: () -> Unit = {},
    onOpenAllApps: () -> Unit = {},
    hapticEnabled: Boolean = true
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val vibrator = remember {
        try {
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        } catch (_: Exception) {
            null
        }
    }

    fun performHaptic() {
        if (!hapticEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(16, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(16)
            }
        } catch (_: Exception) {}
    }

    var selectedModeTab by remember { mutableIntStateOf(0) } // 0 = D-Pad, 1 = Touchpad
    var showDiagnostics by remember { mutableStateOf(false) }
    var showKeyboardInput by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var skipAdFeedback by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

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
                        performHaptic()
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
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Quick Favorite Apps bar
            if (favoriteApps.isNotEmpty()) {
                QuickAppBar(
                    favoriteApps = favoriteApps,
                    onAppClick = { app ->
                        performHaptic()
                        onLaunchApp(app)
                    },
                    onOpenAllApps = onOpenAllApps
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Mode Selector: D-Pad vs Touchpad vs Browser Mouse
            TabRow(
                selectedTabIndex = selectedModeTab,
                containerColor = GlassSurface,
                contentColor = ElectricBluePrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
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
                    text = {
                        Text(
                            text = "D-Pad",
                            style = Typography.labelLarge,
                            color = if (selectedModeTab == 0) ElectricBluePrimary else TextSecondary
                        )
                    },
                    modifier = Modifier.testTag("tab_dpad")
                )
                Tab(
                    selected = selectedModeTab == 1,
                    onClick = { selectedModeTab = 1 },
                    text = {
                        Text(
                            text = "Touchpad",
                            style = Typography.labelLarge,
                            color = if (selectedModeTab == 1) ElectricBluePrimary else TextSecondary
                        )
                    },
                    modifier = Modifier.testTag("tab_touchpad")
                )
                Tab(
                    selected = selectedModeTab == 2,
                    onClick = { selectedModeTab = 2 },
                    text = {
                        Text(
                            text = "Browser Mouse",
                            style = Typography.labelLarge,
                            color = if (selectedModeTab == 2) ElectricBluePrimary else TextSecondary
                        )
                    },
                    modifier = Modifier.testTag("tab_mouse")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Main Control Area: D-Pad, Touchpad, or Browser Mouse
            when (selectedModeTab) {
                0 -> {
                    TactileLightDpad(
                        onDirectionClick = { keyCode ->
                            performHaptic()
                            onSendKey(keyCode, Direction.SHORT)
                        },
                        modifier = Modifier.testTag("remote_dpad")
                    )
                }
                1 -> {
                    TouchpadSurface(
                        onSwipe = { keyCode ->
                            performHaptic()
                            onSendKey(keyCode, Direction.SHORT)
                        },
                        onTap = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER, Direction.SHORT)
                        },
                        modifier = Modifier.testTag("remote_touchpad")
                    )
                }
                2 -> {
                    MousePointerSurface(
                        onSendKey = { keyCode ->
                            onSendKey(keyCode, Direction.SHORT)
                        },
                        onLaunchUrl = onLaunchUrl,
                        onHaptic = { performHaptic() },
                        modifier = Modifier.testTag("remote_mouse_pointer")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dedicated Prominent YouTube SKIP AD Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .shadow(2.dp, RoundedCornerShape(18.dp), spotColor = Color(0x33FF9800))
                    .background(
                        Brush.horizontalGradient(
                            listOf(SkipAdAccent, SkipAdAccentDark)
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        // Standard click
                    }
                    .padding(vertical = 12.dp, horizontal = 18.dp)
                    .testTag("btn_skip_ad"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .padding(vertical = 2.dp)
                ) {
                    IconButton(
                        onClick = {
                            performHaptic()
                            TvLogger.i(TvLogger.TAG_REMOTE, "[YOUTUBE_SKIP_AD] Manual Skip Ad button activated")
                            skipAdFeedback = "Sent Skip Ad action to TV"
                            coroutineScope.launch {
                                // Protocol sequence for YouTube TV Skip Ad:
                                // UP to focus the Skip Ad button, followed by CENTER to press it.
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
                                text = "SKIP AD",
                                style = Typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            if (skipAdFeedback != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = skipAdFeedback ?: "",
                    style = Typography.labelSmall,
                    color = SkipAdAccentDark
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Primary Navigation Cluster (Back, Home, Menu, Mic, Keyboard)
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                elevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavCircleButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = "Back",
                        testTag = "btn_nav_back",
                        onClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_BACK, Direction.SHORT)
                        }
                    )
                    NavCircleButton(
                        icon = Icons.Default.Home,
                        label = "Home",
                        isPrimary = true,
                        testTag = "btn_nav_home",
                        onClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_HOME, Direction.SHORT)
                        }
                    )
                    NavCircleButton(
                        icon = Icons.Default.Mic,
                        label = "Voice",
                        testTag = "btn_nav_mic",
                        onClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_ASSIST, Direction.SHORT)
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
            }

            // Keyboard input drawer
            AnimatedVisibility(
                visible = showKeyboardInput || imeActive,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                NativeTvKeyboardCard(
                    imeActive = imeActive,
                    lastImeText = lastImeText,
                    onSendImeText = onSendImeTextWithFallback,
                    onSendKey = { onSendKey(it, Direction.SHORT) },
                    onSendChar = onSendChar,
                    onHaptic = { performHaptic() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Volume & Media Playback Cluster
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_media_controls"),
                shape = RoundedCornerShape(20.dp),
                elevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Media controls
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                performHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_REWIND, Direction.SHORT)
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
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE, Direction.SHORT)
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
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_FAST_FORWARD, Direction.SHORT)
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
                                onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_MUTE, Direction.SHORT)
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
                                onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_DOWN, Direction.SHORT)
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
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
                                onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_UP, Direction.SHORT)
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(GlassSurfaceElevated)
                                .border(1.dp, GlassBorder, CircleShape)
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

            Spacer(modifier = Modifier.height(14.dp))

            // Quick Media Cast Action
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_quick_cast_media"),
                shape = RoundedCornerShape(18.dp),
                elevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(ElectricBluePrimary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cast,
                                contentDescription = "Cast File",
                                tint = ElectricBluePrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Cast File to TV (VLC)",
                                style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "Direct Wi-Fi stream without screen mirroring",
                                style = Typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Button(
                        onClick = {
                            performHaptic()
                            onPickMediaClick()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(38.dp).testTag("btn_quick_cast_file")
                    ) {
                        Text(
                            text = "Select File",
                            style = Typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDiagnostics) {
        DiagnosticsBottomSheet(onDismiss = { showDiagnostics = false })
    }
}

@Composable
fun TactileLightDpad(
    onDirectionClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(240.dp)
            .shadow(4.dp, CircleShape, spotColor = Color(0x1F0F172A), ambientColor = Color(0x0F0F172A))
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(GlassSurfaceElevated, GlassSurface)
                )
            )
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
                .shadow(4.dp, CircleShape, spotColor = Color(0x335B6CFF))
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
    val swipeThreshold = 42f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .shadow(3.dp, RoundedCornerShape(24.dp), spotColor = Color(0x1F0F172A))
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(GlassSurfaceElevated, GlassSurface)
                )
            )
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(ElectricBluePrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = ElectricBluePrimary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Swipe to navigate • Tap to select",
                style = Typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
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
                .shadow(if (isPrimary) 3.dp else 1.dp, CircleShape, spotColor = Color(0x1F0F172A))
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
