package com.gala.motetv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.AdbConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.tvhome.*
import com.gala.motetv.ui.components.AdbConnectionCard
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TvHomeThemesScreen(
    adbManager: AdbConnectionManager,
    launcherManager: CleanYourTvLauncherManager,
    themeManager: CleanYourTvThemeManager,
    currentRemoteDevice: TvDevice?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val adbState by adbManager.connectionState.collectAsStateWithLifecycle()
    val launcherStatus by launcherManager.launcherStatus.collectAsStateWithLifecycle()
    val themeConfig by themeManager.currentConfig.collectAsStateWithLifecycle()
    val prevConfig by themeManager.previousAppliedConfig.collectAsStateWithLifecycle()
    val themeStatus by themeManager.themeStatusMessage.collectAsStateWithLifecycle()

    var selectedCategory by remember { mutableStateOf<ThemePresetCategory?>(null) }
    var dockAppsInput by remember(themeConfig.dockApps) { mutableStateOf(themeConfig.dockApps.joinToString(", ")) }
    var hiddenAppsInput by remember(themeConfig.hiddenApps) { mutableStateOf(themeConfig.hiddenApps.joinToString(", ")) }
    var pendingWallpaperBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingWallpaperName by remember { mutableStateOf<String?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }

    val isAdbConnected = adbState is AdbConnectionState.Connected

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busyMessage = "Pushing & installing CleanYourTV Home APK..."
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    busyMessage = "Could not read selected APK file."
                } else {
                    val res = launcherManager.installCleanYourTvApk(bytes)
                    busyMessage = res.fold(
                        onSuccess = { "CleanYourTV Home installed. Stock launcher kept intact." },
                        onFailure = { "APK install failed: ${it.message}" }
                    )
                }
            }
        }
    }

    val wallpaperPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                pendingWallpaperBytes = bytes
                pendingWallpaperName = "motetv_wallpaper_${System.currentTimeMillis()}.jpg"
                busyMessage = "Wallpaper selected (${bytes.size / 1024} KB). Tap 'Apply to TV' to deploy."
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(GlassSurfacePressed)
                        .border(1.dp, GlassBorder, CircleShape)
                        .testTag("tvhome_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TV Home & Themes",
                        style = Typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "CleanYourTV Launcher & TV Appearance",
                        style = Typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Scope Clarification Banner
                item(key = "scope_banner") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = ElectricBluePrimary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, ElectricBlueLight.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Tv, contentDescription = null, tint = ElectricBlueLight)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Changes your TV's actual HOME screen (CleanYourTV Home • com.gathin.tvhome), NOT the MoteTV phone UI. Stock TV HOME stays preserved for one-tap recovery.",
                                style = Typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // ADB Card
                item(key = "tvhome_adb_card") {
                    AdbConnectionCard(
                        adbManager = adbManager,
                        defaultTvIp = currentRemoteDevice?.host,
                        onConnectedCallback = {
                            scope.launch { launcherManager.refreshLauncherStatus() }
                        }
                    )
                }

                // A. Launcher Installation & Recovery
                item(key = "launcher_card") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CLEAN YOUR TV LAUNCHER & RECOVERY",
                                    style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                                    color = TextMuted
                                )
                                TextButton(
                                    onClick = { scope.launch { launcherManager.refreshLauncherStatus() } },
                                    enabled = isAdbConnected,
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("Refresh", color = ElectricBlueLight)
                                }
                            }

                            Text(launcherStatus.statusDetail, style = Typography.bodySmall, color = TextSecondary)
                            Text(
                                text = "Original HOME saved: ${launcherStatus.originalHomePackage ?: "Will be captured before switch"}",
                                style = Typography.labelSmall,
                                color = StatusSuccessGreen
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        apkPickerLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
                                    },
                                    enabled = isAdbConnected && launcherStatus.isSdkCompatible,
                                    border = BorderStroke(1.dp, GlassBorderStrong),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("tvhome_install_apk_button")
                                ) {
                                    Text("Install APK", color = ElectricBlueLight)
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            val res = launcherManager.activateCleanYourTvAsHome()
                                            busyMessage = res.fold(
                                                onSuccess = { "CleanYourTV Home is now verified active on TV!" },
                                                onFailure = { it.message }
                                            )
                                        }
                                    },
                                    enabled = isAdbConnected && launcherStatus.isCleanYourTvInstalled,
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("tvhome_set_default_button")
                                ) {
                                    Text(if (launcherStatus.isCleanYourTvActiveHome) "Verified Active" else "Set as TV Home")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val res = launcherManager.restoreOriginalHomeLauncher()
                                        busyMessage = res.fold(
                                            onSuccess = { "Restored original TV HOME (${it.currentHomePackage})." },
                                            onFailure = { "Restore HOME failed: ${it.message}" }
                                        )
                                    }
                                },
                                enabled = isAdbConnected && !launcherStatus.originalHomePackage.isNullOrBlank(),
                                border = BorderStroke(1.dp, StatusWarningAmber),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("tvhome_restore_original_button")
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = null, tint = StatusWarningAmber, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restore Original TV Home", color = StatusWarningAmber)
                            }
                        }
                    }
                }

                // B. Live TV Home Preview
                item(key = "preview_card") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "LIVE TV SCREEN PREVIEW",
                                style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                                color = TextMuted
                            )
                            TvHomePreviewCard(config = themeConfig)
                        }
                    }
                }

                // C. Official CleanYourTV Presets (29 themes)
                item(key = "presets_browser") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "CLEANYOURTV THEMES (${CleanYourTvThemeCatalog.PRESETS.size})",
                            style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                            color = TextMuted
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("All") },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            )
                            ThemePresetCategory.entries.forEach { cat ->
                                FilterChip(
                                    selected = selectedCategory == cat,
                                    onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                                    label = { Text(cat.label) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                )
                            }
                        }

                        val presets = CleanYourTvThemeCatalog.PRESETS.filter {
                            selectedCategory == null || it.category == selectedCategory
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(presets, key = { it.id }) { preset ->
                                ThemePresetChipCard(
                                    preset = preset,
                                    isSelected = themeConfig.themeId == preset.id,
                                    onClick = { themeManager.selectPreset(preset) }
                                )
                            }
                        }
                    }
                }

                // D. Customization Controls
                item(key = "customizer_card") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "LAYOUT & VISUAL CUSTOMIZATION",
                                style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                                color = TextMuted
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = themeConfig.accentColorHex,
                                    onValueChange = { hex -> themeManager.updateConfig { it.copy(accentColorHex = hex) } },
                                    label = { Text("Accent #Hex") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = themeConfig.backgroundColorHex,
                                    onValueChange = { hex -> themeManager.updateConfig { it.copy(backgroundColorHex = hex) } },
                                    label = { Text("Background #Hex") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Text("App Grid Columns: ${themeConfig.columns}", style = Typography.bodySmall, color = TextPrimary)
                            Slider(
                                value = themeConfig.columns.toFloat(),
                                onValueChange = { v -> themeManager.updateConfig { it.copy(columns = v.toInt().coerceIn(3, 7)) } },
                                valueRange = 3f..7f,
                                steps = 3
                            )

                            Text("Dock Slots: ${themeConfig.dockSlots}", style = Typography.bodySmall, color = TextPrimary)
                            Slider(
                                value = themeConfig.dockSlots.toFloat(),
                                onValueChange = { v -> themeManager.updateConfig { it.copy(dockSlots = v.toInt().coerceIn(3, 8)) } },
                                valueRange = 3f..8f,
                                steps = 4
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Dock Blur", style = Typography.bodyMedium, color = TextPrimary)
                                Switch(
                                    checked = themeConfig.dockBlur,
                                    onCheckedChange = { v -> themeManager.updateConfig { it.copy(dockBlur = v) } }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("24-Hour Clock (${themeConfig.clockFormat})", style = Typography.bodyMedium, color = TextPrimary)
                                Switch(
                                    checked = themeConfig.clockFormat == "24h",
                                    onCheckedChange = { is24 ->
                                        themeManager.updateConfig { it.copy(clockFormat = if (is24) "24h" else "12h") }
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("minimal", "bold", "hidden").forEach { style ->
                                    FilterChip(
                                        selected = themeConfig.clockStyle == style,
                                        onClick = { themeManager.updateConfig { it.copy(clockStyle = style) } },
                                        label = { Text("Clock: $style") },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Show TV System Stats", style = Typography.bodyMedium, color = TextPrimary)
                                Switch(
                                    checked = themeConfig.showStats,
                                    onCheckedChange = { v -> themeManager.updateConfig { it.copy(showStats = v) } }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Reduced Motion", style = Typography.bodyMedium, color = TextPrimary)
                                Switch(
                                    checked = themeConfig.reducedMotion,
                                    onCheckedChange = { v -> themeManager.updateConfig { it.copy(reducedMotion = v) } }
                                )
                            }

                            OutlinedTextField(
                                value = dockAppsInput,
                                onValueChange = { raw ->
                                    dockAppsInput = raw
                                    themeManager.updateConfig {
                                        it.copy(dockApps = raw.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() })
                                    }
                                },
                                label = { Text("Pinned Dock Apps (comma package IDs)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = hiddenAppsInput,
                                onValueChange = { raw ->
                                    hiddenAppsInput = raw
                                    themeManager.updateConfig {
                                        it.copy(hiddenApps = raw.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() })
                                    }
                                },
                                label = { Text("Hidden Apps (comma package IDs)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedButton(
                                onClick = {
                                    wallpaperPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                border = BorderStroke(1.dp, GlassBorderStrong),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("tvhome_pick_wallpaper_button")
                            ) {
                                Icon(Icons.Default.Wallpaper, contentDescription = null, tint = ElectricBlueLight)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = pendingWallpaperName?.let { "Wallpaper Ready: $it" } ?: "Pick Custom TV Wallpaper",
                                    color = ElectricBlueLight
                                )
                            }
                        }
                    }
                }

                // E. Status & Apply / Rollback / Reset
                item(key = "apply_actions") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            (busyMessage ?: themeStatus)?.let { msg ->
                                Text(text = msg, style = Typography.bodySmall, color = ElectricBlueLight)
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        val res = themeManager.applyThemeToTv(pendingWallpaperBytes, pendingWallpaperName)
                                        if (res.isSuccess) {
                                            pendingWallpaperBytes = null
                                            pendingWallpaperName = null
                                        }
                                    }
                                },
                                enabled = isAdbConnected,
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("tvhome_apply_theme_button")
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Apply Theme to TV", style = Typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { scope.launch { themeManager.rollbackPreviousTheme() } },
                                    enabled = isAdbConnected && prevConfig != null,
                                    border = BorderStroke(1.dp, GlassBorderStrong),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("tvhome_rollback_button")
                                ) {
                                    Text("Rollback", color = TextPrimary)
                                }

                                OutlinedButton(
                                    onClick = { scope.launch { themeManager.resetThemeOnTv() } },
                                    enabled = isAdbConnected,
                                    border = BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.7f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("tvhome_reset_button")
                                ) {
                                    Text("Reset TV Theme", color = StatusErrorRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
