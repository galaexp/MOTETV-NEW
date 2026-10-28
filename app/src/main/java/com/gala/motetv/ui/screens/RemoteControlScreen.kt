package com.gala.motetv.ui.screens

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.launcher.AppDefinition
import com.gala.motetv.launcher.DefaultApps
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.theme.*
import com.google.android.apps.tv.remote.protocol.Direction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
                vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18)
            }
        } catch (_: Exception) {}
    }

    var selectedModeTab by remember { mutableIntStateOf(0) } // 0 = D-Pad, 1 = Touchpad
    var showKeyboardInput by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var skipAdFeedback by remember { mutableStateOf<String?>(null) }
    val isConnected = connectionState is ConnectionState.Ready

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // TOP HEADER: TV Connection Bar & Power
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // TV Status Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenDevices() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) StatusSuccessGreen else StatusErrorRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isConnected) (device?.name ?: "Connected TV") else "TV Disconnected",
                            style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isConnected) "Tap to switch TV" else "Tap to pair",
                            style = Typography.labelSmall.copy(fontSize = 11.sp),
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                // Quick Power & Input Actions
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // TV Input Button
                    RemoteIconButton(
                        icon = Icons.Default.Tv,
                        contentDescription = "TV Input",
                        onClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_TV_INPUT, Direction.SHORT)
                        },
                        size = 40.dp
                    )

                    // Power Button with glowing crimson circle
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFFDC2626), Color(0xFF991B1B))
                                )
                            )
                            .border(1.dp, Color(0xFFF87171), CircleShape)
                            .clickable {
                                performHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_POWER, Direction.SHORT)
                            }
                            .testTag("btn_power"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // REMOTE BODY SHELL
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(36.dp), spotColor = Color(0x66000000))
                .clip(RoundedCornerShape(36.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF162032),
                            Color(0xFF0F172A),
                            Color(0xFF0B1120)
                        )
                    )
                )
                .border(1.5.dp, Color(0x3338BDF8), RoundedCornerShape(36.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Controller Mode Switcher (D-Pad vs Touchpad)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF090D16))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    ModeTabButton(
                        title = "Tactile D-Pad",
                        icon = Icons.Default.Navigation,
                        isSelected = selectedModeTab == 0,
                        onClick = {
                            performHaptic()
                            selectedModeTab = 0
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    ModeTabButton(
                        title = "Gesture Touchpad",
                        icon = Icons.Default.TouchApp,
                        isSelected = selectedModeTab == 1,
                        onClick = {
                            performHaptic()
                            selectedModeTab = 1
                        }
                    )
                }

                // MAIN CONTROLLER SURFACE
                if (selectedModeTab == 0) {
                    CyberTactileDpad(
                        onDirectionClick = { keyCode ->
                            performHaptic()
                            onSendKey(keyCode, Direction.SHORT)
                        },
                        modifier = Modifier.testTag("remote_dpad")
                    )
                } else {
                    CyberTouchpad(
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

                // CORE NAVIGATION ROW (Back, Home, Menu, Keyboard)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularRemoteButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = "Back",
                        onClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_BACK, Direction.SHORT)
                        },
                        testTag = "btn_nav_back"
                    )

                    CircularRemoteButton(
                        icon = Icons.Default.Home,
                        label = "Home",
                        isHero = true,
                        onClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_HOME, Direction.SHORT)
                        },
                        testTag = "btn_nav_home"
                    )

                    CircularRemoteButton(
                        icon = Icons.Default.Menu,
                        label = "Menu",
                        onClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_MENU, Direction.SHORT)
                        },
                        testTag = "btn_nav_menu"
                    )

                    CircularRemoteButton(
                        icon = Icons.Default.Keyboard,
                        label = "Type",
                        onClick = {
                            performHaptic()
                            showKeyboardInput = !showKeyboardInput
                        },
                        testTag = "btn_nav_keyboard"
                    )
                }

                // SMART SKIP AD ACTION PILL (Fixed to actually skip ads)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .shadow(4.dp, RoundedCornerShape(20.dp), spotColor = Color(0x66F59E0B))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFF59E0B), Color(0xFFD97706))
                            )
                        )
                        .border(1.dp, Color(0xFFFEF3C7), RoundedCornerShape(20.dp))
                        .clickable {
                            performHaptic()
                            skipAdFeedback = "Skipping TV Ad..."
                            coroutineScope.launch {
                                // Real Android TV YouTube Skip sequence:
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_NEXT, Direction.SHORT)
                                delay(120)
                                onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT, Direction.SHORT)
                                delay(140)
                                onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER, Direction.SHORT)
                                delay(120)
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_FAST_FORWARD, Direction.SHORT)
                                delay(2000)
                                skipAdFeedback = null
                            }
                        }
                        .padding(vertical = 12.dp, horizontal = 16.dp)
                        .testTag("btn_skip_ad"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Skip Ad",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "SKIP TV AD",
                                style = Typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp
                                ),
                                color = Color.White
                            )
                            Text(
                                text = "Smart one-tap skip for YouTube & TV video ads",
                                style = Typography.labelSmall.copy(fontSize = 10.sp),
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }

                if (skipAdFeedback != null) {
                    Text(
                        text = skipAdFeedback ?: "",
                        style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF59E0B)
                    )
                }

                // PHYSICAL-STYLE ROCKERS (Volume & Channel)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Rocker: Volume Pillar
                    VerticalRockerPillar(
                        topIcon = Icons.AutoMirrored.Filled.VolumeUp,
                        topLabel = "VOL +",
                        onTopClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_UP, Direction.SHORT)
                        },
                        centerIcon = Icons.AutoMirrored.Filled.VolumeMute,
                        centerLabel = "Mute",
                        onCenterClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_MUTE, Direction.SHORT)
                        },
                        bottomIcon = Icons.AutoMirrored.Filled.VolumeDown,
                        bottomLabel = "VOL -",
                        onBottomClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_DOWN, Direction.SHORT)
                        }
                    )

                    // Center Media Controls (Play / Pause, Rewind, FastForward)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color(0xFF0284C7), Color(0xFF0369A1))
                                    )
                                )
                                .border(1.5.dp, Color(0xFF38BDF8), CircleShape)
                                .clickable {
                                    performHaptic()
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE, Direction.SHORT)
                                }
                                .testTag("btn_media_play_pause"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play / Pause",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MiniPlaybackButton(
                                icon = Icons.Default.FastRewind,
                                onClick = {
                                    performHaptic()
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_REWIND, Direction.SHORT)
                                }
                            )
                            MiniPlaybackButton(
                                icon = Icons.Default.FastForward,
                                onClick = {
                                    performHaptic()
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_FAST_FORWARD, Direction.SHORT)
                                }
                            )
                        }
                    }

                    // Right Rocker: Channel / Page Pillar
                    VerticalRockerPillar(
                        topIcon = Icons.Default.KeyboardArrowUp,
                        topLabel = "CH +",
                        onTopClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_PAGE_UP, Direction.SHORT)
                        },
                        centerIcon = Icons.Default.Info,
                        centerLabel = "Info",
                        onCenterClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_INFO, Direction.SHORT)
                        },
                        bottomIcon = Icons.Default.KeyboardArrowDown,
                        bottomLabel = "CH -",
                        onBottomClick = {
                            performHaptic()
                            onSendKey(AndroidTvKeyCodes.KEYCODE_PAGE_DOWN, Direction.SHORT)
                        }
                    )
                }

                // QUICK APP LAUNCH BAR
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "QUICK TV APPS",
                        style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = Color(0xFF64748B)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppQuickLaunchPill(
                            name = "YouTube",
                            color = Color(0xFFEF4444),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                performHaptic()
                                onLaunchUrl("https://www.youtube.com")
                            }
                        )
                        AppQuickLaunchPill(
                            name = "Netflix",
                            color = Color(0xFFE50914),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                performHaptic()
                                onLaunchUrl("https://www.netflix.com")
                            }
                        )
                        AppQuickLaunchPill(
                            name = "Prime",
                            color = Color(0xFF00A8E1),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                performHaptic()
                                onLaunchUrl("https://app.primevideo.com")
                            }
                        )
                        AppQuickLaunchPill(
                            name = "Spotify",
                            color = Color(0xFF1DB954),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                performHaptic()
                                onLaunchUrl("spotify:")
                            }
                        )
                    }
                }
            }
        }

        // KEYBOARD INPUT CARD
        AnimatedVisibility(
            visible = showKeyboardInput,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Virtual TV Keyboard",
                        style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )

                    OutlinedTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_keyboard"),
                        placeholder = { Text("Search movie, show, or URL...", color = Color(0xFF64748B)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF090D16),
                            unfocusedContainerColor = Color(0xFF090D16)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (keyboardText.isNotEmpty()) {
                                    onSendImeText(keyboardText)
                                    keyboardText = ""
                                    showKeyboardInput = false
                                }
                            }
                        )
                    )

                    Button(
                        onClick = {
                            if (keyboardText.isNotEmpty()) {
                                onSendImeText(keyboardText)
                                keyboardText = ""
                                showKeyboardInput = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Send Text to TV", style = Typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

@Composable
private fun CyberTactileDpad(
    onDirectionClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(250.dp)
            .shadow(12.dp, CircleShape, spotColor = Color(0x66000000))
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        Color(0xFF1E293B),
                        Color(0xFF0F172A),
                        Color(0xFF090D16)
                    )
                )
            )
            .border(2.dp, Color(0x4D38BDF8), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // UP
        IconButton(
            onClick = { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_UP) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .size(54.dp)
                .testTag("dpad_up")
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Up",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        // DOWN
        IconButton(
            onClick = { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(54.dp)
                .testTag("dpad_down")
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Down",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        // LEFT
        IconButton(
            onClick = { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_LEFT) },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp)
                .size(54.dp)
                .testTag("dpad_left")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Left",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }

        // RIGHT
        IconButton(
            onClick = { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT) },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp)
                .size(54.dp)
                .testTag("dpad_right")
        ) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "Right",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }

        // CENTER OK BUTTON
        Box(
            modifier = Modifier
                .size(88.dp)
                .shadow(8.dp, CircleShape, spotColor = Color(0x660284C7))
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0xFF0284C7),
                            Color(0xFF0369A1)
                        )
                    )
                )
                .border(2.dp, Color(0xFF38BDF8), CircleShape)
                .clickable { onDirectionClick(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER) }
                .testTag("dpad_center"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "OK",
                style = Typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                ),
                color = Color.White
            )
        }
    }
}

@Composable
private fun CyberTouchpad(
    onSwipe: (Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var totalDragY by remember { mutableFloatStateOf(0f) }
    val threshold = 70f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF1E293B), Color(0xFF090D16))
                )
            )
            .border(1.5.dp, Color(0x4D38BDF8), RoundedCornerShape(28.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        totalDragX = 0f
                        totalDragY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y

                        if (abs(totalDragX) > threshold) {
                            if (totalDragX > 0) {
                                onSwipe(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT)
                            } else {
                                onSwipe(AndroidTvKeyCodes.KEYCODE_DPAD_LEFT)
                            }
                            totalDragX = 0f
                        } else if (abs(totalDragY) > threshold) {
                            if (totalDragY > 0) {
                                onSwipe(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN)
                            } else {
                                onSwipe(AndroidTvKeyCodes.KEYCODE_DPAD_UP)
                            }
                            totalDragY = 0f
                        }
                    }
                )
            }
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(42.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Swipe to Navigate • Tap to Select",
                style = Typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun ModeTabButton(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) Color(0xFF0284C7) else Color.Transparent
    val tc = if (isSelected) Color.White else Color(0xFF94A3B8)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tc, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(title, style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = tc)
        }
    }
}

@Composable
private fun CircularRemoteButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHero: Boolean = false,
    testTag: String = ""
) {
    val bg = if (isHero) Color(0xFF0284C7) else Color(0xFF1E293B)
    val border = if (isHero) Color(0xFF38BDF8) else Color(0xFF334155)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, border, CircleShape)
                .clickable { onClick() }
                .testTag(testTag),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(text = label, style = Typography.labelSmall.copy(fontSize = 11.sp), color = Color(0xFF94A3B8))
    }
}

@Composable
private fun VerticalRockerPillar(
    topIcon: ImageVector,
    topLabel: String,
    onTopClick: () -> Unit,
    centerIcon: ImageVector,
    centerLabel: String,
    onCenterClick: () -> Unit,
    bottomIcon: ImageVector,
    bottomLabel: String,
    onBottomClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF090D16))
            .border(1.5.dp, Color(0xFF1E293B), RoundedCornerShape(26.dp))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onTopClick, modifier = Modifier.size(48.dp)) {
            Icon(topIcon, contentDescription = topLabel, tint = Color.White, modifier = Modifier.size(24.dp))
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF1E293B))
                .clickable { onCenterClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(centerIcon, contentDescription = centerLabel, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
        }

        IconButton(onClick = onBottomClick, modifier = Modifier.size(48.dp)) {
            Icon(bottomIcon, contentDescription = bottomLabel, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun MiniPlaybackButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFF334155), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RemoteIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 40.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFF334155), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AppQuickLaunchPill(
    name: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
    }
}
