package com.gala.motetv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.launcher.AppDefinition
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@Composable
fun QuickAppBar(
    favoriteApps: List<AppDefinition>,
    onAppClick: (AppDefinition) -> Unit,
    onOpenAllApps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        favoriteApps.forEach { app ->
            val brandColor = Color(app.accentColorHex)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassSurfaceElevated)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = brandColor.copy(alpha = 0.2f)),
                        onClick = { onAppClick(app) }
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .testTag("quick_app_${app.id}"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(brandColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = app.name,
                        style = Typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        color = TextPrimary
                    )
                }
            }
        }

        // Add / View All button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(GlassSurface)
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = ElectricBluePrimary.copy(alpha = 0.2f)),
                    onClick = onOpenAllApps
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("quick_app_all"),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = "All Apps",
                    tint = ElectricBluePrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Apps",
                    style = Typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = ElectricBluePrimary
                )
            }
        }
    }
}
