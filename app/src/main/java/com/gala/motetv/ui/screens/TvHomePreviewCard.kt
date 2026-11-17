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
import androidx.compose.ui.graphics.Brush
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
    val skyAlpha = (config.skyIntensity.coerceIn(0, 100) / 100f) * 0.25f

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = bg,
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.7f)),
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = skyAlpha),
                            bg.copy(alpha = 0.92f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top TV status, greeting & clock_format preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val headerTitle = buildString {
                        append("CleanYourTV")
                        if (config.greetingName.isNotBlank()) {
                            append(" • Hi, ${config.greetingName.trim()}")
                        }
                        if (config.wallpaperEnabled) {
                            append(" • WP")
                        }
                    }
                    Text(
                        text = headerTitle,
                        style = Typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = when (config.clockFormat) {
                            "12" -> "8:45 PM"
                            "24" -> "20:45"
                            else -> "20:45 (Sys)"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                // App Grid Preview (columns + single_row + ambient_glow)
                val cols = config.columns.coerceIn(3, 7)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (i in 0 until cols) {
                            val isFocused = i == 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(if (config.singleRow) 46.dp else 32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isFocused && config.ambientGlow) accent.copy(alpha = 0.24f)
                                        else Color.White.copy(alpha = if (isFocused) 0.16f else 0.08f)
                                    )
                                    .border(
                                        width = if (isFocused) 1.5.dp else 1.dp,
                                        color = if (isFocused) accent else Color.White.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                            )
                        }
                    }
                }

                // Bottom Dock Preview (dock_row + dock_glass + dock_opacity + dock_band_all)
                if (config.dockRow) {
                    val dockAlpha = (config.dockOpacity.coerceIn(30, 100) / 100f) *
                        (if (config.dockGlass) 0.30f else 0.72f)
                    val dockFill = if (config.dockGlass) Color.White.copy(alpha = dockAlpha) else Color(0xFF0F172A).copy(alpha = dockAlpha)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = dockFill,
                        border = BorderStroke(1.dp, accent.copy(alpha = if (config.dockGlass) 0.55f else 0.25f)),
                        modifier = if (config.dockBandAll) Modifier.fillMaxWidth() else Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val dockCount = config.dockApps.size.takeIf { it > 0 }?.coerceAtMost(7) ?: 5
                            repeat(dockCount) { idx ->
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(if (idx == 0) accent else Color.White.copy(alpha = 0.50f))
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(6.dp))
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
