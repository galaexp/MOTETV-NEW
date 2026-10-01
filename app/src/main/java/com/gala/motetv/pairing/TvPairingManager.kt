package com.gala.motetv.pairing

import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.protocol.androidtv.AndroidTvTransport
import com.gala.motetv.protocol.androidtv.codec.PairingProtoCodec
import com.gala.motetv.protocol.androidtv.model.EncodingType
import com.gala.motetv.protocol.androidtv.model.PairingConfiguration
import com.gala.motetv.protocol.androidtv.model.PairingMessage
import com.gala.motetv.protocol.androidtv.model.PairingOption
import com.gala.motetv.protocol.androidtv.model.PairingRequest
import com.gala.motetv.protocol.androidtv.model.PairingSecret
import com.gala.motetv.protocol.androidtv.model.RoleType
import com.gala.motetv.protocol.androidtv.model.Status
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
import java.security.MessageDigest
import java.security.cert.X509Certificate

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
            TvLogger.i(TvLogger.TAG_PAIR, "Starting full pairing flow for ${device.name} @ ${device.host}:${device.pairingPort}")
            _pairingState.value = PairingState.Connecting(device.host, device.pairingPort)

            val identity = credentialStore.getOrCreateIdentity()
            val trans = AndroidTvTransport(
                host = device.host,
                port = device.pairingPort,
                identity = identity
            )
            transport = trans

            _pairingState.value = PairingState.TlsHandshaking
            trans.connect()

            val serverCert = trans.serverCertificate
            if (serverCert == null) {
                fail("ERR_NO_SERVER_CERT", "TV did not provide a valid TLS certificate")
                return@withContext
            }
            _pairingState.value = PairingState.TlsConnected(serverCert)

            val writer = trans.frameWriter
            val reader = trans.frameReader
            if (writer == null || reader == null) {
                fail("ERR_NO_STREAMS", "Failed to open TLS framing streams")
                return@withContext
            }

            // Step 1: Send PairingRequest
            _pairingState.value = PairingState.SendingPairingRequest
            val requestMsg = PairingMessage(
                protocolVersion = 2,
                status = Status.OK,
                pairingRequest = PairingRequest(
                    serviceName = "androidtv-remote",
                    clientName = "MoteTV"
                )
            )
            TvLogger.i(TvLogger.TAG_PAIR, "Sending PairingRequest to ${device.host}:${device.pairingPort}")
            writer.writeFrame(PairingProtoCodec.encode(requestMsg))

            // Step 2: Receive PairingRequestAck
            _pairingState.value = PairingState.WaitingPairingRequestAck
            val reqAckFrame = reader.readNextFrame()
            val reqAckMsg = PairingProtoCodec.decode(reqAckFrame)
            if (reqAckMsg.pairingRequestAck?.status != Status.OK && reqAckMsg.status != Status.OK) {
                fail("ERR_PAIR_REJECTED", "TV rejected PairingRequest (${reqAckMsg.status})")
                return@withContext
            }
            TvLogger.i(TvLogger.TAG_PAIR, "PairingRequestAck accepted by TV")

            // Step 3: Send PairingOption
            _pairingState.value = PairingState.SendingOptions
            val optionMsg = PairingMessage(
                protocolVersion = 2,
                status = Status.OK,
                pairingOption = PairingOption(
                    preferredRole = EncodingType.HEXADECIMAL,
                    inputEncodings = listOf(EncodingType.HEXADECIMAL, EncodingType.ALPHANUMERIC),
                    outputEncodings = listOf(EncodingType.HEXADECIMAL)
                )
            )
            writer.writeFrame(PairingProtoCodec.encode(optionMsg))

            // Step 4: Send PairingConfiguration
            _pairingState.value = PairingState.SendingConfiguration
            val configMsg = PairingMessage(
                protocolVersion = 2,
                status = Status.OK,
                pairingConfiguration = PairingConfiguration(
                    encoding = EncodingType.HEXADECIMAL,
                    clientRole = RoleType.INPUT
                )
            )
            writer.writeFrame(PairingProtoCodec.encode(configMsg))

            // Step 5: Receive PairingConfigurationAck
            _pairingState.value = PairingState.WaitingConfigurationAck
            val cfgAckFrame = reader.readNextFrame()
            val cfgAckMsg = PairingProtoCodec.decode(cfgAckFrame)
            if (cfgAckMsg.pairingConfigurationAck?.status != Status.OK && cfgAckMsg.status != Status.OK) {
                fail("ERR_CONFIG_REJECTED", "TV rejected pairing configuration")
                return@withContext
            }
            TvLogger.i(TvLogger.TAG_PAIR, "Configuration acknowledged. TV is displaying PIN.")

            // Step 6: Wait for user to input PIN from TV screen
            val prompt = "Enter the 6-character code shown on your ${device.name}"
            _pairingState.value = PairingState.WaitingForUserPin(device.name, prompt)

            val pin = pinDeferred?.await()
            if (pin.isNullOrBlank()) {
                fail("ERR_EMPTY_PIN", "No PIN provided")
                return@withContext
            }

            // Step 7: Compute secret hash
            _pairingState.value = PairingState.SendingSecret
            val secretBytes = computeSecretHash(identity.certificate, serverCert, pin)
            val secretMsg = PairingMessage(
                protocolVersion = 2,
                status = Status.OK,
                pairingSecret = PairingSecret(secretBytes)
            )
            writer.writeFrame(PairingProtoCodec.encode(secretMsg))
            TvLogger.i(TvLogger.TAG_PAIR, "Sent secret challenge hash to TV")

            // Step 8: Receive PairingSecretAck
            _pairingState.value = PairingState.WaitingSecretAck
            val secretAckFrame = reader.readNextFrame()
            val secretAckMsg = PairingProtoCodec.decode(secretAckFrame)

            val secretAck = secretAckMsg.pairingSecretAck
            if (secretAck == null || secretAck.status != Status.OK) {
                fail("ERR_BAD_SECRET", "TV rejected PIN code (Incorrect PIN entered)")
                return@withContext
            }

            TvLogger.i(TvLogger.TAG_PAIR, "Pairing successfully verified with TV!")

            // Step 9: Save paired status in credential store
            credentialStore.setDevicePaired(device.id, true)
            val pairedDevice = device.copy(isPaired = true)

            cleanupTransport()
            _pairingState.value = PairingState.Paired(pairedDevice)

        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_PAIR, "Pairing flow exception: ${e.message}", e)
            fail("ERR_PAIR_EXCEPTION", "Pairing error: ${e.message}", e.stackTraceToString())
        }
    }

    private fun computeSecretHash(
        clientCert: X509Certificate,
        serverCert: X509Certificate,
        pin: String
    ): ByteArray {
        val clientCertBytes = clientCert.encoded
        val serverCertBytes = serverCert.encoded

        // If 6-hex code (e.g. "A1B2C3"), convert to byte array or UTF-8 if alpha
        val pinBytes = try {
            if (pin.length % 2 == 0 && pin.all { it in "0123456789ABCDEFabcdef" }) {
                pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                pin.toByteArray(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            pin.toByteArray(Charsets.UTF_8)
        }

        val md = MessageDigest.getInstance("SHA-256")
        md.update(clientCertBytes)
        md.update(serverCertBytes)
        md.update(pinBytes)
        return md.digest()
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
