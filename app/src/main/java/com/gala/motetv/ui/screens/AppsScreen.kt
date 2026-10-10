package com.gala.motetv.ui.screens

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.launcher.AppCategory
import com.gala.motetv.launcher.AppDefinition
import com.gala.motetv.launcher.AppLauncher
import com.gala.motetv.launcher.LaunchResult
import com.gala.motetv.remote.TvRemoteManager
import com.gala.motetv.ui.components.GlassButton
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.components.TvStatusCard
import com.gala.motetv.ui.theme.BackgroundLight
import com.gala.motetv.ui.theme.ElectricBluePrimary
import com.gala.motetv.ui.theme.GlassBorder
import com.gala.motetv.ui.theme.GlassSurface
import com.gala.motetv.ui.theme.GlassSurfaceElevated
import com.gala.motetv.ui.theme.StatusErrorRed
import com.gala.motetv.ui.theme.StatusSuccessGreen
import com.gala.motetv.ui.theme.StatusWarningAmber
import com.gala.motetv.ui.theme.SurfaceCardLight
import com.gala.motetv.ui.theme.TextMuted
import com.gala.motetv.ui.theme.TextPrimary
import com.gala.motetv.ui.theme.TextSecondary
import com.gala.motetv.ui.theme.Typography

@Composable
fun AppsScreen(
    device: TvDevice?,
    connectionState: ConnectionState,
    remoteManager: TvRemoteManager,
    allApps: List<AppDefinition>,
    favoriteAppIds: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onAddCustomApp: (AppDefinition) -> Unit,
    onRemoveCustomApp: (String) -> Unit,
    onOpenDevices: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedAppForStrategy by remember { mutableStateOf<AppDefinition?>(null) }
    var lastLaunchFeedback by remember { mutableStateOf<LaunchResult?>(null) }

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
                    onPowerClick = {}
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Launch Feedback Banner
            item {
                AnimatedVisibility(
                    visible = lastLaunchFeedback != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    lastLaunchFeedback?.let { feedback ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = when (feedback) {
                                is LaunchResult.Success -> StatusSuccessGreen.copy(alpha = 0.15f)
                                is LaunchResult.Failure -> StatusErrorRed.copy(alpha = 0.15f)
                            },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = when (feedback) {
                                            is LaunchResult.Success -> "Launched ${feedback.appName}"
                                            is LaunchResult.Failure -> "Failed to launch ${feedback.appName}"
                                        },
                                        style = Typography.titleSmall,
                                        color = when (feedback) {
                                            is LaunchResult.Success -> StatusSuccessGreen
                                            is LaunchResult.Failure -> StatusErrorRed
                                        }
                                    )
                                    Text(
                                        text = when (feedback) {
                                            is LaunchResult.Success -> "Using: ${feedback.uriUsed}"
                                            is LaunchResult.Failure -> feedback.reason
                                        },
                                        style = Typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                                IconButton(onClick = { lastLaunchFeedback = null }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Header with Add Button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TV Applications",
                            style = Typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "Tap any app to launch on ${device?.name ?: "TV"}",
                            style = Typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    GlassButton(
                        text = "Add App",
                        icon = Icons.Default.Add,
                        onClick = { showAddDialog = true },
                        testTag = "btn_add_app"
                    )
                }
            }

            // Favorites Section
            val favoriteApps = allApps.filter { favoriteAppIds.contains(it.id) }
            if (favoriteApps.isNotEmpty()) {
                item {
                    Text(
                        text = "Favorite Apps",
                        style = Typography.titleMedium,
                        color = TextPrimary
                    )
                }

                items(favoriteApps, key = { "fav_${it.id}" }) { app ->
                    AppRowCard(
                        app = app,
                        isFavorite = true,
                        onLaunch = {
                            val result = AppLauncher.launch(app, remoteManager)
                            lastLaunchFeedback = result
                        },
                        onToggleFavorite = { onToggleFavorite(app.id) },
                        onShowStrategies = { selectedAppForStrategy = app },
                        onDelete = if (app.isCustom) { { onRemoveCustomApp(app.id) } } else null
                    )
                }
            }

            // All Configured Apps Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "All Applications",
                    style = Typography.titleMedium,
                    color = TextPrimary
                )
            }

            items(allApps, key = { "all_${it.id}" }) { app ->
                AppRowCard(
                    app = app,
                    isFavorite = favoriteAppIds.contains(app.id),
                    onLaunch = {
                        val result = AppLauncher.launch(app, remoteManager)
                        lastLaunchFeedback = result
                    },
                    onToggleFavorite = { onToggleFavorite(app.id) },
                    onShowStrategies = { selectedAppForStrategy = app },
                    onDelete = if (app.isCustom) { { onRemoveCustomApp(app.id) } } else null
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Add Custom App Dialog
    if (showAddDialog) {
        AddCustomAppDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { newApp ->
                onAddCustomApp(newApp)
                showAddDialog = false
            }
        )
    }

    // Launch Strategy Dialog (for testing YouTube deep-links vs package URI)
    selectedAppForStrategy?.let { app ->
        AppStrategyDialog(
            app = app,
            onDismiss = { selectedAppForStrategy = null },
            onSelectStrategy = { index ->
                val result = AppLauncher.launch(app, remoteManager, strategyIndex = index)
                lastLaunchFeedback = result
                selectedAppForStrategy = null
            }
        )
    }
}

@Composable
fun AppRowCard(
    app: AppDefinition,
    isFavorite: Boolean,
    onLaunch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowStrategies: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val brandColor = Color(app.accentColorHex)

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_card_${app.id}"),
        shape = RoundedCornerShape(18.dp),
        elevation = 2.dp,
        onClick = onLaunch
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(brandColor.copy(alpha = 0.15f))
                        .border(1.dp, brandColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(brandColor)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = app.name,
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )
                    Text(
                        text = if (app.packageId.isNotBlank()) app.packageId else app.primaryDeepLink,
                        style = Typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Favorite Star
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) StatusWarningAmber else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Strategy Picker / Launch Details
                IconButton(
                    onClick = onShowStrategies,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Launch,
                        contentDescription = "Launch Options",
                        tint = ElectricBluePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Delete if custom
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = StatusErrorRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddCustomAppDialog(
    onDismiss: () -> Unit,
    onAdd: (AppDefinition) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var packageId by remember { mutableStateOf("") }
    var deepLink by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCardLight,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Add Custom App",
                style = Typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Configure an app shortcut to launch on your TV via Android TV Remote v2 protocol.",
                    style = Typography.bodySmall,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("App Name (e.g. VLC)") },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricBluePrimary,
                        unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = packageId,
                    onValueChange = { packageId = it },
                    label = { Text("Package ID (e.g. org.videolan.vlc)") },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricBluePrimary,
                        unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = deepLink,
                    onValueChange = { deepLink = it },
                    label = { Text("Deep Link (Optional, e.g. vlc://)") },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = ElectricBluePrimary,
                        unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            GlassButton(
                text = "Save App",
                isPrimary = true,
                enabled = name.isNotBlank() && (packageId.isNotBlank() || deepLink.isNotBlank()),
                onClick = {
                    val id = name.lowercase().replace(" ", "_").filter { it.isLetterOrDigit() || it == '_' }
                    onAdd(
                        AppDefinition(
                            id = id,
                            name = name.trim(),
                            packageId = packageId.trim(),
                            primaryDeepLink = if (deepLink.isNotBlank()) deepLink.trim() else "market://launch?id=${packageId.trim()}",
                            accentColorHex = 0xFF5B6CFF,
                            isFavorite = true,
                            category = AppCategory.CUSTOM,
                            isCustom = true
                        )
                    )
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}

@Composable
fun AppStrategyDialog(
    app: AppDefinition,
    onDismiss: () -> Unit,
    onSelectStrategy: (Int) -> Unit
) {
    val strategies = AppLauncher.getStrategyDescriptions(app)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCardLight,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Launch ${app.name}",
                style = Typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Select launch strategy to send to TV over Android TV Remote v2 protocol:",
                    style = Typography.bodySmall,
                    color = TextSecondary
                )

                strategies.forEachIndexed { index, desc ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GlassSurface)
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                            .clickable { onSelectStrategy(index) }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = ElectricBluePrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = desc,
                                style = Typography.bodySmall.copy(fontSize = 12.sp),
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextMuted)
            }
        }
    )
}
