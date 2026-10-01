package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.core.util.NetworkMonitor
import com.gala.motetv.discovery.NsdTvDiscoveryManager
import com.gala.motetv.pairing.PairingState
import com.gala.motetv.pairing.TvPairingManagerImpl
import com.gala.motetv.remote.TvRemoteManagerImpl
import com.gala.motetv.storage.TvCredentialStore
import com.gala.motetv.ui.components.PinEntryDialog
import com.gala.motetv.ui.screens.DevicesScreen
import com.gala.motetv.ui.screens.RemoteControlScreen
import com.gala.motetv.ui.theme.MoteTvTheme

enum class AppScreen {
    Remote,
    Devices
}

class MainActivity : ComponentActivity() {

    private lateinit var credentialStore: TvCredentialStore
    private lateinit var discoveryManager: NsdTvDiscoveryManager
    private lateinit var pairingManager: TvPairingManagerImpl
    private lateinit var remoteManager: TvRemoteManagerImpl
    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TvLogger.i(TvLogger.TAG_GENERAL, "MoteTV Android TV Remote initialized")

        credentialStore = TvCredentialStore(applicationContext)
        discoveryManager = NsdTvDiscoveryManager(applicationContext)
        pairingManager = TvPairingManagerImpl(credentialStore)
        remoteManager = TvRemoteManagerImpl(credentialStore)
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
                    remoteManager = remoteManager
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stopDiscovery()
        remoteManager.disconnect()
        pairingManager.cancelPairing()
        networkMonitor.stopMonitoring()
    }
}

@Composable
fun MainAppHost(
    credentialStore: TvCredentialStore,
    discoveryManager: NsdTvDiscoveryManager,
    pairingManager: TvPairingManagerImpl,
    remoteManager: TvRemoteManagerImpl
) {
    var currentScreen by remember { mutableStateOf(AppScreen.Remote) }
    var targetDevice by remember { mutableStateOf<TvDevice?>(null) }

    val discoveredDevices by discoveryManager.discoveredDevices.collectAsStateWithLifecycle()
    val isDiscovering by discoveryManager.isDiscovering.collectAsStateWithLifecycle()
    val pairingState by pairingManager.pairingState.collectAsStateWithLifecycle()
    val connectionState by remoteManager.connectionState.collectAsStateWithLifecycle()

    // When pairing succeeds, automatically connect to remote port 6466
    LaunchedEffect(pairingState) {
        if (pairingState is PairingState.Paired) {
            val pairedDev = (pairingState as PairingState.Paired).device
            targetDevice = pairedDev
            remoteManager.connect(pairedDev)
        }
    }

    // Auto-select first discovered TV if none selected
    LaunchedEffect(discoveredDevices) {
        if (targetDevice == null && discoveredDevices.isNotEmpty()) {
            val first = discoveredDevices.first()
            targetDevice = first
            if (credentialStore.isDevicePaired(first.id)) {
                remoteManager.connect(first)
            }
        }
    }

    when (currentScreen) {
        AppScreen.Remote -> {
            RemoteControlScreen(
                device = targetDevice,
                connectionState = connectionState,
                onSendKey = { keyCode, dir -> remoteManager.sendKey(keyCode, dir) },
                onSendImeText = { text -> remoteManager.sendImeText(text) },
                onOpenDevices = {
                    currentScreen = AppScreen.Devices
                    discoveryManager.startDiscovery()
                },
                onReconnect = {
                    targetDevice?.let { dev ->
                        if (credentialStore.isDevicePaired(dev.id)) {
                            remoteManager.connect(dev)
                        } else {
                            pairingManager.startPairing(dev)
                        }
                    }
                },
                onDisconnect = {
                    remoteManager.disconnect()
                }
            )
        }
        AppScreen.Devices -> {
            BackHandler {
                currentScreen = AppScreen.Remote
            }
            DevicesScreen(
                discoveredDevices = discoveredDevices,
                isDiscovering = isDiscovering,
                onStartScan = { discoveryManager.startDiscovery() },
                onStopScan = { discoveryManager.stopDiscovery() },
                onAddManualDevice = { name, ip ->
                    val added = discoveryManager.addManualDevice(name, ip)
                    targetDevice = added
                    currentScreen = AppScreen.Remote
                    if (credentialStore.isDevicePaired(added.id)) {
                        remoteManager.connect(added)
                    } else {
                        pairingManager.startPairing(added)
                    }
                },
                onSelectDevice = { dev ->
                    targetDevice = dev
                    currentScreen = AppScreen.Remote
                    if (credentialStore.isDevicePaired(dev.id)) {
                        remoteManager.connect(dev)
                    } else {
                        pairingManager.startPairing(dev)
                    }
                },
                onBack = { currentScreen = AppScreen.Remote }
            )
        }
    }

    // Handle PIN entry dialog overlay during pairing
    when (val state = pairingState) {
        is PairingState.WaitingForUserPin -> {
            PinEntryDialog(
                tvName = state.tvName,
                prompt = state.prompt,
                onPinSubmit = { pin ->
                    pairingManager.submitPin(pin)
                },
                onDismiss = {
                    pairingManager.cancelPairing()
                }
            )
        }
        is PairingState.Failed -> {
            // Error toast / retry affordance in badge
        }
        else -> Unit
    }
}
