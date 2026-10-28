package com.gala.motetv.mirror

import com.gala.motetv.core.logging.TvLogger
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScreenMirrorServer(
    val requestedPort: Int = 8088,
    val sessionToken: String = UUID.randomUUID().toString().replace("-", "").take(12)
) {

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    val isBound = AtomicBoolean(false)
    var boundPort: Int = -1
        private set

    private val activeClients = CopyOnWriteArrayList<Socket>()
    val connectedClientsCount = AtomicInteger(0)

    @Volatile
    private var latestFrameJpeg: ByteArray? = null

    companion object {
        const val BOUNDARY = "MoteTvFrameBoundary"
        private const val MAX_PORT_FALLBACKS = 10
    }

    /**
     * Finds a valid IPv4 LAN/Wi-Fi address reachable from local network devices (e.g. Smart TV).
     * Never returns 127.0.0.1, link-local (169.254.x.x), or loopback addresses.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<String>()
            var preferredIp: String? = null

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isPointToPoint || iface.isVirtual) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && !addr.isLinkLocalAddress && !addr.isAnyLocalAddress && !addr.isMulticastAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host == "127.0.0.1" || host.startsWith("169.254.")) continue

                        val name = iface.name.lowercase()
                        if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") || name.startsWith("ap")) {
                            preferredIp = host
                        } else {
                            candidates.add(host)
                        }
                    }
                }
            }
            return preferredIp ?: candidates.firstOrNull()
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_GENERAL, "Failed to inspect network interfaces: ${e.message}")
            return null
        }
    }

    fun getStreamUrl(): String? {
        val ip = getLocalIpAddress() ?: return null
        if (!isBound.get() || boundPort <= 0) return null
        return "http://$ip:$boundPort/stream?token=$sessionToken"
    }

    fun getWebUrl(): String? {
        val ip = getLocalIpAddress() ?: return null
        if (!isBound.get() || boundPort <= 0) return null
        return "http://$ip:$boundPort/?token=$sessionToken"
    }

    /**
     * Synchronously binds to an available port, then starts client handling on a worker thread.
     * Returns true only after the socket is bound.
     */
    fun start(): Boolean {
        if (isRunning.get()) return isBound.get()

        var currentPort = requestedPort
        var boundSocket: ServerSocket? = null

        for (offset in 0..MAX_PORT_FALLBACKS) {
            val candidate = currentPort + offset
            try {
                val ss = ServerSocket(candidate)
                ss.reuseAddress = true
                boundSocket = ss
                boundPort = candidate
                break
            } catch (e: IOException) {
                TvLogger.w(TvLogger.TAG_GENERAL, "Port $candidate in use, trying next port...")
            }
        }

        if (boundSocket == null) {
            TvLogger.e(TvLogger.TAG_GENERAL, "Failed to bind ScreenMirrorServer on ports $requestedPort..${requestedPort + MAX_PORT_FALLBACKS}")
            isBound.set(false)
            return false
        }

        serverSocket = boundSocket
        isBound.set(true)
        isRunning.set(true)
        TvLogger.i(TvLogger.TAG_GENERAL, "ScreenMirrorServer bound on port $boundPort (token protected)")

        Thread({
            while (isRunning.get()) {
                val client = try {
                    serverSocket?.accept()
                } catch (e: Exception) {
                    null
                } ?: break
                handleClient(client)
            }
        }, "ScreenMirrorServer-AcceptLoop").start()

        return true
    }

    private fun handleClient(client: Socket) {
        Thread({
            try {
                client.soTimeout = 10000
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    client.close()
                    return@Thread
                }

                val fullPath = parts[1]
                val path = fullPath.substringBefore("?")
                val queryString = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""
                val token = extractQueryParam(queryString, "token")

                // Consume remaining headers safely
                while (true) {
                    val header = reader.readLine()
                    if (header.isNullOrEmpty()) break
                }

                val out = client.getOutputStream()

                // Security check: validate token
                if (token != sessionToken) {
                    val forbidden = "HTTP/1.1 403 Forbidden\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: 18\r\n" +
                            "Connection: close\r\n\r\n" +
                            "Access restricted\n"
                    out.write(forbidden.toByteArray(Charsets.UTF_8))
                    out.flush()
                    client.close()
                    return@Thread
                }

                // Authorized request
                client.soTimeout = 0 // Remove timeout for persistent streaming
                if (path == "/stream" || path == "/screen.mjpg") {
                    streamMjpeg(client, out)
                } else {
                    serveWebPage(out)
                    client.close()
                }
            } catch (e: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }, "ScreenMirror-ClientWorker").start()
    }

    private fun extractQueryParam(query: String, paramName: String): String? {
        if (query.isEmpty()) return null
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            val key = if (idx > 0) pair.substring(0, idx) else pair
            if (key == paramName) {
                return if (idx > 0 && idx < pair.length - 1) pair.substring(idx + 1) else ""
            }
        }
        return null
    }

    private fun serveWebPage(out: OutputStream) {
        val streamSrc = "/stream?token=$sessionToken"
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>MoteTV Screen Mirror</title>
                <style>
                    body, html {
                        margin: 0; padding: 0;
                        width: 100%; height: 100%;
                        background-color: #000;
                        display: flex; align-items: center; justify-content: center;
                        overflow: hidden;
                    }
                    img {
                        max-width: 100vw;
                        max-height: 100vh;
                        object-fit: contain;
                    }
                </style>
            </head>
            <body>
                <img src="$streamSrc" alt="Live Screen Stream" />
            </body>
            </html>
        """.trimIndent()

        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun streamMjpeg(client: Socket, out: OutputStream) {
        val isClientTracked = AtomicBoolean(false)
        if (activeClients.addIfAbsent(client)) {
            connectedClientsCount.incrementAndGet()
            isClientTracked.set(true)
        }

        try {
            // Correct multipart boundary formatting per RFC 2046:
            // Parameter value must be BOUNDARY (without leading '--')
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()

            var lastSentFrame: ByteArray? = null

            while (isRunning.get() && !client.isClosed) {
                val current = latestFrameJpeg
                if (current != null && current !== lastSentFrame) {
                    lastSentFrame = current
                    val partHeader = "--$BOUNDARY\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${current.size}\r\n\r\n"
                    out.write(partHeader.toByteArray(Charsets.US_ASCII))
                    out.write(current)
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                } else {
                    Thread.sleep(15) // ~60 Hz poll interval
                }
            }

            // Write closing boundary if shutting down gracefully
            try {
                out.write("--$BOUNDARY--\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            } catch (_: Exception) {}

        } catch (e: Exception) {
            // Client closed connection or network error - handle cleanly
        } finally {
            if (activeClients.remove(client) && isClientTracked.compareAndSet(true, false)) {
                connectedClientsCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            }
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun onNewFrame(jpegBytes: ByteArray) {
        latestFrameJpeg = jpegBytes
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        isBound.set(false)

        val clientsToClose = activeClients.toList()
        activeClients.clear()

        for (client in clientsToClose) {
            try { client.close() } catch (_: Exception) {}
        }

        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        latestFrameJpeg = null
        boundPort = -1
        connectedClientsCount.set(0)
        TvLogger.i(TvLogger.TAG_GENERAL, "ScreenMirrorServer stopped and session token invalidated")
    }
}
