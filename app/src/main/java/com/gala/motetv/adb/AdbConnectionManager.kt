package com.gala.motetv.adb

import kotlinx.coroutines.flow.StateFlow

/**
 * Dedicated ADB connection manager kept completely isolated from Android TV Remote v2 sockets.
 * Exposes only typed operations (device discovery, package scanning, allowlisted debloat/restore,
 * and CleanYourTV launcher/theme sync) — never exposes an unauthenticated raw shell to UI users.
 */
interface AdbConnectionManager {
    val connectionState: StateFlow<AdbConnectionState>
    val pairingState: StateFlow<AdbPairingState>
    val diagnosticCategory: StateFlow<AdbDiagnosticCategory>
    val discoveredServices: StateFlow<List<DiscoveredAdbService>>
    val isDiscovering: StateFlow<Boolean>
    val savedEndpoint: StateFlow<AdbEndpointConfig?>
    val lastDiagnosticDetail: StateFlow<String>
    val pairingCapability: AdbPairingCapability

    fun startWirelessDiscovery()
    fun stopWirelessDiscovery()

    fun connect(host: String, port: Int, mode: AdbConnectionMode)
    fun disconnect()

    suspend fun pairWirelessDebugging(host: String, pairingPort: Int, pairingCode: String): Result<String>

    suspend fun inspectTvDevice(): Result<TvDeviceInfo>
    suspend fun listAllPackages(): Result<List<String>>
    suspend fun listDisabledPackages(): Result<Set<String>>

    suspend fun disablePackageForUser(packageName: String): Result<AdbCommandResult>
    suspend fun enablePackage(packageName: String): Result<AdbCommandResult>
    suspend fun launchHomeChooserOrOpenHome(): Result<AdbCommandResult>
    suspend fun setDefaultHomeActivity(componentName: String): Result<AdbCommandResult>
    suspend fun restartCleanYourTvLauncher(): Result<AdbCommandResult>
    suspend fun installApk(apkBytes: ByteArray): Result<AdbCommandResult>
    suspend fun pushAppConfigFile(remotePath: String, content: ByteArray): Result<AdbCommandResult>
    suspend fun readRemoteFile(remotePath: String): Result<ByteArray>
    suspend fun remoteFileExists(remotePath: String): Result<Boolean>
    suspend fun ensureRemoteDirectory(remoteDir: String): Result<AdbCommandResult>
    suspend fun removeRemoteFile(remotePath: String): Result<AdbCommandResult>
    suspend fun checkFilePushSupported(): Result<Boolean>
}
