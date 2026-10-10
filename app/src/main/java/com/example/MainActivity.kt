package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.core.util.NetworkMonitor
import com.gala.motetv.discovery.NsdTvDiscoveryManager
import com.gala.motetv.launcher.AppDefinition
import com.gala.motetv.launcher.AppLauncher
import com.gala.motetv.launcher.DefaultApps
import com.gala.motetv.pairing.PairingState
import com.gala.motetv.pairing.TvPairingManagerImpl
import com.gala.motetv.remote.TvRemoteManagerImpl
import com.gala.motetv.storage.TvCredentialStore
import com.gala.motetv.ui.components.BottomNavBar
import com.gala.motetv.ui.components.NavTab
import com.gala.motetv.ui.components.PinEntryDialog
import com.gala.motetv.ui.screens.AppsScreen
import com.gala.motetv.ui.screens.DevicesScreen
import com.gala.motetv.ui.screens.MediaScreen
import com.gala.motetv.ui.screens.RemoteControlScreen
import com.gala.motetv.ui.screens.SettingsScreen
import com.gala.motetv.ui.theme.BackgroundLight
import com.gala.motetv.ui.theme.MoteTvTheme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.gala.motetv.media.LocalMediaCastController

enum class MainDisplayMode {
    TABS,
    DEVICES
}

class MainActivity : ComponentActivity() {

    private lateinit var credentialStore: TvCredentialStore
    private lateinit var discoveryManager: NsdTvDiscoveryManager
    private lateinit var pairingManager: TvPairingManagerImpl
    private lateinit var remoteManager: TvRemoteManagerImpl
    private lateinit var castController: LocalMediaCastController
    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TvLogger.i(TvLogger.TAG_GENERAL, "MoteTV Android TV Remote initialized (v2.0 Light Glass Edition)")

        credentialStore = TvCredentialStore(applicationContext)
        discoveryManager = NsdTvDiscoveryManager(applicationContext)
        pairingManager = TvPairingManagerImpl(credentialStore)
        remoteManager = TvRemoteManagerImpl(credentialStore)
        castController = LocalMediaCastController(applicationContext, remoteManager)
        networkMonitor = NetworkMonitor(applicationContext)

        networkMonitor.startMonitoring {
            TvLogger.i(TvLogger.TAG_DISCOVERY, "Network changed, triggering auto-discovery")
            discoveryManager.stopDiscovery()
            discoveryManager.startDiscovery()
        }

        // Auto-start discovery on launch
        discoveryManager.startDiscovery()

        setContent {
            MoteTvTheme {
                MainAppHost(
                    credentialStore = credentialStore,
                    discoveryManager = discoveryManager,
                    pairingManager = pairingManager,
                    remoteManager = remoteManager,
                    castController = castController
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stopDiscovery()
        remoteManager.disconnect()
        castController.stopCast()
        pairingManager.cancelPairing()
        networkMonitor.stopMonitoring()
    }
}

@Composable
fun MainAppHost(
    credentialStore: TvCredentialStore,
    discoveryManager: NsdTvDiscoveryManager,
    pairingManager: TvPairingManagerImpl,
    remoteManager: TvRemoteManagerImpl,
    castController: LocalMediaCastController
) {
    var displayMode by remember { mutableStateOf(MainDisplayMode.TABS) }
    var selectedTab by remember { mutableStateOf(NavTab.REMOTE) }
    var targetDevice by remember { mutableStateOf<TvDevice?>(null) }

    // App definitions state: defaults + custom apps
    var customApps by remember { mutableStateOf(credentialStore.getCustomApps()) }
    var favoriteAppIds by remember { mutableStateOf(credentialStore.getFavoriteAppIds()) }
    var savedDevices by remember { mutableStateOf(credentialStore.getSavedDevices()) }

    val allApps = remember(customApps) {
        DefaultApps.ALL_DEFAULT_APPS + customApps
    }

    val favoriteApps = remember(allApps, favoriteAppIds) {
        allApps.filter { favoriteAppIds.contains(it.id) }
    }

    val discoveredDevices by discoveryManager.discoveredDevices.collectAsStateWithLifecycle()
    val isDiscovering by discoveryManager.isDiscovering.collectAsStateWithLifecycle()
    val pairingState by pairingManager.pairingState.collectAsStateWithLifecycle()
    val connectionState by remoteManager.connectionState.collectAsStateWithLifecycle()
    val castSessionState by castController.sessionState.collectAsStateWithLifecycle()
    val imeActive by remoteManager.imeActive.collectAsStateWithLifecycle()
    val lastImeText by remoteManager.lastImeText.collectAsStateWithLifecycle()

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            castController.onMediaFilePicked(uri)
        }
    }

    // When pairing succeeds, automatically save and connect to remote port 6466
    LaunchedEffect(pairingState) {
        if (pairingState is PairingState.Paired) {
            val pairedDev = (pairingState as PairingState.Paired).device
            targetDevice = pairedDev
            credentialStore.saveDevice(pairedDev)
            savedDevices = credentialStore.getSavedDevices()
            remoteManager.connect(pairedDev)
        }
    }

    // Auto-select first discovered or saved TV if none selected
    LaunchedEffect(discoveredDevices, savedDevices) {
        if (targetDevice == null) {
            val saved = savedDevices.firstOrNull()
            if (saved != null) {
                targetDevice = saved
                if (credentialStore.isDevicePaired(saved.id)) {
                    remoteManager.connect(saved)
                }
            } else if (discoveredDevices.isNotEmpty()) {
                val first = discoveredDevices.first()
                targetDevice = first
                if (credentialStore.isDevicePaired(first.id)) {
                    remoteManager.connect(first)
                }
            }
        }
    }

    val onConnectToDevice: (TvDevice) -> Unit = { dev ->
        targetDevice = dev
        credentialStore.saveDevice(dev)
        savedDevices = credentialStore.getSavedDevices()
        if (credentialStore.isDevicePaired(dev.id)) {
            remoteManager.connect(dev)
        } else {
            pairingManager.startPairing(dev)
        }
    }

    when (displayMode) {
        MainDisplayMode.TABS -> {
            Scaffold(
                containerColor = BackgroundLight,
                bottomBar = {
                    BottomNavBar(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it }
                    )
                }
            ) { scaffoldPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding)
                ) {
                    when (selectedTab) {
                        NavTab.REMOTE -> {
                            RemoteControlScreen(
                                device = targetDevice,
                                connectionState = connectionState,
                                onSendKey = { keyCode, dir -> remoteManager.sendKey(keyCode, dir) },
                                onSendImeText = { text -> remoteManager.sendImeText(text) },
                                onSendImeTextWithFallback = { text, fallback -> remoteManager.sendImeTextWithFallback(text, fallback) },
                                onSendChar = { char -> remoteManager.sendChar(char) },
                                imeActive = imeActive,
                                lastImeText = lastImeText,
                                onOpenDevices = {
                                    displayMode = MainDisplayMode.DEVICES
                                    discoveryManager.startDiscovery()
                                },
                                onReconnect = {
                                    targetDevice?.let { dev ->
                                        onConnectToDevice(dev)
                                    }
                                },
                                onDisconnect = {
                                    remoteManager.disconnect()
                                },
                                favoriteApps = favoriteApps,
                                onLaunchApp = { app ->
                                    AppLauncher.launch(app, remoteManager)
                                },
                                onLaunchUrl = { url ->
                                    remoteManager.launchAppLink(url)
                                },
                                onPickMediaClick = {
                                    mediaPickerLauncher.launch(arrayOf("video/*", "audio/*", "*/*"))
                                },
                                onOpenAllApps = {
                                    selectedTab = NavTab.APPS
                                },
                                hapticEnabled = credentialStore.isHapticEnabled()
                            )
                        }

                        NavTab.APPS -> {
                            AppsScreen(
                                device = targetDevice,
                                connectionState = connectionState,
                                remoteManager = remoteManager,
                                allApps = allApps,
                                favoriteAppIds = favoriteAppIds,
                                onToggleFavorite = { appId ->
                                    credentialStore.toggleFavoriteApp(appId)
                                    favoriteAppIds = credentialStore.getFavoriteAppIds()
                                },
                                onAddCustomApp = { newApp ->
                                    credentialStore.saveCustomApp(newApp)
                                    customApps = credentialStore.getCustomApps()
                                    favoriteAppIds = credentialStore.getFavoriteAppIds()
                                },
                                onRemoveCustomApp = { appId ->
                                    credentialStore.removeCustomApp(appId)
                                    customApps = credentialStore.getCustomApps()
                                    favoriteAppIds = credentialStore.getFavoriteAppIds()
                                },
                                onOpenDevices = {
                                    displayMode = MainDisplayMode.DEVICES
                                    discoveryManager.startDiscovery()
                                },
                                onReconnect = {
                                    targetDevice?.let { dev ->
                                        onConnectToDevice(dev)
                                    }
                                }
                            )
                        }

                        NavTab.MEDIA -> {
                            MediaScreen(
                                device = targetDevice,
                                connectionState = connectionState,
                                onSendKey = { keyCode, dir -> remoteManager.sendKey(keyCode, dir) },
                                onOpenDevices = {
                                    displayMode = MainDisplayMode.DEVICES
                                    discoveryManager.startDiscovery()
                                },
                                onReconnect = {
                                    targetDevice?.let { dev ->
                                        onConnectToDevice(dev)
                                    }
                                },
                                castController = castController,
                                sessionState = castSessionState,
                                onPickMediaClick = {
                                    mediaPickerLauncher.launch(arrayOf("video/*", "audio/*", "*/*"))
                                }
                            )
                        }

                        NavTab.SETTINGS -> {
                            SettingsScreen(
                                currentDevice = targetDevice,
                                connectionState = connectionState,
                                savedDevices = savedDevices,
                                credentialStore = credentialStore,
                                onSelectDevice = { dev ->
                                    onConnectToDevice(dev)
                                },
                                onOpenDiscovery = {
                                    displayMode = MainDisplayMode.DEVICES
                                    discoveryManager.startDiscovery()
                                },
                                onAddManualDevice = { name, ip ->
                                    val added = discoveryManager.addManualDevice(name, ip)
                                    onConnectToDevice(added)
                                },
                                onRemoveDevice = { devId ->
                                    credentialStore.removeDevice(devId)
                                    savedDevices = credentialStore.getSavedDevices()
                                    if (targetDevice?.id == devId) {
                                        remoteManager.disconnect()
                                        targetDevice = savedDevices.firstOrNull()
                                    }
                                },
                                onRenameDevice = { devId, newName ->
                                    credentialStore.renameDevice(devId, newName)
                                    savedDevices = credentialStore.getSavedDevices()
                                    if (targetDevice?.id == devId) {
                                        targetDevice = targetDevice?.copy(name = newName)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        MainDisplayMode.DEVICES -> {
            BackHandler {
                displayMode = MainDisplayMode.TABS
            }
            DevicesScreen(
                discoveredDevices = discoveredDevices,
                isDiscovering = isDiscovering,
                onStartScan = { discoveryManager.startDiscovery() },
                onStopScan = { discoveryManager.stopDiscovery() },
                onAddManualDevice = { name, ip ->
                    val added = discoveryManager.addManualDevice(name, ip)
                    displayMode = MainDisplayMode.TABS
                    onConnectToDevice(added)
                },
                onSelectDevice = { dev ->
                    displayMode = MainDisplayMode.TABS
                    onConnectToDevice(dev)
                },
                onBack = { displayMode = MainDisplayMode.TABS }
            )
        }
    }

    // Handle PIN entry dialog overlay during pairing
    when (val state = pairingState) {
        is PairingState.WaitingForUserPin -> {
            PinEntryDialog(
                tvName = state.tvName,
                prompt = state.prompt,
                errorMessage = state.errorMessage,
                isSubmitting = state.isSubmitting,
                onPinSubmit = { pin ->
                    pairingManager.submitPin(pin)
                },
                onDismiss = {
                    pairingManager.cancelPairing()
                }
            )
        }
        else -> Unit
    }
}
