package com.gala.motetv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.ui.theme.ElectricBlueDark
import com.gala.motetv.ui.theme.ElectricBlueLight
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassBorderStrong
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography
import kotlin.math.roundToInt

@Composable
fun MousePointerSurface(
    onSendKey: (Int) -> Unit,
    onLaunchUrl: (String) -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pointer state
    var cursorPosition by remember { mutableStateOf(Offset(200f, 150f)) }
    var isPointerActive by remember { mutableStateOf(false) }

    // Sensitivity multiplier: 0 = Normal (1x), 1 = Fast (1.5x), 2 = High Speed (2.5x)
    var sensitivityIndex by remember { mutableIntStateOf(0) }
    val sensitivityThreshold = when (sensitivityIndex) {
        1 -> 24f
        2 -> 14f
        else -> 36f
    }

    var showOpenUrlDialog by remember { mutableStateOf(false) }
    var customUrlText by remember { mutableStateOf("https://") }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Sensitivity Selector & Status Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AdsClick,
                    contentDescription = null,
                    tint = ElectricBluePrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Browser Mouse Mode",
                    style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }

            // Sensitivity Chips
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("1x Normal", "1.5x Fast", "2.5x Hyper").forEachIndexed { idx, label ->
                    FilterChip(
                        selected = sensitivityIndex == idx,
                        onClick = {
                            sensitivityIndex = idx
                            onHaptic()
                        },
                        label = { Text(label, style = Typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricBluePrimary.copy(alpha = 0.15f),
                            selectedLabelColor = ElectricBluePrimary,
                            containerColor = GlassSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = sensitivityIndex == idx,
                            borderColor = if (sensitivityIndex == idx) ElectricBluePrimary else GlassBorder
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Main Trackpad Surface with Visual Cursor and Scroll Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Trackpad Box
            var dragAccumX by remember { mutableFloatStateOf(0f) }
            var dragAccumY by remember { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .shadow(3.dp, RoundedCornerShape(22.dp), spotColor = Color(0x1F0F172A))
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(GlassSurfaceElevated, GlassSurface)))
                    .border(1.5.dp, GlassBorderStrong, RoundedCornerShape(22.dp))
                    .pointerInput(sensitivityThreshold) {
                        detectTapGestures(
                            onTap = {
                                onHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER)
                            },
                            onLongPress = {
                                onHaptic()
                                onSendKey(AndroidTvKeyCodes.KEYCODE_MENU)
                            }
                        )
                    }
                    .pointerInput(sensitivityThreshold) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                cursorPosition = offset
                                isPointerActive = true
                                dragAccumX = 0f
                                dragAccumY = 0f
                            },
                            onDragEnd = {
                                isPointerActive = false
                            },
                            onDragCancel = {
                                isPointerActive = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                cursorPosition = Offset(
                                    x = (cursorPosition.x + dragAmount.x).coerceIn(10f, size.width.toFloat() - 10f),
                                    y = (cursorPosition.y + dragAmount.y).coerceIn(10f, size.height.toFloat() - 10f)
                                )

                                dragAccumX += dragAmount.x
                                dragAccumY += dragAmount.y

                                if (dragAccumX > sensitivityThreshold) {
                                    onHaptic()
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_RIGHT)
                                    dragAccumX = 0f
                                } else if (dragAccumX < -sensitivityThreshold) {
                                    onHaptic()
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_LEFT)
                                    dragAccumX = 0f
                                }

                                if (dragAccumY > sensitivityThreshold) {
                                    onHaptic()
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_DOWN)
                                    dragAccumY = 0f
                                } else if (dragAccumY < -sensitivityThreshold) {
                                    onHaptic()
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_UP)
                                    dragAccumY = 0f
                                }
                            }
                        )
                    }
                    .testTag("trackpad_mouse_surface")
            ) {
                // Background Trackpad Grid / Hint
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Touchpad Cursor",
                        style = Typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = TextSecondary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Glide finger to move pointer • Tap to Left-Click",
                        style = Typography.labelSmall,
                        color = TextMuted.copy(alpha = 0.7f)
                    )
                }

                // Smooth On-Screen Visual Pointer Indicator
                if (isPointerActive) {
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = (cursorPosition.x - 16f).roundToInt(),
                                    y = (cursorPosition.y - 16f).roundToInt()
                                )
                            }
                            .size(32.dp)
                            .shadow(4.dp, CircleShape, spotColor = ElectricBluePrimary)
                            .clip(CircleShape)
                            .background(ElectricBluePrimary.copy(alpha = 0.25f))
                            .border(2.dp, ElectricBluePrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ElectricBlueLight)
                        )
                    }
                }
            }

            // Dedicated Vertical Scroll Strip (Scroll Wheel)
            var scrollDragAccum by remember { mutableFloatStateOf(0f) }
            val scrollThreshold = 30f

            Column(
                modifier = Modifier
                    .width(52.dp)
                    .fillMaxHeight()
                    .shadow(2.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(GlassSurfaceElevated)
                    .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                scrollDragAccum += dragAmount.y
                                if (scrollDragAccum > scrollThreshold) {
                                    onHaptic()
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_PAGE_DOWN)
                                    scrollDragAccum = 0f
                                } else if (scrollDragAccum < -scrollThreshold) {
                                    onHaptic()
                                    onSendKey(AndroidTvKeyCodes.KEYCODE_PAGE_UP)
                                    scrollDragAccum = 0f
                                }
                            }
                        )
                    }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_PAGE_UP)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Scroll Up", tint = TextPrimary, modifier = Modifier.size(20.dp))
                }

                Text(
                    text = "SCROLL",
                    style = Typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = TextMuted
                )

                IconButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_PAGE_DOWN)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll Down", tint = TextPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Dedicated Mouse Click Action Buttons (Left Click, Middle Click, Right Click)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left Click (Primary Button)
            Button(
                onClick = {
                    onHaptic()
                    onSendKey(AndroidTvKeyCodes.KEYCODE_DPAD_CENTER)
                },
                modifier = Modifier
                    .weight(1.4f)
                    .height(46.dp)
                    .testTag("btn_mouse_left_click"),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "Left Click",
                    style = Typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }

            // Middle Click (Tab/Open link)
            OutlinedButton(
                onClick = {
                    onHaptic()
                    onSendKey(AndroidTvKeyCodes.KEYCODE_TAB)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .testTag("btn_mouse_mid_click"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "Tab / Link",
                    style = Typography.labelMedium,
                    color = TextPrimary
                )
            }

            // Right Click (Menu/Context)
            OutlinedButton(
                onClick = {
                    onHaptic()
                    onSendKey(AndroidTvKeyCodes.KEYCODE_MENU)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .testTag("btn_mouse_right_click"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Right Click Menu", tint = TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Menu",
                    style = Typography.labelMedium,
                    color = TextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Browser Navigation Toolbar: Back, Forward, Refresh, Search, Zoom In, Zoom Out
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_BACK)
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }

                IconButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_FORWARD)
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward", tint = TextPrimary)
                }

                IconButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_REFRESH)
                    }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                }

                IconButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_SEARCH)
                    }
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Address Bar", tint = ElectricBluePrimary)
                }

                IconButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_ZOOM_IN)
                    }
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = TextSecondary)
                }

                IconButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_ZOOM_OUT)
                    }
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = TextSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Launch TV Browsers / Open URL Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = { onLaunchUrl("market://launch?id=com.phlox.tvwebbrowser") },
                label = { Text("TV Bro Browser", style = Typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = GlassSurface)
            )

            AssistChip(
                onClick = { onLaunchUrl("market://launch?id=com.cloudmosa.puffinTV") },
                label = { Text("Puffin TV", style = Typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = GlassSurface)
            )

            AssistChip(
                onClick = { showOpenUrlDialog = !showOpenUrlDialog },
                label = { Text("Open URL...", style = Typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = GlassSurface)
            )
        }

        // Open URL Input Drawer
        AnimatedVisibility(visible = showOpenUrlDialog) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customUrlText,
                        onValueChange = { customUrlText = it },
                        label = { Text("Website URL") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBluePrimary,
                            unfocusedBorderColor = GlassBorder,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (customUrlText.isNotBlank()) {
                                onHaptic()
                                onLaunchUrl(customUrlText)
                                showOpenUrlDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Open", color = Color.White)
                    }
                }
            }
        }
    }
}
