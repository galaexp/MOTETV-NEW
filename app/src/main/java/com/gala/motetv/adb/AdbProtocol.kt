package com.gala.motetv.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Pure-Kotlin ADB stream session abstraction allowing deterministic unit testing without physical TVs.
 */
interface AdbSession : Closeable {
    val isConnected: Boolean
    val banner: String

    suspend fun openShell(command: String): AdbCommandResult
    suspend fun pushFile(remotePath: String, content: ByteArray, mode: Int = 420): AdbCommandResult
}

internal object AdbProtocolConstants {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534c5453

    const val VERSION = 0x01000001
    const val VERSION_STLS = 0x01000000
    const val MAX_DATA = 256 * 1024

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val SYNC_MAX_CHUNK = 64 * 1024
}

internal data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPacket) return false
        return command == other.command &&
            arg0 == other.arg0 &&
            arg1 == other.arg1 &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

internal object AdbWireCodec {
    fun encodePacket(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val crc = payload.sumOf { (it.toInt() and 0xFF) }
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(crc)
        buf.putInt(command xor -1)
        if (payload.isNotEmpty()) {
            buf.put(payload)
        }
        return buf.array()
    }

    @Throws(IOException::class)
    fun readPacket(input: DataInputStream): AdbPacket {
        val headerBytes = ByteArray(24)
        input.readFully(headerBytes)
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = header.getInt()
        val arg0 = header.getInt()
        val arg1 = header.getInt()
        val length = header.getInt()
        val crc = header.getInt()
        val magic = header.getInt()

        if ((cmd xor -1) != magic) {
            throw IOException("Invalid ADB packet magic for cmd=0x${cmd.toString(16)}")
        }
        if (length < 0 || length > 1024 * 1024) {
            throw IOException("ADB packet length out of bounds: $length")
        }

        val payload = ByteArray(length)
        if (length > 0) {
            input.readFully(payload)
        }
        // Validate checksum only when non-zero or payload present (STLS modern peers may omit CRC when 0)
        if (crc != 0) {
            val actualCrc = payload.sumOf { (it.toInt() and 0xFF) }
            if (actualCrc != crc) {
                throw IOException("ADB CRC mismatch (expected=$crc, actual=$actualCrc)")
            }
        }
        return AdbPacket(cmd, arg0, arg1, payload)
    }
}

/**
 * Pure-Kotlin TCP/TLS implementation of the ADB wire protocol supporting:
 * - Legacy authenticated network ADB (port 5555)
 * - Post-pairing Wireless Debugging TLS (`STLS` + client certificate / RSA AUTH)
 * - `shell:` execution and `sync:` file push
 */
class AdbTcpSession(
    private val host: String,
    private val port: Int,
    private val mode: AdbConnectionMode,
    private val credentialStore: AdbCredentialStore,
    private val connectTimeoutMs: Int = 8_000,
    private val authApprovalTimeoutMs: Int = 45_000,
    private val onAwaitingTvAuthorization: () -> Unit = {}
) : AdbSession {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private val streamIdCounter = AtomicInteger(1)
    private val ioLock = Any()

    override var banner: String = ""
        private set

    override val isConnected: Boolean
        get() {
            val s = socket ?: return false
            return s.isConnected && !s.isClosed && !s.isInputShutdown && !s.isOutputShutdown
        }

    fun connectAndAuthenticate() {
        val rawSocket = Socket()
        rawSocket.soTimeout = connectTimeoutMs
        rawSocket.tcpNoDelay = true
        rawSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = rawSocket

        var inStream = DataInputStream(rawSocket.getInputStream())
        var outStream = rawSocket.getOutputStream()
        input = inStream
        output = outStream

        // Send CNXN banner
        val cnxnBanner = "host::features=shell_v2,cmd,stat_v2\u0000".toByteArray(Charsets.US_ASCII)
        writePacket(outStream, AdbProtocolConstants.A_CNXN, AdbProtocolConstants.VERSION, AdbProtocolConstants.MAX_DATA, cnxnBanner)

        var sentSignature = false
        var sentPubKey = false

        while (true) {
            val pkt = AdbWireCodec.readPacket(inStream)
            when (pkt.command) {
                AdbProtocolConstants.A_STLS -> {
                    if (mode != AdbConnectionMode.WIRELESS_DEBUGGING_TLS) {
                        // Device requires TLS upgrade even if user chose 5555; honor STLS
                    }
                    writePacket(outStream, AdbProtocolConstants.A_STLS, AdbProtocolConstants.VERSION_STLS, 0)
                    val tlsSocket = upgradeToTls(rawSocket, credentialStore.getOrCreateAdbKeyPair())
                    socket = tlsSocket
                    inStream = DataInputStream(tlsSocket.inputStream)
                    outStream = tlsSocket.outputStream
                    input = inStream
                    output = outStream
                }

                AdbProtocolConstants.A_AUTH -> {
                    if (pkt.arg0 != AdbProtocolConstants.AUTH_TOKEN) {
                        throw IOException("Unexpected ADB AUTH arg0=${pkt.arg0}")
                    }
                    if (!sentSignature) {
                        sentSignature = true
                        val signed = credentialStore.signAuthToken(pkt.payload)
                        writePacket(outStream, AdbProtocolConstants.A_AUTH, AdbProtocolConstants.AUTH_SIGNATURE, 0, signed)
                    } else if (!sentPubKey) {
                        sentPubKey = true
                        socket?.soTimeout = authApprovalTimeoutMs
                        onAwaitingTvAuthorization()
                        val pubPayload = credentialStore.encodeAdbPublicKeyPayload()
                        writePacket(outStream, AdbProtocolConstants.A_AUTH, AdbProtocolConstants.AUTH_RSAPUBLICKEY, 0, pubPayload)
                    } else {
                        throw IOException("ADB authentication rejected after RSAPUBLICKEY exchange")
                    }
                }

                AdbProtocolConstants.A_CNXN -> {
                    banner = pkt.payload.toString(Charsets.UTF_8).trimEnd('\u0000')
                    socket?.soTimeout = 15_000
                    return
                }

                else -> {
                    throw IOException("Unexpected ADB handshake command: 0x${pkt.command.toString(16)}")
                }
            }
        }
    }

    override suspend fun openShell(command: String): AdbCommandResult = synchronized(ioLock) {
        val inStream = input ?: throw IOException("ADB session not connected")
        val outStream = output ?: throw IOException("ADB session not connected")
        val localId = streamIdCounter.getAndIncrement()

        val target = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
        writePacket(outStream, AdbProtocolConstants.A_OPEN, localId, 0, target)

        var remoteId = 0
        val stdoutBuf = ByteArrayOutputStream()

        while (true) {
            val pkt = AdbWireCodec.readPacket(inStream)
            when (pkt.command) {
                AdbProtocolConstants.A_OKAY -> {
                    if (pkt.arg1 == localId) {
                        remoteId = pkt.arg0
                    }
                }

                AdbProtocolConstants.A_WRTE -> {
                    if (pkt.arg1 == localId) {
                        remoteId = pkt.arg0
                        stdoutBuf.write(pkt.payload)
                        writePacket(outStream, AdbProtocolConstants.A_OKAY, localId, remoteId)
                    }
                }

                AdbProtocolConstants.A_CLSE -> {
                    if (pkt.arg1 == localId || (remoteId != 0 && pkt.arg0 == remoteId)) {
                        if (remoteId != 0) {
                            writePacket(outStream, AdbProtocolConstants.A_CLSE, localId, remoteId)
                        }
                        break
                    }
                }

                else -> {
                    // Ignore unrelated packets on connection
                }
            }
        }

        val text = stdoutBuf.toString(Charsets.UTF_8.name())
        AdbCommandResult(exitCode = 0, stdout = text)
    }

    override suspend fun pushFile(remotePath: String, content: ByteArray, mode: Int): AdbCommandResult = synchronized(ioLock) {
        val inStream = input ?: throw IOException("ADB session not connected")
        val outStream = output ?: throw IOException("ADB session not connected")
        val localId = streamIdCounter.getAndIncrement()

        writePacket(outStream, AdbProtocolConstants.A_OPEN, localId, 0, "sync:\u0000".toByteArray(Charsets.UTF_8))
        var remoteId = 0
        while (remoteId == 0) {
            val pkt = AdbWireCodec.readPacket(inStream)
            if (pkt.command == AdbProtocolConstants.A_OKAY && pkt.arg1 == localId) {
                remoteId = pkt.arg0
            } else if (pkt.command == AdbProtocolConstants.A_CLSE && pkt.arg1 == localId) {
                return@synchronized AdbCommandResult(exitCode = 1, stdout = "", stderr = "ADB sync stream closed")
            }
        }

        // Send SYNC SEND header: "SEND" + le32(len) + path,mode
        val spec = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
        val sendHeader = ByteBuffer.allocate(8 + spec.size).order(ByteOrder.LITTLE_ENDIAN)
        sendHeader.put("SEND".toByteArray(Charsets.US_ASCII))
        sendHeader.putInt(spec.size)
        sendHeader.put(spec)
        sendSyncChunk(inStream, outStream, localId, remoteId, sendHeader.array())

        // Stream file data in 64KB chunks
        var offset = 0
        while (offset < content.size) {
            val chunkLen = minOf(AdbProtocolConstants.SYNC_MAX_CHUNK, content.size - offset)
            val dataFrame = ByteBuffer.allocate(8 + chunkLen).order(ByteOrder.LITTLE_ENDIAN)
            dataFrame.put("DATA".toByteArray(Charsets.US_ASCII))
            dataFrame.putInt(chunkLen)
            dataFrame.put(content, offset, chunkLen)
            sendSyncChunk(inStream, outStream, localId, remoteId, dataFrame.array())
            offset += chunkLen
        }

        // Send DONE + mtime
        val doneFrame = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        doneFrame.put("DONE".toByteArray(Charsets.US_ASCII))
        doneFrame.putInt((System.currentTimeMillis() / 1000L).toInt())
        sendSyncChunk(inStream, outStream, localId, remoteId, doneFrame.array())

        // Read OKAY or FAIL from adbd sync
        var status = "OKAY"
        var detail = ""
        while (true) {
            val pkt = AdbWireCodec.readPacket(inStream)
            if (pkt.command == AdbProtocolConstants.A_WRTE && pkt.arg1 == localId) {
                writePacket(outStream, AdbProtocolConstants.A_OKAY, localId, remoteId)
                if (pkt.payload.size >= 4) {
                    status = pkt.payload.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                    detail = if (pkt.payload.size > 8) pkt.payload.copyOfRange(8, pkt.payload.size).toString(Charsets.UTF_8) else ""
                }
                break
            } else if (pkt.command == AdbProtocolConstants.A_CLSE && pkt.arg1 == localId) {
                break
            }
        }

        writePacket(outStream, AdbProtocolConstants.A_CLSE, localId, remoteId)
        if (status == "OKAY") {
            AdbCommandResult(exitCode = 0, stdout = "Pushed ${content.size} bytes to $remotePath")
        } else {
            AdbCommandResult(exitCode = 1, stdout = "", stderr = "ADB sync $status: $detail")
        }
    }

    private fun sendSyncChunk(
        inStream: DataInputStream,
        outStream: OutputStream,
        localId: Int,
        remoteId: Int,
        payload: ByteArray
    ) {
        writePacket(outStream, AdbProtocolConstants.A_WRTE, localId, remoteId, payload)
        while (true) {
            val pkt = AdbWireCodec.readPacket(inStream)
            if (pkt.command == AdbProtocolConstants.A_OKAY && pkt.arg1 == localId) {
                return
            }
            if (pkt.command == AdbProtocolConstants.A_CLSE && pkt.arg1 == localId) {
                throw EOFException("ADB sync channel closed during write")
            }
        }
    }

    private fun writePacket(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
        out.write(AdbWireCodec.encodePacket(cmd, arg0, arg1, payload))
        out.flush()
    }

    private fun upgradeToTls(rawSocket: Socket, keyPair: KeyPair): SSLSocket {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val cert = com.gala.motetv.pairing.TvCertificateManager
            .createSelfSignedX509Certificate(keyPair, "MoteTV-ADB")

        val keyManager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String> = arrayOf("adb")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<java.security.Principal>?, socket: Socket?): String = "adb"
            override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<java.security.Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
            override fun getPrivateKey(alias: String?): java.security.PrivateKey = keyPair.private
        }

        val sslCtx = SSLContext.getInstance("TLSv1.3")
        sslCtx.init(arrayOf(keyManager), trustAll, SecureRandom())
        val sslSocket = sslCtx.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
        sslSocket.useClientMode = true
        sslSocket.startHandshake()
        return sslSocket
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
            input = null
            output = null
        }
    }
}
