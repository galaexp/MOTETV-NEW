package com.gala.motetv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.theme.*
import kotlinx.coroutines.launch

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
    val coroutineScope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var launchFeedback by remember { mutableStateOf<String?>(null) }
    val isConnected = connectionState is ConnectionState.Ready

    val filteredApps = remember(allApps, favoriteAppIds, selectedCategory) {
        when (selectedCategory) {
            "FAVORITES" -> allApps.filter { favoriteAppIds.contains(it.id) }
            "STREAMING" -> allApps.filter { it.category == AppCategory.STREAMING }
            "VIDEO" -> allApps.filter { it.category == AppCategory.VIDEO }
            "MUSIC" -> allApps.filter { it.category == AppCategory.MUSIC }
            "CUSTOM" -> allApps.filter { it.isCustom }
            else -> allApps
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // TOP BAR: Header & Add App Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Android TV Apps",
                        style = Typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = if (isConnected) "Connected to ${device?.name}" else "Connect to TV to launch apps",
                        style = Typography.bodySmall,
                        color = if (isConnected) Color(0xFF10B981) else Color(0xFF94A3B8)
                    )
                }

                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add App", style = Typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
            }

            // FILTER CATEGORIES ROW
            val categories = listOf(
                "ALL" to "All Apps",
                "FAVORITES" to "Favorites ⭐",
                "STREAMING" to "Streaming",
                "VIDEO" to "Video",
                "MUSIC" to "Music",
                "CUSTOM" to "Custom"
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { (key, label) ->
                    val isSel = selectedCategory == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSel) Color(0xFF0284C7) else Color(0xFF1E293B))
                            .border(
                                1.dp,
                                if (isSel) Color(0xFF38BDF8) else Color(0xFF334155),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { selectedCategory = key }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            style = Typography.labelSmall.copy(fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium),
                            color = if (isSel) Color.White else Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // LAUNCH FEEDBACK BANNER
            AnimatedVisibility(
                visible = launchFeedback != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x330284C7),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8))
                ) {
                    Text(
                        text = launchFeedback ?: "",
                        style = Typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // APP LIST
            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No apps in this category",
                        style = Typography.bodyMedium,
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredApps, key = { it.id }) { app ->
                        val isFav = favoriteAppIds.contains(app.id)
                        AppItemCard(
                            app = app,
                            isFavorite = isFav,
                            isConnected = isConnected,
                            onLaunch = {
                                coroutineScope.launch {
                                    val result = AppLauncher.launch(app, remoteManager)
                                    launchFeedback = "Launched ${app.name} on TV"
                                    kotlinx.coroutines.delay(3000)
                                    launchFeedback = null
                                }
                            },
                            onToggleFavorite = { onToggleFavorite(app.id) },
                            onDelete = if (app.isCustom) { { onRemoveCustomApp(app.id) } } else null
                        )
                    }
                }
            }
        }

        // ADD CUSTOM APP DIALOG
        if (showAddDialog) {
            AddCustomAppDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { newApp ->
                    onAddCustomApp(newApp)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
private fun AppItemCard(
    app: AppDefinition,
    isFavorite: Boolean,
    isConnected: Boolean,
    onLaunch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: (() -> Unit)?
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // App Logo Placeholder Box
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = app.name,
                        style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = app.category.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = Typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Actions: Star Favorite, Launch, Delete (if custom)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                    }
                }

                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFFF59E0B) else Color(0xFF64748B),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Button(
                    onClick = onLaunch,
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7),
                        disabledContainerColor = Color(0xFF334155)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open", style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun AddCustomAppDialog(
    onDismiss: () -> Unit,
    onAdd: (AppDefinition) -> Unit
) {
    var appName by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }
    var deepLinkUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(24.dp),
        title = {
            Text("Add Custom TV App", style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("App Name", color = Color(0xFF94A3B8)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF334155)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = deepLinkUrl,
                    onValueChange = { deepLinkUrl = it },
                    label = { Text("Deep Link / Web URL (e.g. https://...)", color = Color(0xFF94A3B8)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF334155)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (appName.isNotEmpty()) {
                        val newApp = AppDefinition(
                            id = "custom_${System.currentTimeMillis()}",
                            name = appName,
                            packageId = if (deepLinkUrl.startsWith("package:")) deepLinkUrl.removePrefix("package:") else "",
                            primaryDeepLink = deepLinkUrl,
                            category = AppCategory.CUSTOM,
                            isCustom = true
                        )
                        onAdd(newApp)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Add App")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}
