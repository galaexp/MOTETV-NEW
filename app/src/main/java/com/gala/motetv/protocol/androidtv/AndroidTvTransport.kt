package com.gala.motetv.protocol.androidtv

import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.pairing.TvCertificateManager
import com.gala.motetv.pairing.TvIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class AndroidTvTransport(
    private val host: String,
    private val port: Int,
    private val identity: TvIdentity,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) : Closeable {

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 6000
        const val DEFAULT_READ_TIMEOUT_MS = 20000
    }

    private var sslSocket: SSLSocket? = null
    private var certManager: TvCertificateManager? = null

    var serverCertificate: X509Certificate? = null
        private set

    var frameReader: ProtoFrameReader? = null
        private set

    var frameWriter: ProtoFrameWriter? = null
        private set

    val isConnected: Boolean
        get() = sslSocket?.isConnected == true && sslSocket?.isClosed == false

    /**
     * Connects via mutual TLS to the TV and performs the TLS handshake.
     */
    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        try {
            TvLogger.i(TvLogger.TAG_TLS, "Initiating TLS connection to $host:$port...")

            val cm = TvCertificateManager()
            certManager = cm
            val sslContext: SSLContext = cm.createSslContext(identity)

            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            socket.useClientMode = true
            socket.soTimeout = readTimeoutMs
            socket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")

            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)

            TvLogger.i(TvLogger.TAG_TLS, "Starting TLS handshake with $host:$port...")
            socket.startHandshake()

            val session = socket.session
            TvLogger.i(
                TvLogger.TAG_TLS,
                "TLS Handshake Success: Protocol=${session.protocol}, CipherSuite=${session.cipherSuite}"
            )

            // Extract server certificate
            val peerCerts = try {
                session.peerCertificates
            } catch (e: Exception) {
                null
            }

            serverCertificate = when {
                !peerCerts.isNullOrEmpty() && peerCerts[0] is X509Certificate -> {
                    peerCerts[0] as X509Certificate
                }
                cm.capturedPeerCertificate != null -> {
                    cm.capturedPeerCertificate
                }
                else -> {
                    throw IOException("Server did not present an X.509 certificate during TLS handshake")
                }
            }

            TvLogger.i(
                TvLogger.TAG_TLS,
                "Server Peer Certificate: Subject=${serverCertificate?.subjectDN?.name}, Serial=${serverCertificate?.serialNumber}"
            )

            sslSocket = socket
            frameReader = ProtoFrameReader(socket.inputStream)
            frameWriter = ProtoFrameWriter(socket.outputStream)

        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_TLS, "TLS connection failed to $host:$port: ${e.message}", e)
            close()
            throw e
        }
    }

    override fun close() {
        try {
            sslSocket?.close()
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_TLS, "Exception closing TLS socket: ${e.message}")
        } finally {
            sslSocket = null
            frameReader = null
            frameWriter = null
        }
    }
}
