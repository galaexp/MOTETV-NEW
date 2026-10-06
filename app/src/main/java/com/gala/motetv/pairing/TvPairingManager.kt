package com.gala.motetv.pairing

import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.pairing.crypto.PoloCrypto
import com.gala.motetv.protocol.androidtv.AndroidTvTransport
import com.gala.motetv.storage.TvCredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import polo.wire.protobuf.Configuration
import polo.wire.protobuf.Encoding
import polo.wire.protobuf.EncodingType
import polo.wire.protobuf.MessageType
import polo.wire.protobuf.Options
import polo.wire.protobuf.OuterMessage
import polo.wire.protobuf.PairingRequest
import polo.wire.protobuf.RoleType
import polo.wire.protobuf.Secret
import polo.wire.protobuf.Status
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

interface TvPairingManager {
    val pairingState: StateFlow<PairingState>
    fun startPairing(device: TvDevice)
    fun submitPin(pin: String)
    fun cancelPairing()
}

class TvPairingManagerImpl(
    private val credentialStore: TvCredentialStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : TvPairingManager {

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    override val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var pairingJob: Job? = null
    private var transport: AndroidTvTransport? = null
    private var pinDeferred: CompletableDeferred<String>? = null
    private var currentDevice: TvDevice? = null

    override fun startPairing(device: TvDevice) {
        pairingJob?.cancel()
        currentDevice = device
        pinDeferred = CompletableDeferred()
        pairingJob = scope.launch {
            executePairingFlow(device)
        }
    }

    override fun submitPin(pin: String) {
        val trimmed = pin.trim().uppercase()
        TvLogger.i(TvLogger.TAG_PAIR, "User submitted PIN (${trimmed.length} characters)")
        pinDeferred?.complete(trimmed)
    }

    override fun cancelPairing() {
        pairingJob?.cancel()
        pinDeferred?.cancel()
        cleanupTransport()
        _pairingState.value = PairingState.Cancelled
        TvLogger.i(TvLogger.TAG_PAIR, "Pairing cancelled by user")
    }

    private suspend fun executePairingFlow(device: TvDevice) = withContext(Dispatchers.IO) {
        try {
            TvLogger.i(TvLogger.TAG_PAIR, "Starting pairing flow for ${device.name} @ ${device.host}:${device.pairingPort}")
            val identity = credentialStore.getOrCreateIdentity(device.id)

            var activeServerCert: X509Certificate? = null

            // Helper to establish TLS and exchange PairingRequest -> Options -> Configuration
            suspend fun connectAndHandshake(): Boolean {
                cleanupTransport()
                _pairingState.value = PairingState.Connecting(device.host, device.pairingPort)

                val trans = AndroidTvTransport(
                    host = device.host,
                    port = device.pairingPort,
                    identity = identity
                )
                transport = trans

                TvLogger.i(TvLogger.TAG_TLS, "TLS handshake started")
                _pairingState.value = PairingState.TlsHandshaking
                trans.connect()

                val serverCert = trans.serverCertificate
                if (serverCert == null) {
                    fail("ERR_NO_SERVER_CERT", "TV did not provide a valid TLS certificate")
                    return false
                }
                activeServerCert = serverCert

                val serverFp = credentialStore.computeFingerprint(serverCert)
                TvLogger.i(TvLogger.TAG_TLS, "TLS handshake completed. Peer fingerprint: $serverFp")
                _pairingState.value = PairingState.TlsConnected(serverCert)

                val writer = trans.frameWriter
                val reader = trans.frameReader
                if (writer == null || reader == null) {
                    fail("ERR_NO_STREAMS", "Failed to open TLS framing streams")
                    return false
                }

                fun sendOuterMessage(tag: String, msg: OuterMessage) {
                    val bytes = msg.encode()
                    val hexPreview = bytes.take(16).joinToString(" ") { "%02X".format(it) }
                    TvLogger.i(
                        TvLogger.TAG_PAIR,
                        "[PAIRING_WRITER] FRAME TX ($tag): length=${bytes.size} firstBytes=[$hexPreview]"
                    )
                    TvLogger.i(
                        TvLogger.TAG_PAIR,
                        "OUTER TX: protocolVersion=${msg.protocol_version ?: 2}, status=${msg.status ?: Status.STATUS_OK} (${msg.status?.value ?: 200}), type=${msg.type} (${msg.type?.value}), payloadLength=${bytes.size}"
                    )
                    writer.writeFrame(bytes)
                }

                fun readOuterMessage(tag: String): Pair<OuterMessage, ByteArray> {
                    TvLogger.d(TvLogger.TAG_PAIR, "[PAIRING_READER] waiting for frame ($tag)...")
                    val frame = reader.readNextFrame()
                    val hexPreview = frame.take(16).joinToString(" ") { "%02X".format(it) }
                    TvLogger.i(
                        TvLogger.TAG_PAIR,
                        "[PAIRING_READER] FRAME RX ($tag): length=${frame.size} firstBytes=[$hexPreview]"
                    )
                    val msg = OuterMessage.ADAPTER.decode(frame)
                    TvLogger.i(
                        TvLogger.TAG_PAIR,
                        "PAIR RX: type=${msg.type ?: "UNKNOWN"}, status=${msg.status ?: Status.STATUS_OK} (${msg.status?.value ?: 200}), protocolVersion=${msg.protocol_version ?: 2}, payloadLength=${frame.size}"
                    )
                    return Pair(msg, frame)
                }

                // STEP 1: Send PairingRequest
                _pairingState.value = PairingState.SendingPairingRequest
                val requestPayload = PairingRequest(
                    service_name = "androidtvremote",
                    client_name = "MoteTV"
                )
                val requestMsg = OuterMessage(
                    protocol_version = 2,
                    status = Status.STATUS_OK,
                    type = MessageType.MESSAGE_TYPE_PAIRING_REQUEST,
                    pairing_request = requestPayload
                )
                sendOuterMessage("PairingRequest", requestMsg)

                // STEP 2: Receive PairingRequestAck
                _pairingState.value = PairingState.WaitingPairingRequestAck
                val (reqAckMsg, _) = readOuterMessage("PairingRequestAck")

                if (reqAckMsg.status == Status.STATUS_ERROR || reqAckMsg.status == Status.STATUS_BAD_CONFIGURATION) {
                    fail("ERR_PAIR_REJECTED", "TV rejected PairingRequest (${reqAckMsg.status})")
                    return false
                }

                // STEP 3: Send Options
                _pairingState.value = PairingState.SendingOptions
                val optionPayload = Options(
                    input_encodings = listOf(Encoding(type = EncodingType.ENCODING_TYPE_HEXADECIMAL, symbol_length = 6)),
                    output_encodings = listOf(Encoding(type = EncodingType.ENCODING_TYPE_HEXADECIMAL, symbol_length = 6)),
                    preferred_role = RoleType.ROLE_TYPE_INPUT
                )
                val optionMsg = OuterMessage(
                    protocol_version = 2,
                    status = Status.STATUS_OK,
                    type = MessageType.MESSAGE_TYPE_OPTIONS,
                    options = optionPayload
                )
                sendOuterMessage("Options", optionMsg)

                // STEP 4: RECEIVE Options from TV
                _pairingState.value = PairingState.WaitingOptionsResponse
                TvLogger.i(TvLogger.TAG_PAIR, "Waiting for TV Options response...")
                val (tvOptionsMsg, _) = readOuterMessage("Options")

                val tvOpts = tvOptionsMsg.options
                TvLogger.i(
                    TvLogger.TAG_PAIR,
                    "TV OPTIONS received:\n" +
                    "  inputEncodings: ${tvOpts?.input_encodings?.map { "${it.type} (symbolLength=${it.symbol_length})" }}\n" +
                    "  outputEncodings: ${tvOpts?.output_encodings?.map { "${it.type} (symbolLength=${it.symbol_length})" }}\n" +
                    "  preferredRole: ${tvOpts?.preferred_role}"
                )

                if (tvOptionsMsg.status == Status.STATUS_ERROR || tvOptionsMsg.status == Status.STATUS_BAD_CONFIGURATION) {
                    fail("ERR_INVALID_OPTIONS", "TV rejected options exchange (${tvOptionsMsg.status})")
                    return false
                }

                // STEP 5: Send Configuration
                _pairingState.value = PairingState.SendingConfiguration
                val configPayload = Configuration(
                    encoding = Encoding(
                        type = EncodingType.ENCODING_TYPE_HEXADECIMAL,
                        symbol_length = 6
                    ),
                    client_role = RoleType.ROLE_TYPE_INPUT
                )
                val configBytes = configPayload.encode()
                val configHex = configBytes.joinToString(" ") { "%02X".format(it) }

                TvLogger.i(
                    TvLogger.TAG_PAIR,
                    "CONFIGURATION TX:\n" +
                    "  encoding.type = ${configPayload.encoding?.type?.name} (${configPayload.encoding?.type?.value})\n" +
                    "  encoding.symbol_length = ${configPayload.encoding?.symbol_length}\n" +
                    "  client_role = ${configPayload.client_role?.name} (${configPayload.client_role?.value})\n" +
                    "  payloadLength = ${configBytes.size}\n" +
                    "  payloadHEX = $configHex"
                )

                val configOuterMsg = OuterMessage(
                    protocol_version = 2,
                    status = Status.STATUS_OK,
                    type = MessageType.MESSAGE_TYPE_CONFIGURATION,
                    configuration = configPayload
                )
                sendOuterMessage("Configuration", configOuterMsg)

                // STEP 6: Receive ConfigurationAck
                _pairingState.value = PairingState.WaitingConfigurationAck
                val (cfgAckMsg, _) = readOuterMessage("ConfigurationAck")

                if (cfgAckMsg.status == Status.STATUS_ERROR ||
                    cfgAckMsg.status == Status.STATUS_BAD_CONFIGURATION ||
                    cfgAckMsg.status == Status.STATUS_BAD_SECRET
                ) {
                    fail("ERR_CONFIG_REJECTED", "TV rejected pairing configuration (${cfgAckMsg.status})")
                    return false
                }

                TvLogger.i(TvLogger.TAG_PAIR, "Configuration accepted")
                return true
            }

            // Perform initial connection and pre-PIN handshake
            if (!connectAndHandshake()) {
                return@withContext
            }

            TvLogger.i(TvLogger.TAG_PAIR, "Waiting for pairing PIN")

            // STEP 7: PIN state entered -> Physical TV displays PIN!
            val prompt = "Enter the 6-character code shown on your ${device.name}"
            var pinAccepted = false
            var currentErrorMessage: String? = null

            while (!pinAccepted) {
                _pairingState.value = PairingState.WaitingForUserPin(
                    tvName = device.name,
                    prompt = prompt,
                    errorMessage = currentErrorMessage,
                    isSubmitting = false
                )

                val pin = pinDeferred?.await()
                if (pin.isNullOrBlank() || pin.length != 6) {
                    fail("ERR_EMPTY_PIN", "No valid 6-character PIN entered")
                    return@withContext
                }

                // Show submitting state in dialog
                _pairingState.value = PairingState.WaitingForUserPin(
                    tvName = device.name,
                    prompt = prompt,
                    errorMessage = null,
                    isSubmitting = true
                )

                // If transport was closed (e.g., due to TV disconnecting on previous STATUS_BAD_SECRET), reconnect
                val currentTrans = transport
                if (currentTrans == null || currentTrans.frameWriter == null) {
                    TvLogger.i(TvLogger.TAG_PAIR, "Re-establishing pairing connection for PIN submission retry...")
                    if (!connectAndHandshake()) {
                        currentErrorMessage = "Connection lost. Please try entering the PIN again."
                        pinDeferred = CompletableDeferred()
                        continue
                    }
                }

                val liveTrans = transport
                val writer = liveTrans?.frameWriter
                val reader = liveTrans?.frameReader
                val serverCert = activeServerCert

                if (liveTrans == null || writer == null || reader == null || serverCert == null) {
                    fail("ERR_NO_STREAMS", "Pairing connection lost during PIN submission")
                    return@withContext
                }

                fun sendOuterMessage(tag: String, msg: OuterMessage) {
                    val bytes = msg.encode()
                    val hexPreview = bytes.take(16).joinToString(" ") { "%02X".format(it) }
                    TvLogger.i(
                        TvLogger.TAG_PAIR,
                        "[PAIRING_WRITER] FRAME TX ($tag): length=${bytes.size} firstBytes=[$hexPreview]"
                    )
                    TvLogger.i(
                        TvLogger.TAG_PAIR,
                        "OUTER TX: protocolVersion=${msg.protocol_version ?: 2}, status=${msg.status ?: Status.STATUS_OK} (${msg.status?.value ?: 200}), type=${msg.type} (${msg.type?.value}), payloadLength=${bytes.size}"
                    )
                    writer.writeFrame(bytes)
                }

                fun readOuterMessage(tag: String): Pair<OuterMessage, ByteArray> {
                    TvLogger.d(TvLogger.TAG_PAIR, "[PAIRING_READER] waiting for frame ($tag)...")
                    val frame = reader.readNextFrame()
                    val hexPreview = frame.take(16).joinToString(" ") { "%02X".format(it) }
                    TvLogger.i(
                        TvLogger.TAG_PAIR,
                        "[PAIRING_READER] FRAME RX ($tag): length=${frame.size} firstBytes=[$hexPreview]"
                    )
                    val msg = OuterMessage.ADAPTER.decode(frame)
                    TvLogger.i(
                        TvLogger.TAG_PAIR,
                        "PAIR RX: type=${msg.type ?: "UNKNOWN"}, status=${msg.status ?: Status.STATUS_OK} (${msg.status?.value ?: 200}), protocolVersion=${msg.protocol_version ?: 2}, payloadLength=${frame.size}"
                    )
                    return Pair(msg, frame)
                }

                // STEP 8: Calculate RSA-based SHA-256 Secret Challenge
                val clientPubKey = identity.certificate.publicKey as RSAPublicKey
                val serverPubKey = serverCert.publicKey as RSAPublicKey

                val hashInput = PoloCrypto.buildSecretHashInput(clientPubKey, serverPubKey, pin)
                val secretBytes = PoloCrypto.computeClientSecret(clientPubKey, serverPubKey, pin)

                val expectedFirstByte = PoloCrypto.hexStringToByteArray(pin.substring(0, 2))[0]
                val actualFirstByte = secretBytes[0]
                val checksumValid = (expectedFirstByte == actualFirstByte)

                val clientKeyFp = PoloCrypto.computePublicKeyFingerprint(clientPubKey)
                val serverKeyFp = PoloCrypto.computePublicKeyFingerprint(serverPubKey)
                val serverCertFp = credentialStore.computeFingerprint(serverCert)

                val pinAsciiBytesHex = pin.toByteArray(Charsets.US_ASCII).joinToString(" ") { "%02X".format(it) }
                val hashInputHex = hashInput.joinToString(" ") { "%02X".format(it) }
                val secretHex = secretBytes.joinToString(" ") { "%02X".format(it) }

                val secretPayload = Secret(secret = secretBytes.toByteString())
                val secretPayloadBytes = secretPayload.encode()
                val secretPayloadHex = secretPayloadBytes.joinToString(" ") { "%02X".format(it) }

                val secretMsg = OuterMessage(
                    protocol_version = 2,
                    status = Status.STATUS_OK,
                    type = MessageType.MESSAGE_TYPE_SECRET,
                    secret = secretPayload
                )
                val outerBytes = secretMsg.encode()
                val outerHex = outerBytes.joinToString(" ") { "%02X".format(it) }

                TvLogger.i(
                    TvLogger.TAG_PAIR,
                    "SECRET DIAGNOSTICS:\n" +
                    "  PIN length: ${pin.length}\n" +
                    "  PIN ASCII bytes: [$pinAsciiBytesHex]\n" +
                    "  PIN encoding method: HEXADECIMAL (substring(2) -> 2 bytes)\n" +
                    "  Expected 1st byte (from PIN[0..1]): ${"%02X".format(expectedFirstByte)}\n" +
                    "  Actual 1st byte (from SHA-256[0]): ${"%02X".format(actualFirstByte)}\n" +
                    "  PIN Checksum Match: $checksumValid\n" +
                    "  client certificate public-key fingerprint: $clientKeyFp\n" +
                    "  TV certificate public-key fingerprint: $serverKeyFp\n" +
                    "  TV certificate fingerprint: $serverCertFp\n" +
                    "  exact hash input length: ${hashInput.size}\n" +
                    "  exact hash input HEX: [$hashInputHex]\n" +
                    "  SHA-256 result HEX: [$secretHex]\n" +
                    "  final Secret bytes HEX: [$secretHex]\n" +
                    "  Secret protobuf payload HEX: [$secretPayloadHex]\n" +
                    "  OuterMessage HEX: [$outerHex]"
                )

                if (!checksumValid) {
                    TvLogger.w(
                        TvLogger.TAG_PAIR,
                        "PIN Checksum mismatch! First byte of SHA-256 (${"%02X".format(actualFirstByte)}) != PIN prefix (${"%02X".format(expectedFirstByte)}). PIN entered is invalid."
                    )
                    currentErrorMessage = "Incorrect PIN. Please check the 6-character code on your TV and try again."
                    pinDeferred = CompletableDeferred()
                    cleanupTransport()
                    continue
                }

                TvLogger.i(TvLogger.TAG_PAIR, "PIN UI: Secret submission started")
                sendOuterMessage("Secret", secretMsg)

                // STEP 9: Receive SecretAck
                val (secretAckMsg, _) = readOuterMessage("SecretAck")

                if (secretAckMsg.status == Status.STATUS_BAD_SECRET ||
                    (secretAckMsg.status != Status.STATUS_OK && secretAckMsg.secret_ack == null)
                ) {
                    TvLogger.e(TvLogger.TAG_PAIR, "PIN UI: SecretAck received, status = STATUS_BAD_SECRET (402)")
                    TvLogger.w(TvLogger.TAG_PAIR, "TV rejected PIN code (STATUS_BAD_SECRET). Keeping PIN dialog open for retry.")
                    currentErrorMessage = "Incorrect PIN. Please enter the 6-character code shown on your TV."
                    pinDeferred = CompletableDeferred()
                    cleanupTransport()
                } else {
                    TvLogger.i(TvLogger.TAG_PAIR, "PIN UI: SecretAck received, status = STATUS_OK (200)")
                    TvLogger.i(TvLogger.TAG_PAIR, "Pairing successfully verified with TV!")
                    pinAccepted = true

                    // Save paired status & server certificate fingerprint for this specific TV
                    credentialStore.setDevicePaired(device.id, serverCert, true)
                    val pairedDevice = device.copy(isPaired = true)

                    cleanupTransport()
                    _pairingState.value = PairingState.Paired(pairedDevice)
                }
            }

        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_PAIR, "Pairing flow exception: ${e.message}", e)
            fail("ERR_PAIR_EXCEPTION", "Pairing error: ${e.message}", e.stackTraceToString())
        }
    }

    private fun fail(errorCode: String, userMessage: String, technicalDetails: String? = null) {
        TvLogger.e(TvLogger.TAG_PAIR, "Pairing Failure [$errorCode]: $userMessage")
        cleanupTransport()
        _pairingState.value = PairingState.Failed(errorCode, userMessage, technicalDetails)
    }

    private fun cleanupTransport() {
        try {
            transport?.close()
        } catch (_: Exception) {}
        transport = null
    }
}
