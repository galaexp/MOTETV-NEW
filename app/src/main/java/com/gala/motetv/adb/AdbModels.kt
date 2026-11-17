package com.gala.motetv.adb

enum class AdbConnectionMode(val displayName: String, val defaultPort: Int) {
    NETWORK_LEGACY_5555("Network ADB (TCP 5555)", 5555),
    WIRELESS_DEBUGGING_TLS("Wireless Debugging (TLS)", 0)
}

enum class AdbDiagnosticCategory(val label: String) {
    NONE("Ready"),
    PAIRING_FAILURE("Pairing Failure"),
    AUTH_FAILURE("TV Authorization Failure"),
    CONNECTION_TIMEOUT("Connection Timeout"),
    TV_UNAVAILABLE("TV Unavailable"),
    SHELL_FAILURE("Shell Command Failure"),
    FILE_TRANSFER_FAILURE("File Transfer Failure"),
    TV_DISCONNECTED("TV Disconnected")
}

sealed class AdbPairingState {
    data object Idle : AdbPairingState()
    data class Pairing(val host: String, val pairingPort: Int) : AdbPairingState()
    data class Paired(val host: String, val detail: String) : AdbPairingState()
    data class Failed(
        val category: AdbDiagnosticCategory,
        val message: String
    ) : AdbPairingState()
}

sealed class AdbConnectionState {
    data object Disconnected : AdbConnectionState()
    data class Connecting(
        val host: String,
        val port: Int,
        val mode: AdbConnectionMode
    ) : AdbConnectionState()

    data class AwaitingTvAuthorization(
        val host: String,
        val port: Int,
        val keyFingerprint: String
    ) : AdbConnectionState()

    data class Connected(
        val host: String,
        val port: Int,
        val mode: AdbConnectionMode,
        val banner: String,
        val deviceInfo: TvDeviceInfo? = null
    ) : AdbConnectionState()

    data class Error(
        val code: String,
        val userMessage: String,
        val technicalDetail: String,
        val category: AdbDiagnosticCategory = AdbDiagnosticCategory.TV_UNAVAILABLE
    ) : AdbConnectionState()
}

data class AdbEndpointConfig(
    val host: String,
    val port: Int = 5555,
    val mode: AdbConnectionMode = AdbConnectionMode.NETWORK_LEGACY_5555,
    val lastConnectedMs: Long = 0L
)

data class DiscoveredAdbService(
    val serviceName: String,
    val host: String,
    val port: Int,
    val serviceType: String,
    val isPairingService: Boolean
)

data class AdbPairingCapability(
    val isNativeSpake2Supported: Boolean,
    val explanation: String
)

data class AdbCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = ""
) {
    val isSuccess: Boolean get() = exitCode == 0
}

data class TvDeviceInfo(
    val manufacturer: String,
    val model: String,
    val deviceName: String,
    val productBoard: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val buildId: String,
    val buildFingerprint: String,
    val currentHomePackage: String?,
    val currentHomeActivity: String?,
    val currentHomeLabel: String?
) {
    val isXiaomiMiTvStickOriginal: Boolean
        get() {
            val combined = "$manufacturer $model $deviceName $productBoard $buildFingerprint".lowercase()
            return combined.contains("aquaman") ||
                combined.contains("mitv-aesp0") ||
                combined.contains("mitv stick") ||
                combined.contains("mi tv stick") ||
                (combined.contains("mibox") && combined.contains("stick"))
        }

    val isXiaomiDevice: Boolean
        get() = manufacturer.contains("xiaomi", ignoreCase = true) ||
            model.contains("mitv", ignoreCase = true) ||
            model.contains("mi tv", ignoreCase = true) ||
            model.contains("mi box", ignoreCase = true) ||
            buildFingerprint.contains("xiaomi", ignoreCase = true)
}
