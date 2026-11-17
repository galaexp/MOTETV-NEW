package com.gala.motetv.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.gala.motetv.core.logging.TvLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.KeyPair

fun interface AdbPairingTransport {
    suspend fun pair(host: String, port: Int, pairingCode: String, clientKeyPair: KeyPair): Result<String>
}

class AdbConnectionManagerImpl(
    private val context: Context?,
    private val credentialStore: AdbCredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val sessionFactory: (String, Int, AdbConnectionMode, () -> Unit) -> AdbSession = { host, port, mode, onAwaiting ->
        AdbTcpSession(
            host = host,
            port = port,
            mode = mode,
            credentialStore = credentialStore,
            onAwaitingTvAuthorization = onAwaiting
        ).apply { connectAndAuthenticate() }
    },
    private val wirelessPairingTransport: AdbPairingTransport? = null
) : AdbConnectionManager {

    companion object {
        const val SERVICE_ADB_TLS_CONNECT = "_adb-tls-connect._tcp."
        const val SERVICE_ADB_TLS_PAIRING = "_adb-tls-pairing._tcp."
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
        private val COMPONENT_NAME_REGEX = Regex("^[a-zA-Z0-9._/$]+$")
        private val SAFE_PATH_REGEX = Regex("^/sdcard/Android/data/com\\.gathin\\.tvhome/files/[a-zA-Z0-9._/-]+$")
    }

    private val _connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    override val connectionState: StateFlow<AdbConnectionState> = _connectionState.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<DiscoveredAdbService>>(emptyList())
    override val discoveredServices: StateFlow<List<DiscoveredAdbService>> = _discoveredServices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _savedEndpoint = MutableStateFlow(credentialStore.getSavedEndpoint())
    override val savedEndpoint: StateFlow<AdbEndpointConfig?> = _savedEndpoint.asStateFlow()

    private val _lastDiagnosticDetail = MutableStateFlow("ADB not connected")
    override val lastDiagnosticDetail: StateFlow<String> = _lastDiagnosticDetail.asStateFlow()

    override val pairingCapability: AdbPairingCapability = if (wirelessPairingTransport != null) {
        AdbPairingCapability(
            isNativeSpake2Supported = true,
            explanation = "Wireless Debugging SPAKE2 pairing transport available."
        )
    } else {
        AdbPairingCapability(
            isNativeSpake2Supported = false,
            explanation = "Android's public SSLContext API does not expose BoringSSL SPAKE2 PSK ciphers required for Android 11+ Wireless Debugging 6-digit pairing from an unprivileged app. Use Traditional Network ADB (port 5555) or pair once from a computer ('adb pair') and then enter the TV's Wireless Debugging Connect port below."
        )
    }

    private val sessionMutex = Mutex()
    private var activeSession: AdbSession? = null
    private var connectJob: Job? = null

    private val nsdManager: NsdManager? by lazy {
        context?.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null

    override fun startWirelessDiscovery() {
        val manager = nsdManager ?: run {
            _lastDiagnosticDetail.value = "mDNS discovery unavailable without Android context"
            return
        }
        stopWirelessDiscovery()
        _discoveredServices.value = emptyList()
        _isDiscovering.value = true

        connectDiscoveryListener = registerNsdListener(manager, SERVICE_ADB_TLS_CONNECT, isPairing = false)
        pairingDiscoveryListener = registerNsdListener(manager, SERVICE_ADB_TLS_PAIRING, isPairing = true)
    }

    @Suppress("DEPRECATION")
    private fun registerNsdListener(
        manager: NsdManager,
        serviceType: String,
        isPairing: Boolean
    ): NsdManager.DiscoveryListener? {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: return
                        val port = serviceInfo.port
                        val entry = DiscoveredAdbService(
                            serviceName = serviceInfo.serviceName,
                            host = host,
                            port = port,
                            serviceType = serviceType,
                            isPairingService = isPairing
                        )
                        val existing = _discoveredServices.value.toMutableList()
                        existing.removeAll { it.host == host && it.port == port }
                        existing.add(entry)
                        _discoveredServices.value = existing
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _discoveredServices.value = _discoveredServices.value.filterNot {
                    it.serviceName == service.serviceName && it.serviceType == serviceType
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _lastDiagnosticDetail.value = "NSD discovery failed for $serviceType (code $errorCode)"
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        return try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            listener
        } catch (e: Exception) {
            _lastDiagnosticDetail.value = "NSD error ($serviceType): ${e.message}"
            null
        }
    }

    override fun stopWirelessDiscovery() {
        val manager = nsdManager
        listOf(connectDiscoveryListener, pairingDiscoveryListener).forEach { listener ->
            if (listener != null && manager != null) {
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (_: Exception) {
                }
            }
        }
        connectDiscoveryListener = null
        pairingDiscoveryListener = null
        _isDiscovering.value = false
    }

    override fun connect(host: String, port: Int, mode: AdbConnectionMode) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank() || port !in 1..65535) {
            _connectionState.value = AdbConnectionState.Error(
                code = "ERR_ADB_INVALID_ENDPOINT",
                userMessage = "Enter a valid TV IP address and port (1–65535).",
                technicalDetail = "host='$host', port=$port"
            )
            return
        }

        connectJob?.cancel()
        connectJob = scope.launch(ioDispatcher) {
            sessionMutex.withLock {
                closeSessionInternal()
                _connectionState.value = AdbConnectionState.Connecting(cleanHost, port, mode)
                _lastDiagnosticDetail.value = "Connecting ADB to $cleanHost:$port (${mode.displayName})..."

                try {
                    val fingerprint = credentialStore.getKeyFingerprint()
                    val session = sessionFactory(cleanHost, port, mode) {
                        _connectionState.value = AdbConnectionState.AwaitingTvAuthorization(
                            host = cleanHost,
                            port = port,
                            keyFingerprint = fingerprint
                        )
                        _lastDiagnosticDetail.value = "Approve the 'Allow USB/Network Debugging' prompt on your TV (Key: $fingerprint)."
                    }
                    activeSession = session

                    val endpoint = AdbEndpointConfig(
                        host = cleanHost,
                        port = port,
                        mode = mode,
                        lastConnectedMs = System.currentTimeMillis()
                    )
                    credentialStore.saveEndpoint(endpoint)
                    _savedEndpoint.value = endpoint

                    val devInfo = inspectTvDeviceInternal(session).getOrNull()
                    _connectionState.value = AdbConnectionState.Connected(
                        host = cleanHost,
                        port = port,
                        mode = mode,
                        banner = session.banner,
                        deviceInfo = devInfo
                    )
                    _lastDiagnosticDetail.value = "ADB connected to $cleanHost:$port (${devInfo?.model ?: session.banner})"
                } catch (e: Exception) {
                    closeSessionInternal()
                    val hint = when {
                        e.message?.contains("ECONNREFUSED", ignoreCase = true) == true ->
                            "TV refused port $port. Enable USB/Network Debugging in Android TV Developer Options."
                        e.message?.contains("timed out", ignoreCase = true) == true ||
                            e.message?.contains("SocketTimeout", ignoreCase = true) == true ->
                            "Timed out waiting for TV response or debugging authorization prompt."
                        mode == AdbConnectionMode.WIRELESS_DEBUGGING_TLS ->
                            "Wireless Debugging TLS handshake failed. Ensure this MoteTV key was paired or use Network ADB on port 5555."
                        else -> e.message ?: "ADB connection error"
                    }
                    _connectionState.value = AdbConnectionState.Error(
                        code = "ERR_ADB_CONNECT",
                        userMessage = hint,
                        technicalDetail = "${e::class.simpleName}: ${e.message}"
                    )
                    _lastDiagnosticDetail.value = "ADB error: $hint (${e.message})"
                    TvLogger.w(TvLogger.TAG_PAIR, "ADB connection error: ${e.message}")
                }
            }
        }
    }

    override fun disconnect() {
        connectJob?.cancel()
        scope.launch(ioDispatcher) {
            sessionMutex.withLock {
                closeSessionInternal()
                _connectionState.value = AdbConnectionState.Disconnected
                _lastDiagnosticDetail.value = "ADB disconnected"
            }
        }
    }

    override suspend fun pairWirelessDebugging(
        host: String,
        pairingPort: Int,
        pairingCode: String
    ): Result<String> = withContext(ioDispatcher) {
        val transport = wirelessPairingTransport
        if (transport == null) {
            val msg = pairingCapability.explanation
            _lastDiagnosticDetail.value = msg
            return@withContext Result.failure(UnsupportedOperationException(msg))
        }
        val cleanCode = pairingCode.trim()
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
            return@withContext Result.failure(IllegalArgumentException("Pairing code must be a 6-digit number."))
        }
        transport.pair(host.trim(), pairingPort, cleanCode, credentialStore.getOrCreateAdbKeyPair())
    }

    override suspend fun inspectTvDevice(): Result<TvDeviceInfo> = withContext(ioDispatcher) {
        sessionMutex.withLock {
            val session = activeSession ?: return@withContext Result.failure(IllegalStateException("ADB not connected"))
            inspectTvDeviceInternal(session)
        }
    }

    private suspend fun inspectTvDeviceInternal(session: AdbSession): Result<TvDeviceInfo> {
        return try {
            val propsRaw = session.openShell(
                "echo MFG=$(getprop ro.product.manufacturer); " +
                    "echo MOD=$(getprop ro.product.model); " +
                    "echo DEV=$(getprop ro.product.device); " +
                    "echo BRD=$(getprop ro.product.board); " +
                    "echo REL=$(getprop ro.build.version.release); " +
                    "echo SDK=$(getprop ro.build.version.sdk); " +
                    "echo BID=$(getprop ro.build.display.id); " +
                    "echo FGP=$(getprop ro.build.fingerprint)"
            ).stdout

            val props = propsRaw.lines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
                }
                .toMap()

            val homeRaw = session.openShell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").stdout
            val homeComponent = homeRaw.lines()
                .map { it.trim() }
                .lastOrNull { it.contains('/') && !it.startsWith("priority") }
            val homePkg = homeComponent?.substringBefore('/')?.takeIf { it.isNotBlank() }

            val info = TvDeviceInfo(
                manufacturer = props["MFG"]?.ifBlank { null } ?: "Unknown",
                model = props["MOD"]?.ifBlank { null } ?: "Android TV",
                deviceName = props["DEV"]?.ifBlank { null } ?: "tv",
                productBoard = props["BRD"]?.ifBlank { null } ?: "",
                androidVersion = props["REL"]?.ifBlank { null } ?: Build.VERSION.RELEASE,
                sdkVersion = props["SDK"]?.toIntOrNull() ?: 0,
                buildId = props["BID"]?.ifBlank { null } ?: "",
                buildFingerprint = props["FGP"]?.ifBlank { null } ?: "",
                currentHomePackage = homePkg,
                currentHomeActivity = homeComponent,
                currentHomeLabel = homePkg
            )
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listAllPackages(): Result<List<String>> = withContext(ioDispatcher) {
        runTypedShell("pm list packages") { out ->
            out.lines()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) trimmed.removePrefix("package:").trim() else null
                }
                .filter { it.isNotBlank() }
                .sorted()
        }
    }

    override suspend fun listDisabledPackages(): Result<Set<String>> = withContext(ioDispatcher) {
        runTypedShell("pm list packages -d") { out ->
            out.lines()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) trimmed.removePrefix("package:").trim() else null
                }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    override suspend fun disablePackageForUser(packageName: String): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (!PACKAGE_NAME_REGEX.matches(packageName)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        runValidatedPmCommand("pm disable-user --user 0 $packageName")
    }

    override suspend fun enablePackage(packageName: String): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (!PACKAGE_NAME_REGEX.matches(packageName)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        runValidatedPmCommand("pm enable $packageName")
    }

    override suspend fun launchHomeChooserOrOpenHome(): Result<AdbCommandResult> = withContext(ioDispatcher) {
        runTypedShellResult("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
    }

    override suspend fun setDefaultHomeActivity(componentName: String): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (!COMPONENT_NAME_REGEX.matches(componentName) || !componentName.contains('/')) {
            return@withContext Result.failure(IllegalArgumentException("Invalid component name: $componentName"))
        }
        runTypedShellResult("cmd package set-home-activity $componentName")
    }

    override suspend fun installApk(apkBytes: ByteArray): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (apkBytes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("APK payload is empty"))
        }
        sessionMutex.withLock {
            val session = activeSession ?: return@withContext Result.failure(IllegalStateException("ADB not connected"))
            val remoteTemp = "/data/local/tmp/cleanyourtv_home.apk"
            val pushRes = session.pushFile(remoteTemp, apkBytes)
            if (!pushRes.isSuccess) {
                return@withContext Result.failure(IOException(pushRes.stderr.ifBlank { "Failed to push APK via ADB sync" }))
            }
            val installRes = session.openShell("pm install -r $remoteTemp")
            session.openShell("rm -f $remoteTemp")
            if (installRes.stdout.contains("Success", ignoreCase = true)) {
                Result.success(AdbCommandResult(exitCode = 0, stdout = installRes.stdout.trim()))
            } else {
                Result.failure(IOException("APK install failed: ${installRes.stdout.trim()} ${installRes.stderr}".trim()))
            }
        }
    }

    override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (!SAFE_PATH_REGEX.matches(remotePath)) {
            return@withContext Result.failure(IllegalArgumentException("Refusing push outside CleanYourTV app files dir: $remotePath"))
        }
        sessionMutex.withLock {
            val session = activeSession ?: return@withContext Result.failure(IllegalStateException("ADB not connected"))
            val res = session.pushFile(remotePath, content)
            if (res.isSuccess) Result.success(res) else Result.failure(IOException(res.stderr.ifBlank { "ADB file push failed" }))
        }
    }

    override suspend fun ensureRemoteDirectory(remoteDir: String): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (!remoteDir.startsWith("/sdcard/Android/data/com.gathin.tvhome/files") || remoteDir.contains("..")) {
            return@withContext Result.failure(IllegalArgumentException("Unsafe directory path: $remoteDir"))
        }
        runTypedShellResult("mkdir -p $remoteDir")
    }

    override suspend fun removeRemoteFile(remotePath: String): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (!SAFE_PATH_REGEX.matches(remotePath)) {
            return@withContext Result.failure(IllegalArgumentException("Refusing remove outside CleanYourTV files dir: $remotePath"))
        }
        runTypedShellResult("rm -f $remotePath")
    }

    override suspend fun checkFilePushSupported(): Result<Boolean> = withContext(ioDispatcher) {
        runTypedShell("ls -d /sdcard/Android/data >/dev/null 2>&1 && echo OK || echo RESTRICTED") { out ->
            out.contains("OK")
        }
    }

    private suspend fun <T> runTypedShell(command: String, parser: (String) -> T): Result<T> {
        return sessionMutex.withLock {
            val session = activeSession ?: return@withLock Result.failure(IllegalStateException("ADB not connected"))
            try {
                val res = session.openShell(command)
                Result.success(parser(res.stdout))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun runTypedShellResult(command: String): Result<AdbCommandResult> {
        return sessionMutex.withLock {
            val session = activeSession ?: return@withLock Result.failure(IllegalStateException("ADB not connected"))
            try {
                Result.success(session.openShell(command))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun runValidatedPmCommand(command: String): Result<AdbCommandResult> {
        return sessionMutex.withLock {
            val session = activeSession ?: return@withLock Result.failure(IllegalStateException("ADB not connected"))
            try {
                val res = session.openShell(command)
                val out = res.stdout.trim()
                val failed = out.contains("Exception", ignoreCase = true) ||
                    out.contains("Error:", ignoreCase = true) ||
                    out.contains("SecurityException", ignoreCase = true) ||
                    out.contains("Unknown package", ignoreCase = true)
                if (failed) {
                    Result.failure(IOException(out.ifBlank { "Package manager command failed" }))
                } else {
                    Result.success(AdbCommandResult(exitCode = 0, stdout = out))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun closeSessionInternal() {
        try {
            activeSession?.close()
        } catch (_: Exception) {
        }
        activeSession = null
    }
}
