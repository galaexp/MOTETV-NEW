package com.gala.motetv.mirror

import android.graphics.Bitmap
import com.gala.motetv.core.logging.TvLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScreenMirrorServer(private val port: Int = 8088) {

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val activeClients = CopyOnWriteArrayList<Socket>()
    val connectedClientsCount = AtomicInteger(0)

    @Volatile
    private var latestFrameJpeg: ByteArray? = null

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_GENERAL, "Failed to get local IP: ${e.message}")
        }
        return "127.0.0.1"
    }

    fun getStreamUrl(): String = "http://${getLocalIpAddress()}:$port/stream"
    fun getWebUrl(): String = "http://${getLocalIpAddress()}:$port/"

    fun start() {
        if (isRunning.getAndSet(true)) return
        Thread({
            try {
                serverSocket = ServerSocket(port)
                TvLogger.i(TvLogger.TAG_GENERAL, "ScreenMirrorServer started on port $port")
                while (isRunning.get()) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    TvLogger.e(TvLogger.TAG_GENERAL, "ScreenMirrorServer exception: ${e.message}")
                }
            } finally {
                stop()
            }
        }, "ScreenMirrorServer-Thread").start()
    }

    private fun handleClient(client: Socket) {
        Thread({
            try {
                client.soTimeout = 0
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                val path = if (parts.size > 1) parts[1] else "/"

                // Consume headers
                while (true) {
                    val header = reader.readLine()
                    if (header.isNullOrEmpty()) break
                }

                val out = client.getOutputStream()
                if (path == "/stream" || path == "/screen.mjpg") {
                    streamMjpeg(client, out)
                } else {
                    serveWebPage(out)
                    client.close()
                }
            } catch (e: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }, "ScreenMirror-Client").start()
    }

    private fun serveWebPage(out: OutputStream) {
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
                <img src="/stream" alt="Live Screen Stream" />
            </body>
            </html>
        """.trimIndent()

        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun streamMjpeg(client: Socket, out: OutputStream) {
        activeClients.add(client)
        connectedClientsCount.incrementAndGet()
        val boundary = "MoteTvFrameBoundary"
        try {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=--$boundary\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.flush()

            var lastSentFrame: ByteArray? = null

            while (isRunning.get() && !client.isClosed) {
                val current = latestFrameJpeg
                if (current != null && current !== lastSentFrame) {
                    lastSentFrame = current
                    val partHeader = "--$boundary\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${current.size}\r\n\r\n"
                    out.write(partHeader.toByteArray())
                    out.write(current)
                    out.write("\r\n".toByteArray())
                    out.flush()
                } else {
                    Thread.sleep(15) // ~60fps poll
                }
            }
        } catch (e: Exception) {
            // Client disconnected normally
        } finally {
            activeClients.remove(client)
            connectedClientsCount.decrementAndGet()
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun onNewFrame(jpegBytes: ByteArray) {
        latestFrameJpeg = jpegBytes
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        for (client in activeClients) {
            try { client.close() } catch (_: Exception) {}
        }
        activeClients.clear()
        connectedClientsCount.set(0)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        latestFrameJpeg = null
    }
}
