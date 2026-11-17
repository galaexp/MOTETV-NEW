package com.gala.motetv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.tvhome.CleanYourTvThemeConfig
import com.gala.motetv.tvhome.CleanYourTvThemePreset
import com.gala.motetv.ui.theme.*

fun parseHexColorSafe(hex: String, fallback: Color): Color {
    return runCatching {
        Color(android.graphics.Color.parseColor(hex.trim()))
    }.getOrDefault(fallback)
}

@Composable
fun TvHomePreviewCard(
    config: CleanYourTvThemeConfig,
    modifier: Modifier = Modifier
) {
    val bg = parseHexColorSafe(config.backgroundColorHex, BackgroundDark)
    val accent = parseHexColorSafe(config.accentColorHex, ElectricBlueLight)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = bg,
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.7f)),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top TV status & clock preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CleanYourTV • ${config.themeId}",
                    style = Typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = accent
                )
                if (config.clockStyle != "hidden") {
                    Text(
                        text = if (config.clockFormat == "12h") "8:45 PM" else "20:45",
                        fontSize = if (config.clockStyle == "bold") 18.sp else 14.sp,
                        fontWeight = if (config.clockStyle == "bold") FontWeight.ExtraBold else FontWeight.Medium,
                        color = Color.White
                    )
                }
            }

            // App Grid Preview
            val cols = config.columns.coerceIn(3, 7)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 0 until cols) {
                    val isFocused = i == 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = if (isFocused) 0.18f else 0.08f))
                            .border(
                                width = if (isFocused) 1.5.dp else 1.dp,
                                color = if (isFocused) accent else Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp)
                            )
                    )
                }
            }

            // Bottom Dock Preview
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = if (config.dockBlur) 0.14f else 0.06f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(config.dockSlots.coerceIn(3, 8)) { idx ->
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(if (idx == 0) accent else Color.White.copy(alpha = 0.45f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ThemePresetChipCard(
    preset: CleanYourTvThemePreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val primary = parseHexColorSafe(preset.previewPrimaryHex, SurfaceCardDark)
    val accent = parseHexColorSafe(preset.previewSecondaryHex, ElectricBlueLight)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = primary,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) accent else GlassBorder),
        modifier = Modifier
            .width(148.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(
                    text = preset.name,
                    style = Typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = preset.subtitle,
                style = Typography.labelSmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${preset.defaultColumns} cols • ${preset.defaultAccentHex}",
                style = Typography.labelSmall,
                color = accent
            )
        }
    }
}
