package com.gala.motetv.adb

import android.net.ssl.SSLSockets
import android.os.Build
import com.gala.motetv.pairing.TvCertificateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

interface TlsPairingChannel : Closeable {
    val input: InputStream
    val output: OutputStream
    val exportedKeyingMaterial: ByteArray
}

class AdbPairingException(
    val category: AdbDiagnosticCategory,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Native Android 11+ (API 30+) implementation of AOSP Wireless Debugging pairing (`adb pair`):
 * 1. Connects TLS 1.3 to `<host>:<pairingPort>` using the device-persistent MoteTV client cert.
 * 2. Exports 64 bytes of TLS keying material via `SSLSockets.exportKeyingMaterial("adb-label")`.
 * 3. Runs BoringSSL-compatible Ed25519 SPAKE2 (`adb pair client` / `adb pair server`) over
 *    `(pairingCode || exportedKeyingMaterial)`.
 * 4. Derives an AES-128-GCM key via HKDF-SHA256 (`"adb pairing_auth aes-128-gcm key"`).
 * 5. Exchanges encrypted 8192-byte `PeerInfo` packets containing MoteTV's ADB RSA public key.
 *
 * Registers coroutine cancellation hooks against active sockets/channels so cancelling pairing
 * closes the socket immediately and aborts any blocking socket connect/read.
 */
class NativeWirelessAdbPairing(
    private val credentialStore: AdbCredentialStore,
    val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val readTimeoutMs: Int = connectTimeoutMs,
    private val channelFactory: ((String, Int, KeyPair) -> TlsPairingChannel)? = null
) : AdbPairingTransport {

    companion object {
        const val DEFAULT_TIMEOUT_MS: Int = 10_000
        const val PROTOCOL_VERSION: Byte = 1
        const val PACKET_HEADER_SIZE = 6
        const val PACKET_TYPE_SPAKE2_MSG: Byte = 0
        const val PACKET_TYPE_PEER_INFO: Byte = 1
        const val PEER_INFO_TYPE_ADB_RSA_PUB_KEY: Byte = 0
        const val PEER_INFO_SIZE = 8192
        const val MAX_ENCRYPTED_PAYLOAD = 16_384

        internal val CLIENT_NAME = "adb pair client".toByteArray(Charsets.US_ASCII)
        internal val SERVER_NAME = "adb pair server".toByteArray(Charsets.US_ASCII)
        internal val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
        private const val EKM_LABEL = "adb-label"
        private const val EKM_LENGTH = 64

        fun isNativeSupportedOnDevice(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
            return sdkInt >= Build.VERSION_CODES.R
        }

        fun openAndroidTlsPairingChannel(
            host: String,
            port: Int,
            keyPair: KeyPair,
            connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
            readTimeoutMs: Int = connectTimeoutMs,
            onSocketCreated: ((Closeable) -> Unit)? = null
        ): TlsPairingChannel {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    "In-app Wireless Debugging TLS pairing requires Android 11+ (API 30). Current device is API ${Build.VERSION.SDK_INT}; use Traditional Network ADB (5555) or pair once via PC 'adb pair'."
                )
            }

            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val cert = TvCertificateManager.createSelfSignedX509Certificate(keyPair, "MoteTV-ADB-Pair")
            val keyManager = object : X509KeyManager {
                override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?) = arrayOf("adb")
                override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<java.security.Principal>?, socket: Socket?) = "adb"
                override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String>? = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<java.security.Principal>?, socket: Socket?): String? = null
                override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
                override fun getPrivateKey(alias: String?): java.security.PrivateKey = keyPair.private
            }

            val effectiveConnectTimeout = connectTimeoutMs.coerceAtLeast(1)
            val effectiveReadTimeout = readTimeoutMs.coerceAtLeast(1)

            val rawSocket = Socket()
            onSocketCreated?.invoke(rawSocket)
            var sslSocket: SSLSocket? = null
            try {
                rawSocket.soTimeout = effectiveReadTimeout
                rawSocket.tcpNoDelay = true
                rawSocket.connect(InetSocketAddress(host, port), effectiveConnectTimeout)

                val sslCtx = SSLContext.getInstance("TLSv1.3")
                sslCtx.init(arrayOf(keyManager), trustAll, SecureRandom())
                val wrapped = sslCtx.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
                sslSocket = wrapped
                onSocketCreated?.invoke(wrapped)
                wrapped.useClientMode = true
                wrapped.enabledProtocols = arrayOf("TLSv1.3")
                wrapped.soTimeout = effectiveReadTimeout
                wrapped.startHandshake()

                if (!SSLSockets.isSupportedSocket(wrapped)) {
                    wrapped.close()
                    throw AdbPairingException(
                        AdbDiagnosticCategory.PAIRING_FAILURE,
                        "Device TLS stack does not support SSLSockets keying material export."
                    )
                }

                val ekm = SSLSockets.exportKeyingMaterial(wrapped, EKM_LABEL, null, EKM_LENGTH)
                    ?: throw AdbPairingException(
                        AdbDiagnosticCategory.PAIRING_FAILURE,
                        "TLS 1.3 exportKeyingMaterial('adb-label') returned null."
                    )

                return object : TlsPairingChannel {
                    override val input: InputStream = wrapped.inputStream
                    override val output: OutputStream = wrapped.outputStream
                    override val exportedKeyingMaterial: ByteArray = ekm
                    override fun close() {
                        try {
                            wrapped.close()
                        } catch (_: Exception) {
                        }
                        try {
                            rawSocket.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                runCatching { sslSocket?.close() }
                runCatching { rawSocket.close() }
                throw AdbPairingException(
                    AdbDiagnosticCategory.CONNECTION_TIMEOUT,
                    "Timed out during TLS pairing connect/handshake to $host:$port (${effectiveConnectTimeout}ms/${effectiveReadTimeout}ms): ${e.message}",
                    e
                )
            } catch (e: Exception) {
                runCatching { sslSocket?.close() }
                runCatching { rawSocket.close() }
                if (e is AdbPairingException) throw e
                throw AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    "TLS pairing handshake failed: ${e.message}",
                    e
                )
            }
        }

        internal fun encodePacket(type: Byte, payload: ByteArray): ByteArray {
            val buf = ByteBuffer.allocate(PACKET_HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
            buf.put(PROTOCOL_VERSION)
            buf.put(type)
            buf.putInt(payload.size)
            buf.put(payload)
            return buf.array()
        }

        internal data class PairingPacket(
            val version: Byte,
            val type: Byte,
            val payload: ByteArray
        )

        @Throws(IOException::class)
        internal fun readPacket(input: DataInputStream, expectedType: Byte): PairingPacket {
            val headerBytes = ByteArray(PACKET_HEADER_SIZE)
            try {
                input.readFully(headerBytes)
            } catch (e: EOFException) {
                throw AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    "TV closed pairing connection before sending response (verify pairing code and port).",
                    e
                )
            }
            val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
            val version = header.get()
            val type = header.get()
            val size = header.getInt()
            if (version != PROTOCOL_VERSION) {
                throw AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    "Unsupported pairing packet version=$version (expected $PROTOCOL_VERSION)"
                )
            }
            if (type != expectedType) {
                throw AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    "Unexpected pairing packet type=$type (expected $expectedType)"
                )
            }
            if (size <= 0 || size > MAX_ENCRYPTED_PAYLOAD) {
                throw AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    "Pairing packet payload size out of bounds: $size"
                )
            }
            val payload = ByteArray(size)
            try {
                input.readFully(payload)
            } catch (e: EOFException) {
                throw AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    "TV closed pairing connection while transmitting payload.",
                    e
                )
            }
            return PairingPacket(version, type, payload)
        }

        internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int = 16): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            // Extract with empty salt -> salt defaults to HashLen zero bytes
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            // Expand T(1) for length <= 32
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info)
            mac.update(1.toByte())
            return mac.doFinal().copyOfRange(0, length)
        }

        internal fun buildPeerInfoRsaKey(rsaPublicKeyPayload: ByteArray): ByteArray {
            val block = ByteArray(PEER_INFO_SIZE)
            block[0] = PEER_INFO_TYPE_ADB_RSA_PUB_KEY
            val copyLen = minOf(rsaPublicKeyPayload.size, PEER_INFO_SIZE - 1)
            System.arraycopy(rsaPublicKeyPayload, 0, block, 1, copyLen)
            return block
        }
    }

    private val currentActiveChannelRef = AtomicReference<Closeable?>(null)

    fun cancelCurrentPairing() {
        currentActiveChannelRef.getAndSet(null)?.let { closeable ->
            runCatching { closeable.close() }
        }
    }

    override suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        clientKeyPair: KeyPair
    ): Result<String> {
        val ctx = currentCoroutineContext()
        ctx.ensureActive()

        val activeChannelRef = AtomicReference<TlsPairingChannel?>(null)
        val activeSocketRef = AtomicReference<Closeable?>(null)

        fun closeActiveResources() {
            currentActiveChannelRef.set(null)
            activeChannelRef.getAndSet(null)?.let { ch ->
                try {
                    ch.close()
                } catch (_: Exception) {
                }
            }
            activeSocketRef.getAndSet(null)?.let { sock ->
                try {
                    sock.close()
                } catch (_: Exception) {
                }
            }
        }

        @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
        val completionHandle = ctx[Job]?.invokeOnCompletion(onCancelling = true) { cause ->
            if (cause != null) {
                closeActiveResources()
            }
        }

        return try {
            val channel = if (channelFactory != null) {
                val ch = channelFactory.invoke(host, port, clientKeyPair)
                currentActiveChannelRef.set(ch)
                activeSocketRef.set(ch)
                activeChannelRef.set(ch)
                if (!ctx.isActive) {
                    runCatching { ch.close() }
                }
                ch
            } else {
                openAndroidTlsPairingChannel(
                    host = host,
                    port = port,
                    keyPair = clientKeyPair,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    onSocketCreated = { closeable ->
                        currentActiveChannelRef.set(closeable)
                        activeSocketRef.set(closeable)
                        if (!ctx.isActive) {
                            runCatching { closeable.close() }
                        }
                    }
                )
            }
            currentActiveChannelRef.set(channel)
            activeChannelRef.set(channel)
            ctx.ensureActive()

            val inStream = DataInputStream(channel.input)
            val outStream = channel.output

            val passwordBytes = pairingCode.toByteArray(Charsets.UTF_8) + channel.exportedKeyingMaterial
            val spake = Ed25519Spake2.Spake2Session(
                role = Ed25519Spake2.Role.ALICE_CLIENT,
                myName = CLIENT_NAME,
                theirName = SERVER_NAME
            )

            // Step 1: Exchange SPAKE2 messages
            val mySpakeMsg = spake.generateMessage(passwordBytes)
            outStream.write(encodePacket(PACKET_TYPE_SPAKE2_MSG, mySpakeMsg))
            outStream.flush()
            ctx.ensureActive()

            val peerSpakePacket = readPacket(inStream, PACKET_TYPE_SPAKE2_MSG)
            ctx.ensureActive()
            val sharedSecret = spake.processMessage(peerSpakePacket.payload)

            // Step 2: Derive AES-128-GCM key via HKDF-SHA256
            val aesKey = SecretKeySpec(hkdfSha256(sharedSecret, HKDF_INFO, 16), "AES")
            val encNonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()
            val decNonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()

            // Step 3: Encrypt & send MoteTV's ADB RSA public key PeerInfo (8192 bytes)
            val pubKeyPayload = credentialStore.encodeAdbPublicKeyPayload()
            val plainPeerInfo = buildPeerInfoRsaKey(pubKeyPayload)
            val encCipher = Cipher.getInstance("AES/GCM/NoPadding")
            encCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, encNonce))
            val encryptedPeerInfo = encCipher.doFinal(plainPeerInfo)

            outStream.write(encodePacket(PACKET_TYPE_PEER_INFO, encryptedPeerInfo))
            outStream.flush()
            ctx.ensureActive()

            // Step 4: Read & authenticate TV's encrypted PeerInfo
            val peerInfoPacket = readPacket(inStream, PACKET_TYPE_PEER_INFO)
            ctx.ensureActive()
            val decCipher = Cipher.getInstance("AES/GCM/NoPadding")
            decCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, decNonce))
            val decryptedPeerInfo = try {
                decCipher.doFinal(peerInfoPacket.payload)
            } catch (e: AEADBadTagException) {
                throw AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    "Pairing code mismatch — TV rejected SPAKE2 authentication key.",
                    e
                )
            }

            if (decryptedPeerInfo.size != PEER_INFO_SIZE) {
                throw AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    "Unexpected TV PeerInfo size: ${decryptedPeerInfo.size} bytes."
                )
            }

            val peerGuid = decryptedPeerInfo.copyOfRange(1, PEER_INFO_SIZE)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .ifBlank { host }

            Result.success("Paired with TV ($peerGuid). Now enter the TV's Wireless Debugging Connect port to connect ADB.")
        } catch (e: CancellationException) {
            closeActiveResources()
            throw e
        } catch (e: Exception) {
            if (!ctx.isActive) {
                closeActiveResources()
                throw CancellationException("Wireless ADB pairing cancelled", e)
            }
            val wrapped = when {
                e is AdbPairingException -> e
                e is SocketTimeoutException || e.cause is SocketTimeoutException -> AdbPairingException(
                    AdbDiagnosticCategory.CONNECTION_TIMEOUT,
                    "Wireless ADB pairing timed out (${e.message ?: "no TV response"}).",
                    e
                )
                else -> AdbPairingException(
                    AdbDiagnosticCategory.PAIRING_FAILURE,
                    e.message ?: "Wireless Debugging pairing failed",
                    e
                )
            }
            Result.failure(wrapped)
        } finally {
            completionHandle?.dispose()
            closeActiveResources()
        }
    }
}
