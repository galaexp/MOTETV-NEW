package com.gala.motetv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

enum class NavTab(val title: String, val icon: ImageVector, val tag: String) {
    REMOTE("Remote", Icons.Default.Tv, "tab_nav_remote"),
    APPS("Apps", Icons.Default.Apps, "tab_nav_apps"),
    MEDIA("Media", Icons.Default.PlayCircle, "tab_nav_media"),
    SETTINGS("Settings", Icons.Default.Settings, "tab_nav_settings")
}

@Composable
fun BottomNavBar(
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("bottom_nav_bar"),
        shape = RoundedCornerShape(26.dp),
        elevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                val iconColor by animateColorAsState(
                    targetValue = if (isSelected) ElectricBluePrimary else TextSecondary,
                    label = "tabColor"
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = ElectricBluePrimary.copy(alpha = 0.15f)),
                            onClick = { onTabSelected(tab) }
                        )
                        .background(
                            if (isSelected) ElectricBluePrimary.copy(alpha = 0.12f) else Color.Transparent
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(tab.tag),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = iconColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tab.title,
                            style = Typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (isSelected) ElectricBluePrimary else TextMuted
                        )
                    }
                }
            }
        }
    }
}
