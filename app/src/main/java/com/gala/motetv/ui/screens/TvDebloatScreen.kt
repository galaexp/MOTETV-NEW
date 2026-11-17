package com.gala.motetv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gala.motetv.adb.AdbConnectionManager
import com.gala.motetv.adb.AdbConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.debloat.*
import com.gala.motetv.ui.components.AdbConnectionCard
import com.gala.motetv.ui.components.GlassCard
import com.gala.motetv.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TvDebloatScreen(
    adbManager: AdbConnectionManager,
    debloatManager: DebloatManager,
    currentRemoteDevice: TvDevice?,
    isCleanYourTvActiveHome: Boolean,
    isCleanYourTvInstalled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val adbState by adbManager.connectionState.collectAsStateWithLifecycle()
    val scanResult by debloatManager.scanResult.collectAsStateWithLifecycle()
    val selectedPackages by debloatManager.selectedPackages.collectAsStateWithLifecycle()
    val latestBackup by debloatManager.latestBackup.collectAsStateWithLifecycle()
    val history by debloatManager.operationHistory.collectAsStateWithLifecycle()
    val progress by debloatManager.batchProgress.collectAsStateWithLifecycle()
    val statusMsg by debloatManager.statusMessage.collectAsStateWithLifecycle()

    var selectedCategory by remember { mutableStateOf<PackageCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var inspectTarget by remember { mutableStateOf<ScannedPackage?>(null) }
    var confirmActionIsRestore by remember { mutableStateOf<Boolean?>(null) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = debloatManager.exportBackupJson()
        if (uri != null && json != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                debloatManager.markBackupExported(uri.lastPathSegment ?: "SAF document")
            }
        }
    }

    val isAdbConnected = adbState is AdbConnectionState.Connected

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
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(GlassSurfacePressed)
                            .border(1.dp, GlassBorder, CircleShape)
                            .testTag("debloat_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "TV Debloat & Privacy",
                            style = Typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Reversible CleanYourTV package guard (ADB)",
                            style = Typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(
                    onClick = { showHistoryDialog = true },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("debloat_history_button")
                ) {
                    Icon(Icons.Default.History, contentDescription = "Operation History", tint = ElectricBlueLight)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Separate ADB Connection Section
                item(key = "adb_card") {
                    AdbConnectionCard(
                        adbManager = adbManager,
                        defaultTvIp = currentRemoteDevice?.host
                    )
                }

                // Device Discovery Summary & Model Safety Card
                if (scanResult != null) {
                    val scan = scanResult!!
                    item(key = "device_info_card") {
                        GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "DISCOVERED TV PROFILE",
                                    style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                                    color = TextMuted
                                )
                                Text(
                                    text = "${scan.deviceInfo.manufacturer} ${scan.deviceInfo.model} (${scan.deviceInfo.deviceName})",
                                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Android ${scan.deviceInfo.androidVersion} (SDK ${scan.deviceInfo.sdkVersion}) • Build ${scan.deviceInfo.buildId}",
                                    style = Typography.bodySmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "Active HOME: ${scan.deviceInfo.currentHomePackage ?: "Unknown"}",
                                    style = Typography.bodySmall,
                                    color = ElectricBlueLight
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (scan.hasExactModelCatalogMatch) StatusSuccessGreen.copy(alpha = 0.12f) else StatusWarningAmber.copy(alpha = 0.12f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (scan.hasExactModelCatalogMatch) StatusSuccessGreen.copy(alpha = 0.4f) else StatusWarningAmber.copy(alpha = 0.4f)
                                    )
                                ) {
                                    Text(
                                        text = scan.modelSafetyNotice,
                                        style = Typography.bodySmall,
                                        color = if (scan.hasExactModelCatalogMatch) StatusSuccessGreen else StatusWarningAmber,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Controls Card: Scan, Backup, Export, Select Safe, Restore All
                item(key = "actions_card") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { scope.launch { debloatManager.scanTv() } },
                                    enabled = isAdbConnected && !progress.isRunning,
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBluePrimary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("debloat_scan_tv_button")
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Scan TV")
                                }

                                OutlinedButton(
                                    onClick = { scope.launch { debloatManager.createBackup() } },
                                    enabled = isAdbConnected && !progress.isRunning,
                                    border = BorderStroke(1.dp, GlassBorderStrong),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("debloat_backup_button")
                                ) {
                                    Icon(Icons.Default.Backup, contentDescription = null, tint = ElectricBlueLight, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Backup", color = ElectricBlueLight)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { debloatManager.selectRecommendedSafePackages() },
                                    enabled = scanResult != null && !progress.isRunning,
                                    border = BorderStroke(1.dp, GlassBorderStrong),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("debloat_select_safe_button")
                                ) {
                                    Text("Select Safe", color = TextPrimary, style = Typography.labelLarge)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val devName = (scanResult?.deviceInfo?.deviceName ?: "tv").replace(" ", "_")
                                        exportBackupLauncher.launch("motetv_tv_backup_$devName.json")
                                    },
                                    enabled = latestBackup != null,
                                    border = BorderStroke(1.dp, GlassBorderStrong),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("debloat_export_backup_button")
                                ) {
                                    Text("Export JSON", color = TextPrimary, style = Typography.labelLarge)
                                }

                                OutlinedButton(
                                    onClick = { scope.launch { debloatManager.restoreAllModifiedByMoteTv() } },
                                    enabled = isAdbConnected && !progress.isRunning,
                                    border = BorderStroke(1.dp, StatusSuccessGreen.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("debloat_restore_all_button")
                                ) {
                                    Text("Restore All", color = StatusSuccessGreen, style = Typography.labelLarge)
                                }
                            }

                            if (progress.isRunning) {
                                LinearProgressIndicator(
                                    progress = {
                                        if (progress.totalCount > 0) progress.processedCount.toFloat() / progress.totalCount else 0f
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = ElectricBlueLight
                                )
                                Text(
                                    text = "Processing ${progress.processedCount}/${progress.totalCount}: ${progress.currentPackage ?: ""}",
                                    style = Typography.labelSmall,
                                    color = ElectricBlueLight
                                )
                            }

                            if (!statusMsg.isNullOrBlank()) {
                                Text(
                                    text = statusMsg!!,
                                    style = Typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                // Filter Chips & Search Bar (when scanned)
                if (scanResult != null) {
                    item(key = "filter_bar") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search package or app name...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = ElectricBlueLight,
                                    unfocusedBorderColor = GlassBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("debloat_search_input")
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
                                    label = { Text("All (${scanResult!!.packages.size})") },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                )
                                PackageCategory.entries.forEach { cat ->
                                    val count = scanResult!!.packages.count { it.category == cat }
                                    if (count > 0) {
                                        FilterChip(
                                            selected = selectedCategory == cat,
                                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                                            label = { Text("${cat.title.substringBefore(" &")} ($count)") },
                                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val filtered = scanResult!!.packages.filter { pkg ->
                        val catMatch = selectedCategory == null || pkg.category == selectedCategory
                        val q = searchQuery.trim()
                        val textMatch = q.isBlank() ||
                            pkg.packageName.contains(q, ignoreCase = true) ||
                            pkg.appName.contains(q, ignoreCase = true)
                        catMatch && textMatch
                    }

                    items(filtered, key = { it.packageName }) { pkg ->
                        val isSelected = selectedPackages.contains(pkg.packageName)
                        PackageRowCard(
                            pkg = pkg,
                            isSelected = isSelected,
                            onToggle = { debloatManager.togglePackageSelection(pkg.packageName) },
                            onInspect = { inspectTarget = pkg }
                        )
                    }
                }
            }

            // Bottom Selection Action Bar
            if (selectedPackages.isNotEmpty() && scanResult != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceCardDark,
                    border = BorderStroke(1.dp, GlassBorderStrong),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedPackages.size} selected",
                            style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { confirmActionIsRestore = true },
                            border = BorderStroke(1.dp, StatusSuccessGreen),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("debloat_restore_selected_button")
                        ) {
                            Text("Restore", color = StatusSuccessGreen)
                        }
                        Button(
                            onClick = { confirmActionIsRestore = false },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusErrorRed),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("debloat_disable_selected_button")
                        ) {
                            Text("Disable (${selectedPackages.size})")
                        }
                    }
                }
            }
        }

        inspectTarget?.let { target ->
            PackageInspectDialog(pkg = target, onDismiss = { inspectTarget = null })
        }

        val restoreMode = confirmActionIsRestore
        if (restoreMode != null && scanResult != null) {
            val targets = scanResult!!.packages.filter { selectedPackages.contains(it.packageName) }
            ConfirmDebloatDialog(
                selectedPackages = targets,
                isRestoreMode = restoreMode,
                onConfirm = {
                    confirmActionIsRestore = null
                    scope.launch {
                        if (restoreMode) {
                            debloatManager.restorePackages(targets.map { it.packageName })
                        } else {
                            debloatManager.disablePackagesWithBackup(
                                packages = targets.map { it.packageName },
                                verifiedReplacementHomeInstalled = isCleanYourTvInstalled,
                                verifiedReplacementHomeActive = isCleanYourTvActiveHome
                            )
                        }
                    }
                },
                onDismiss = { confirmActionIsRestore = null }
            )
        }

        if (showHistoryDialog) {
            DebloatHistoryDialog(history = history, onDismiss = { showHistoryDialog = false })
        }
    }
}

@Composable
private fun PackageRowCard(
    pkg: ScannedPackage,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onInspect: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        borderColor = if (isSelected) ElectricBlueLight else GlassBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !pkg.isBlockedFromDisable, onClick = onToggle)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { if (!pkg.isBlockedFromDisable) onToggle() },
                enabled = !pkg.isBlockedFromDisable
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = pkg.appName,
                        style = Typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (pkg.isBlockedFromDisable) TextMuted else TextPrimary
                    )
                    Text(
                        text = if (pkg.isEnabled) "ENABLED" else "DISABLED",
                        style = Typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (pkg.isEnabled) StatusSuccessGreen else StatusWarningAmber
                    )
                }
                Text(pkg.packageName, style = Typography.labelSmall, color = ElectricBlueLight)
                Text("${pkg.category.title} • ${pkg.riskLevel.label}", style = Typography.labelSmall, color = TextMuted)
                Text(pkg.reason, style = Typography.bodySmall, color = TextSecondary, maxLines = 2)
            }
            IconButton(
                onClick = onInspect,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = "Inspect ${pkg.appName}", tint = TextSecondary)
            }
        }
    }
}
