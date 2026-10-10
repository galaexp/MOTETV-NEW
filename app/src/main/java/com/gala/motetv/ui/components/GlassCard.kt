package com.gala.motetv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color = GlassSurface,
    borderColor: Color = GlassBorder,
    borderWidth: Dp = 1.dp,
    elevation: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            onClick = onClick,
            indication = ripple(color = Color.Black.copy(alpha = 0.08f)),
            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()
        )
    } else Modifier

    Box(
        modifier = modifier
            .shadow(elevation = elevation, shape = shape, spotColor = Color(0x140F172A), ambientColor = Color(0x0A0F172A))
            .clip(shape)
            .then(clickModifier)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GlassSurfaceElevated,
                        backgroundColor
                    )
                )
            )
            .border(
                border = BorderStroke(borderWidth, borderColor),
                shape = shape
            )
    ) {
        content()
    }
}
