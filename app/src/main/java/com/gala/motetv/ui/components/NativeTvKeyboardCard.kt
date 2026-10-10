package com.gala.motetv.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.ui.theme.ElectricBlueDark
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.StatusSuccessGreen
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeTvKeyboardCard(
    imeActive: Boolean,
    lastImeText: String,
    onSendImeText: (String, Boolean) -> Unit,
    onSendKey: (Int) -> Unit,
    onSendChar: (Char) -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var useDirectKeyCodes by remember { mutableStateOf(false) }
    var showSoftKeyboardLayout by remember { mutableStateOf(false) }

    val quickSearches = remember {
        listOf(
            "Action Movies",
            "YouTube 4K",
            "Music Videos",
            "Latest News",
            "Relaxing Ambience",
            "Anime",
            "Tech Reviews"
        )
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("card_native_tv_keyboard"),
        shape = RoundedCornerShape(22.dp),
        elevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: IME Status Indicator & Mode Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (imeActive) StatusSuccessGreen.copy(alpha = 0.15f) else ElectricBluePrimary.copy(alpha = 0.12f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "Keyboard",
                            tint = if (imeActive) StatusSuccessGreen else ElectricBluePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "TV Keyboard & IME",
                            style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = if (imeActive) "TV Input Field Connected" else "Android TV Remote v2 IME",
                            style = Typography.labelSmall,
                            color = if (imeActive) StatusSuccessGreen else TextSecondary
                        )
                    }
                }

                // Mode toggle chip: IME vs Direct Keys
                FilterChip(
                    selected = useDirectKeyCodes,
                    onClick = {
                        useDirectKeyCodes = !useDirectKeyCodes
                        onHaptic()
                    },
                    label = {
                        Text(
                            text = if (useDirectKeyCodes) "Direct Keys" else "Protocol IME",
                            style = Typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricBluePrimary.copy(alpha = 0.15f),
                        selectedLabelColor = ElectricBluePrimary,
                        containerColor = GlassSurface
                    ),
                    modifier = Modifier.height(30.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Input Text Field Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { newValue ->
                        inputText = newValue
                    },
                    placeholder = {
                        Text(
                            text = if (lastImeText.isNotBlank()) lastImeText else "Type text to send to TV...",
                            color = TextMuted
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricBluePrimary,
                        unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        if (inputText.isNotEmpty()) {
                            IconButton(onClick = { inputText = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_tv_keyboard_text")
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Send Button
                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onHaptic()
                            onSendImeText(inputText, useDirectKeyCodes)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("btn_send_tv_keyboard"),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Control Strip: Paste, Space, Backspace, Enter, Soft Keys Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Paste from clipboard
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val item = clipboard.primaryClip?.getItemAt(0)
                        val text = item?.text?.toString()
                        if (!text.isNullOrBlank()) {
                            inputText = text
                            onHaptic()
                        }
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Paste", style = Typography.labelSmall, color = TextPrimary)
                }

                // Space
                OutlinedButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_SPACE)
                        inputText += " "
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.SpaceBar, contentDescription = "Space", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Space", style = Typography.labelSmall, color = TextPrimary)
                }

                // Delete / Backspace
                OutlinedButton(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_DEL)
                        if (inputText.isNotEmpty()) {
                            inputText = inputText.dropLast(1)
                        }
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Backspace, contentDescription = "Delete", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Del", style = Typography.labelSmall, color = TextPrimary)
                }

                // Enter / Submit
                Button(
                    onClick = {
                        onHaptic()
                        onSendKey(AndroidTvKeyCodes.KEYCODE_ENTER)
                    },
                    modifier = Modifier.weight(1.1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlueDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.KeyboardReturn, contentDescription = "Enter", tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Enter", style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Search Suggestion Chips
            Text(
                text = "Quick Searches",
                style = Typography.labelSmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                quickSearches.forEach { phrase ->
                    SuggestionChip(
                        onClick = {
                            onHaptic()
                            onSendImeText(phrase, useDirectKeyCodes)
                        },
                        label = { Text(phrase, style = Typography.labelSmall) },
                        icon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = GlassSurfaceElevated
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = GlassBorder
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Soft On-Screen QWERTY Keyboard Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showSoftKeyboardLayout) "Hide On-Screen Keyboard" else "Show On-Screen Keyboard",
                    style = Typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = ElectricBluePrimary,
                    modifier = Modifier.clickable { showSoftKeyboardLayout = !showSoftKeyboardLayout }
                )
            }

            AnimatedVisibility(
                visible = showSoftKeyboardLayout,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Numbers row
                    QwertyRow(
                        chars = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0'),
                        onCharClick = {
                            onHaptic()
                            onSendChar(it)
                            inputText += it
                        }
                    )
                    // Row 1
                    QwertyRow(
                        chars = listOf('Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'),
                        onCharClick = {
                            onHaptic()
                            onSendChar(it)
                            inputText += it
                        }
                    )
                    // Row 2
                    QwertyRow(
                        chars = listOf('A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'),
                        onCharClick = {
                            onHaptic()
                            onSendChar(it)
                            inputText += it
                        }
                    )
                    // Row 3
                    QwertyRow(
                        chars = listOf('Z', 'X', 'C', 'V', 'B', 'N', 'M'),
                        onCharClick = {
                            onHaptic()
                            onSendChar(it)
                            inputText += it
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QwertyRow(
    chars: List<Char>,
    onCharClick: (Char) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        chars.forEach { char ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(width = 30.dp, height = 38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GlassSurfaceElevated)
                    .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = ElectricBluePrimary.copy(alpha = 0.2f)),
                        onClick = { onCharClick(char) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = char.toString(),
                    style = Typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    color = TextPrimary
                )
            }
        }
    }
}
