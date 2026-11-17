package com.gala.motetv.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.gala.motetv.core.logging.TvLogger
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicLong

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
    private val wirelessPairingTransport: AdbPairingTransport? = defaultPairingTransport(credentialStore),
    private val commandTimeoutMs: Long = 15_000L,
    private val pairingTimeoutMs: Long = 12_000L
) : AdbConnectionManager {

    companion object {
        const val SERVICE_ADB_TLS_CONNECT = "_adb-tls-connect._tcp."
        const val SERVICE_ADB_TLS_PAIRING = "_adb-tls-pairing._tcp."
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
        private val COMPONENT_NAME_REGEX = Regex("^[a-zA-Z0-9._/$]+$")
        private val SAFE_PATH_REGEX = Regex("^/sdcard/Android/data/com\\.gathin\\.tvhome/files/[a-zA-Z0-9._/-]+$")

        fun defaultPairingTransport(credentialStore: AdbCredentialStore): AdbPairingTransport? {
            return if (NativeWirelessAdbPairing.isNativeSupportedOnDevice()) {
                NativeWirelessAdbPairing(credentialStore)
            } else {
                null
            }
        }
    }

    private val _connectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    override val connectionState: StateFlow<AdbConnectionState> = _connectionState.asStateFlow()

    private val _pairingState = MutableStateFlow<AdbPairingState>(AdbPairingState.Idle)
    override val pairingState: StateFlow<AdbPairingState> = _pairingState.asStateFlow()

    private val _diagnosticCategory = MutableStateFlow(AdbDiagnosticCategory.NONE)
    override val diagnosticCategory: StateFlow<AdbDiagnosticCategory> = _diagnosticCategory.asStateFlow()

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
            explanation = "Native Wireless Debugging TLS pairing (SPAKE2 + Ed25519) active. Pair first using the TV's Pairing port & 6-digit code, then connect using the separate Wireless Debugging Connect port."
        )
    } else {
        AdbPairingCapability(
            isNativeSpake2Supported = false,
            explanation = "In-app Wireless Debugging TLS pairing requires Android 11+ (API 30) on this phone for TLS 1.3 keying material export. Use Traditional Network ADB (port 5555) or pair once via a computer ('adb pair') and connect via the TV's Wireless Debugging Connect port."
        )
    }

    private val sessionMutex = Mutex()
    private val pairingMutex = Mutex()
    private val jobLock = Any()
    private val connectGeneration = AtomicLong(0L)
    private var activeSession: AdbSession? = null
    private var connectJob: Job? = null
    private var pairingJob: Job? = null

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
            setErrorState(
                code = "ERR_ADB_INVALID_ENDPOINT",
                category = AdbDiagnosticCategory.TV_UNAVAILABLE,
                userMessage = "Enter a valid TV IP address and port (1–65535).",
                technicalDetail = "host='$host', port=$port"
            )
            return
        }

        val myGeneration = connectGeneration.incrementAndGet()
        synchronized(jobLock) {
            pairingJob?.cancel()
            pairingJob = null
            connectJob?.cancel()
            connectJob = scope.launch(ioDispatcher) {
                sessionMutex.withLock {
                    if (connectGeneration.get() != myGeneration) return@withLock
                    closeSessionInternal()
                    _diagnosticCategory.value = AdbDiagnosticCategory.NONE
                    _connectionState.value = AdbConnectionState.Connecting(cleanHost, port, mode)
                    _lastDiagnosticDetail.value = "Connecting ADB to $cleanHost:$port (${mode.displayName})..."

                    var createdSession: AdbSession? = null
                    try {
                        val fingerprint = credentialStore.getKeyFingerprint()
                        createdSession = sessionFactory(cleanHost, port, mode) {
                            if (connectGeneration.get() == myGeneration) {
                                _connectionState.value = AdbConnectionState.AwaitingTvAuthorization(
                                    host = cleanHost,
                                    port = port,
                                    keyFingerprint = fingerprint
                                )
                                _lastDiagnosticDetail.value =
                                    "Approve the 'Allow USB/Network Debugging' prompt on your TV (Key: $fingerprint)."
                            }
                        }

                        if (connectGeneration.get() != myGeneration) {
                            try {
                                createdSession.close()
                            } catch (_: Exception) {
                            }
                            return@withLock
                        }

                        activeSession = createdSession
                        val endpoint = AdbEndpointConfig(
                            host = cleanHost,
                            port = port,
                            mode = mode,
                            lastConnectedMs = System.currentTimeMillis()
                        )
                        credentialStore.saveEndpoint(endpoint)
                        _savedEndpoint.value = endpoint

                        val devInfo = withTimeout(commandTimeoutMs) {
                            inspectTvDeviceInternal(createdSession).getOrNull()
                        }
                        if (connectGeneration.get() != myGeneration) {
                            closeSessionInternal()
                            return@withLock
                        }

                        _diagnosticCategory.value = AdbDiagnosticCategory.NONE
                        _connectionState.value = AdbConnectionState.Connected(
                            host = cleanHost,
                            port = port,
                            mode = mode,
                            banner = createdSession.banner,
                            deviceInfo = devInfo
                        )
                        _lastDiagnosticDetail.value =
                            "ADB connected to $cleanHost:$port (${devInfo?.model ?: createdSession.banner})"
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        try {
                            createdSession?.close()
                        } catch (_: Exception) {
                        }
                        closeSessionInternal()
                        val (cat, hint) = classifyException(e, cleanHost, port, mode, defaultCat = AdbDiagnosticCategory.CONNECTION_TIMEOUT)
                        if (connectGeneration.get() == myGeneration) {
                            setErrorState(
                                code = "ERR_ADB_TIMEOUT",
                                category = cat,
                                userMessage = hint,
                                technicalDetail = "${e::class.simpleName}: ${e.message}"
                            )
                        }
                        TvLogger.w(TvLogger.TAG_PAIR, "ADB connection timed out ($cat): ${e.message}")
                    } catch (e: CancellationException) {
                        try {
                            createdSession?.close()
                        } catch (_: Exception) {
                        }
                        if (activeSession === createdSession) {
                            activeSession = null
                        }
                        throw e
                    } catch (e: Exception) {
                        try {
                            createdSession?.close()
                        } catch (_: Exception) {
                        }
                        closeSessionInternal()
                        val (cat, hint) = classifyException(e, cleanHost, port, mode, defaultCat = AdbDiagnosticCategory.TV_UNAVAILABLE)
                        if (connectGeneration.get() == myGeneration) {
                            setErrorState(
                                code = "ERR_ADB_CONNECT",
                                category = cat,
                                userMessage = hint,
                                technicalDetail = "${e::class.simpleName}: ${e.message}"
                            )
                        }
                        TvLogger.w(TvLogger.TAG_PAIR, "ADB connection error ($cat): ${e.message}")
                    }
                }
            }
        }
    }

    override fun disconnect() {
        connectGeneration.incrementAndGet()
        synchronized(jobLock) {
            pairingJob?.cancel()
            pairingJob = null
            connectJob?.cancel()
            connectJob = null
        }
        scope.launch(ioDispatcher) {
            sessionMutex.withLock {
                closeSessionInternal()
                _diagnosticCategory.value = AdbDiagnosticCategory.NONE
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
            _diagnosticCategory.value = AdbDiagnosticCategory.PAIRING_FAILURE
            _pairingState.value = AdbPairingState.Failed(AdbDiagnosticCategory.PAIRING_FAILURE, msg)
            _lastDiagnosticDetail.value = msg
            return@withContext Result.failure(UnsupportedOperationException(msg))
        }

        val cleanHost = host.trim()
        val cleanCode = pairingCode.trim()
        if (cleanHost.isBlank() || pairingPort !in 1..65535) {
            val err = IllegalArgumentException("Enter a valid TV IP address and pairing port (1–65535).")
            _diagnosticCategory.value = AdbDiagnosticCategory.PAIRING_FAILURE
            _pairingState.value = AdbPairingState.Failed(AdbDiagnosticCategory.PAIRING_FAILURE, err.message!!)
            _lastDiagnosticDetail.value = err.message!!
            return@withContext Result.failure(err)
        }
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
            val err = IllegalArgumentException("Pairing code must be a 6-digit number.")
            _diagnosticCategory.value = AdbDiagnosticCategory.PAIRING_FAILURE
            _pairingState.value = AdbPairingState.Failed(AdbDiagnosticCategory.PAIRING_FAILURE, err.message!!)
            _lastDiagnosticDetail.value = err.message!!
            return@withContext Result.failure(err)
        }

        // Prevent overlapping pairing or connect jobs
        val previousPairing: Job?
        synchronized(jobLock) {
            previousPairing = pairingJob
            previousPairing?.cancel()
            connectJob?.cancel()
            pairingJob = coroutineContext[Job]
        }

        pairingMutex.withLock {
            _pairingState.value = AdbPairingState.Pairing(cleanHost, pairingPort)
            _diagnosticCategory.value = AdbDiagnosticCategory.NONE
            _lastDiagnosticDetail.value = "Pairing Wireless Debugging at $cleanHost:$pairingPort..."

            try {
                val res = withTimeout(pairingTimeoutMs) {
                    transport.pair(cleanHost, pairingPort, cleanCode, credentialStore.getOrCreateAdbKeyPair())
                }
                res.onSuccess { detail ->
                    _diagnosticCategory.value = AdbDiagnosticCategory.NONE
                    _pairingState.value = AdbPairingState.Paired(cleanHost, detail)
                    _lastDiagnosticDetail.value = detail
                }.onFailure { err ->
                    val (cat, msg) = classifyException(
                        err, cleanHost, pairingPort, AdbConnectionMode.WIRELESS_DEBUGGING_TLS,
                        defaultCat = AdbDiagnosticCategory.PAIRING_FAILURE
                    )
                    _diagnosticCategory.value = cat
                    _pairingState.value = AdbPairingState.Failed(cat, msg)
                    _lastDiagnosticDetail.value = "${cat.label}: $msg"
                }
                res
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val (cat, msg) = classifyException(
                    e, cleanHost, pairingPort, AdbConnectionMode.WIRELESS_DEBUGGING_TLS,
                    defaultCat = AdbDiagnosticCategory.CONNECTION_TIMEOUT
                )
                _diagnosticCategory.value = cat
                _pairingState.value = AdbPairingState.Failed(cat, msg)
                _lastDiagnosticDetail.value = "${cat.label}: $msg"
                Result.failure(AdbPairingException(cat, msg, e))
            } catch (e: CancellationException) {
                _lastDiagnosticDetail.value = "Wireless Debugging pairing cancelled"
                throw e
            } catch (e: Exception) {
                val (cat, msg) = classifyException(
                    e, cleanHost, pairingPort, AdbConnectionMode.WIRELESS_DEBUGGING_TLS,
                    defaultCat = AdbDiagnosticCategory.PAIRING_FAILURE
                )
                _diagnosticCategory.value = cat
                _pairingState.value = AdbPairingState.Failed(cat, msg)
                _lastDiagnosticDetail.value = "${cat.label}: $msg"
                Result.failure(AdbPairingException(cat, msg, e))
            } finally {
                synchronized(jobLock) {
                    if (pairingJob === coroutineContext[Job]) {
                        pairingJob = null
                    }
                }
            }
        }
    }

    override suspend fun inspectTvDevice(): Result<TvDeviceInfo> = withContext(ioDispatcher) {
        withActiveSession(AdbDiagnosticCategory.SHELL_FAILURE) { session ->
            inspectTvDeviceInternal(session).getOrThrow()
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

            val homeRaw = session.openShell(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
            ).stdout
            val homeComponent = homeRaw.lines()
                .map { it.trim() }
                .lastOrNull { it.contains('/') && !it.startsWith("priority") }
            val homePkg = homeComponent?.substringBefore('/')?.takeIf { it.isNotBlank() }

            Result.success(
                TvDeviceInfo(
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
            )
        } catch (e: CancellationException) {
            throw e
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

    override suspend fun restartCleanYourTvLauncher(): Result<AdbCommandResult> = withContext(ioDispatcher) {
        withActiveSession(AdbDiagnosticCategory.SHELL_FAILURE) { session ->
            session.openShell("am force-stop com.gathin.tvhome")
            session.openShell("am start -n com.gathin.tvhome/.MainActivity")
        }
    }

    override suspend fun installApk(apkBytes: ByteArray): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (apkBytes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("APK payload is empty"))
        }
        withActiveSession(AdbDiagnosticCategory.FILE_TRANSFER_FAILURE) { session ->
            val remoteTemp = "/data/local/tmp/cleanyourtv_home.apk"
            val pushRes = session.pushFile(remoteTemp, apkBytes)
            if (!pushRes.isSuccess) {
                throw AdbOperationException(
                    AdbDiagnosticCategory.FILE_TRANSFER_FAILURE,
                    pushRes.stderr.ifBlank { "Failed to push APK via ADB sync" }
                )
            }
            val installRes = session.openShell("pm install -r $remoteTemp")
            session.openShell("rm -f $remoteTemp")
            if (installRes.stdout.contains("Success", ignoreCase = true)) {
                AdbCommandResult(exitCode = 0, stdout = installRes.stdout.trim())
            } else {
                throw AdbOperationException(
                    AdbDiagnosticCategory.SHELL_FAILURE,
                    "APK install failed: ${installRes.stdout.trim()} ${installRes.stderr}".trim()
                )
            }
        }
    }

    override suspend fun pushAppConfigFile(remotePath: String, content: ByteArray): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (!SAFE_PATH_REGEX.matches(remotePath) || remotePath.contains("..")) {
            return@withContext Result.failure(IllegalArgumentException("Refusing push outside CleanYourTV app files dir: $remotePath"))
        }
        withActiveSession(AdbDiagnosticCategory.FILE_TRANSFER_FAILURE) { session ->
            val res = session.pushFile(remotePath, content)
            if (res.isSuccess) res
            else throw AdbOperationException(
                AdbDiagnosticCategory.FILE_TRANSFER_FAILURE,
                res.stderr.ifBlank { "ADB file push failed" }
            )
        }
    }

    override suspend fun readRemoteFile(remotePath: String): Result<ByteArray> = withContext(ioDispatcher) {
        if (!SAFE_PATH_REGEX.matches(remotePath) || remotePath.contains("..")) {
            return@withContext Result.failure(IllegalArgumentException("Refusing read outside CleanYourTV app files dir: $remotePath"))
        }
        withActiveSession(AdbDiagnosticCategory.FILE_TRANSFER_FAILURE) { session ->
            session.pullFile(remotePath)
        }
    }

    override suspend fun remoteFileExists(remotePath: String): Result<Boolean> = withContext(ioDispatcher) {
        if (!SAFE_PATH_REGEX.matches(remotePath) || remotePath.contains("..")) {
            return@withContext Result.failure(IllegalArgumentException("Refusing check outside CleanYourTV app files dir: $remotePath"))
        }
        runTypedShell("test -s '$remotePath' && echo EXISTS || echo MISSING") { out ->
            out.contains("EXISTS")
        }
    }

    override suspend fun ensureRemoteDirectory(remoteDir: String): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (!remoteDir.startsWith("/sdcard/Android/data/com.gathin.tvhome/files") || remoteDir.contains("..")) {
            return@withContext Result.failure(IllegalArgumentException("Unsafe directory path: $remoteDir"))
        }
        runTypedShellResult("mkdir -p $remoteDir")
    }

    override suspend fun removeRemoteFile(remotePath: String): Result<AdbCommandResult> = withContext(ioDispatcher) {
        if (!SAFE_PATH_REGEX.matches(remotePath) || remotePath.contains("..")) {
            return@withContext Result.failure(IllegalArgumentException("Refusing remove outside CleanYourTV files dir: $remotePath"))
        }
        runTypedShellResult("rm -f $remotePath")
    }

    override suspend fun checkFilePushSupported(): Result<Boolean> = withContext(ioDispatcher) {
        runTypedShell("ls -d /sdcard/Android/data >/dev/null 2>&1 && echo OK || echo RESTRICTED") { out ->
            out.contains("OK")
        }
    }

    private suspend fun <T> withActiveSession(
        defaultCategory: AdbDiagnosticCategory,
        block: suspend (AdbSession) -> T
    ): Result<T> {
        return sessionMutex.withLock {
            val session = activeSession
            if (session == null) {
                _diagnosticCategory.value = AdbDiagnosticCategory.TV_DISCONNECTED
                return@withLock Result.failure(
                    AdbOperationException(AdbDiagnosticCategory.TV_DISCONNECTED, "ADB not connected")
                )
            }
            if (!session.isConnected) {
                handleUnexpectedDisconnect("ADB socket closed")
                return@withLock Result.failure(
                    AdbOperationException(AdbDiagnosticCategory.TV_DISCONNECTED, "ADB connection lost cleanly; reconnect TV.")
                )
            }
            try {
                val res = withTimeout(commandTimeoutMs) {
                    block(session)
                }
                _diagnosticCategory.value = AdbDiagnosticCategory.NONE
                Result.success(res)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val cat = AdbDiagnosticCategory.CONNECTION_TIMEOUT
                _diagnosticCategory.value = cat
                _lastDiagnosticDetail.value = "${cat.label}: Command timed out after ${commandTimeoutMs}ms"
                Result.failure(AdbOperationException(cat, "ADB command timed out after ${commandTimeoutMs}ms", e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cat = extractCategory(e, defaultCategory)
                _diagnosticCategory.value = cat
                _lastDiagnosticDetail.value = "${cat.label}: ${e.message}"
                if (cat == AdbDiagnosticCategory.TV_DISCONNECTED || !session.isConnected) {
                    handleUnexpectedDisconnect(e.message ?: "TV disconnected")
                }
                Result.failure(e)
            }
        }
    }

    private suspend fun <T> runTypedShell(command: String, parser: (String) -> T): Result<T> {
        return withActiveSession(AdbDiagnosticCategory.SHELL_FAILURE) { session ->
            val res = session.openShell(command)
            parser(res.stdout)
        }
    }

    private suspend fun runTypedShellResult(command: String): Result<AdbCommandResult> {
        return withActiveSession(AdbDiagnosticCategory.SHELL_FAILURE) { session ->
            session.openShell(command)
        }
    }

    private suspend fun runValidatedPmCommand(command: String): Result<AdbCommandResult> {
        return withActiveSession(AdbDiagnosticCategory.SHELL_FAILURE) { session ->
            val res = session.openShell(command)
            val out = res.stdout.trim()
            val failed = out.contains("Exception", ignoreCase = true) ||
                out.contains("Error:", ignoreCase = true) ||
                out.contains("SecurityException", ignoreCase = true) ||
                out.contains("Unknown package", ignoreCase = true)
            if (failed) {
                throw AdbOperationException(
                    AdbDiagnosticCategory.SHELL_FAILURE,
                    out.ifBlank { "Package manager command failed" }
                )
            }
            AdbCommandResult(exitCode = 0, stdout = out)
        }
    }

    private fun handleUnexpectedDisconnect(reason: String) {
        closeSessionInternal()
        _diagnosticCategory.value = AdbDiagnosticCategory.TV_DISCONNECTED
        _connectionState.value = AdbConnectionState.Error(
            code = "ERR_ADB_DISCONNECTED",
            userMessage = "TV ADB connection dropped ($reason). Tap Connect ADB to resume.",
            technicalDetail = reason,
            category = AdbDiagnosticCategory.TV_DISCONNECTED
        )
        _lastDiagnosticDetail.value = "${AdbDiagnosticCategory.TV_DISCONNECTED.label}: $reason"
    }

    private fun extractCategory(e: Throwable, fallback: AdbDiagnosticCategory): AdbDiagnosticCategory {
        return when (e) {
            is AdbOperationException -> e.category
            is AdbPairingException -> e.category
            is SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> AdbDiagnosticCategory.CONNECTION_TIMEOUT
            is EOFException -> AdbDiagnosticCategory.TV_DISCONNECTED
            else -> fallback
        }
    }

    private fun classifyException(
        e: Throwable,
        host: String,
        port: Int,
        mode: AdbConnectionMode,
        defaultCat: AdbDiagnosticCategory
    ): Pair<AdbDiagnosticCategory, String> {
        if (e is AdbOperationException) return e.category to (e.message ?: e.category.label)
        if (e is AdbPairingException) return e.category to (e.message ?: e.category.label)
        val msg = e.message.orEmpty()
        return when {
            e is SocketTimeoutException || e is kotlinx.coroutines.TimeoutCancellationException ||
                msg.contains("timed out", ignoreCase = true) ->
                AdbDiagnosticCategory.CONNECTION_TIMEOUT to "Timed out reaching $host:$port. Check TV screen for prompts and Wi-Fi reachability."
            msg.contains("ECONNREFUSED", ignoreCase = true) ->
                AdbDiagnosticCategory.TV_UNAVAILABLE to "TV refused $host:$port. Enable USB/Wireless Debugging in TV Developer Options."
            e is EOFException || msg.contains("closed", ignoreCase = true) ->
                (if (mode == AdbConnectionMode.WIRELESS_DEBUGGING_TLS) AdbDiagnosticCategory.AUTH_FAILURE else AdbDiagnosticCategory.TV_DISCONNECTED) to
                    (msg.ifBlank { "TV closed connection cleanly before completing handshake." })
            else -> defaultCat to (msg.ifBlank { "ADB connection failed" })
        }
    }

    private fun setErrorState(
        code: String,
        category: AdbDiagnosticCategory,
        userMessage: String,
        technicalDetail: String
    ) {
        _diagnosticCategory.value = category
        _connectionState.value = AdbConnectionState.Error(
            code = code,
            userMessage = userMessage,
            technicalDetail = technicalDetail,
            category = category
        )
        _lastDiagnosticDetail.value = "${category.label}: $userMessage"
    }

    private fun closeSessionInternal() {
        try {
            activeSession?.close()
        } catch (_: Exception) {
        }
        activeSession = null
    }
}
