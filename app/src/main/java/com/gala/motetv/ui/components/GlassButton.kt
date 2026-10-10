package com.gala.motetv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gala.motetv.ui.theme.ElectricBlueDark
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassBorderStrong
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.Typography

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPrimary: Boolean = false,
    customAccent: Color? = null,
    enabled: Boolean = true,
    testTag: String = "glass_button"
) {
    val shape = RoundedCornerShape(16.dp)

    val backgroundBrush = when {
        customAccent != null -> {
            Brush.horizontalGradient(
                colors = listOf(customAccent, customAccent.copy(alpha = 0.85f))
            )
        }
        isPrimary -> {
            if (enabled) {
                Brush.horizontalGradient(
                    colors = listOf(ElectricBluePrimary, ElectricBlueDark)
                )
            } else {
                Brush.horizontalGradient(
                    colors = listOf(ElectricBluePrimary.copy(alpha = 0.4f), ElectricBlueDark.copy(alpha = 0.4f))
                )
            }
        }
        else -> {
            Brush.verticalGradient(
                colors = listOf(GlassSurfaceElevated, GlassSurface)
            )
        }
    }

    val borderStroke = when {
        isPrimary || customAccent != null -> BorderStroke(1.dp, GlassBorderStrong.copy(alpha = 0.3f))
        else -> BorderStroke(1.dp, GlassBorder)
    }

    val contentColor = when {
        !enabled -> TextPrimary.copy(alpha = 0.4f)
        isPrimary || customAccent != null -> Color.White
        else -> TextPrimary
    }

    val elevation = if (isPrimary || customAccent != null) 3.dp else 1.dp

    Box(
        modifier = modifier
            .testTag(testTag)
            .shadow(elevation = if (enabled) elevation else 0.dp, shape = shape, spotColor = Color(0x1F0F172A))
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(backgroundBrush)
            .border(borderStroke, shape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = if (isPrimary || customAccent != null) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.1f)),
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = Typography.titleMedium,
                color = contentColor
            )
        }
    }
}
