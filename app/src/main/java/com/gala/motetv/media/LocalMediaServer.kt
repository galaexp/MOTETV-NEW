package com.gala.motetv.media

import android.content.Context
import android.net.Uri
import com.gala.motetv.core.logging.TvLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

sealed class MediaServerState {
    data object Idle : MediaServerState()
    data class Running(
        val streamUrl: String,
        val host: String,
        val port: Int,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val bytesServed: Long = 0L,
        val activeClients: Int = 0
    ) : MediaServerState()
    data class Error(val message: String) : MediaServerState()
}

class LocalMediaServer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _state = MutableStateFlow<MediaServerState>(MediaServerState.Idle)
    val state: StateFlow<MediaServerState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    // Immutable session reference for thread-safety and no midway state mutations
    @Volatile
    private var activeSession: StreamingSession? = null

    private val totalBytesServed = AtomicLong(0L)
    private val activeClientCount = java.util.concurrent.atomic.AtomicInteger(0)

    private data class StreamingSession(
        val uri: Uri,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val tempSeekableFile: File? = null
    )

    companion object {
        const val DEFAULT_PORT = 8989
        // Matches "bytes=start-end", "bytes=start-", or "bytes=-suffix"
        private val RANGE_HEADER_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)")
        private const val CHUNK_BUFFER_SIZE = 64 * 1024 // 64 KB
    }

    /**
     * Starts the HTTP media streaming server on local Wi-Fi.
     * Returns the complete HTTP stream URL for VLC on TV, or empty string on failure.
     */
    @Synchronized
    fun startStreaming(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        mimeType: String
    ): String {
        stopStreaming()

        totalBytesServed.set(0L)
        activeClientCount.set(0)

        // Verify SAF access and determine if seekable
        var resolvedSize = fileSize
        var tempFile: File? = null

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val statSize = pfd.statSize
                if (statSize > 0L) {
                    resolvedSize = statSize
                }
                pfd.close()
            } else {
                TvLogger.w(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] OpenFileDescriptor returned null for $uri, checking stream")
                // Check if openInputStream works
                context.contentResolver.openInputStream(uri)?.close() ?: run {
                    val err = "Cannot open media source: $fileName"
                    TvLogger.e(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] $err")
                    _state.value = MediaServerState.Error(err)
                    return ""
                }
            }
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Failed to open direct PFD: ${e.message}")
        }

        val session = StreamingSession(
            uri = uri,
            fileName = fileName,
            fileSize = resolvedSize,
            mimeType = if (mimeType.isBlank()) "video/mp4" else mimeType,
            tempSeekableFile = tempFile
        )
        activeSession = session

        val hostIp = getLocalWifiIpAddress()
        if (hostIp == "127.0.0.1") {
            TvLogger.w(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Warning: local IP resolved to 127.0.0.1. No active Wi-Fi LAN interface detected.")
        }

        var port = DEFAULT_PORT
        var socket: ServerSocket? = null
        for (candidatePort in DEFAULT_PORT..(DEFAULT_PORT + 20)) {
            try {
                socket = ServerSocket(candidatePort)
                port = candidatePort
                break
            } catch (_: Exception) {
                // Try next port
            }
        }

        if (socket == null) {
            val errorMsg = "Could not bind local streaming port ($DEFAULT_PORT-${DEFAULT_PORT + 20})"
            TvLogger.e(TvLogger.TAG_GENERAL, errorMsg)
            _state.value = MediaServerState.Error(errorMsg)
            return ""
        }

        serverSocket = socket
        val streamUrl = "http://$hostIp:$port/media"

        _state.value = MediaServerState.Running(
            streamUrl = streamUrl,
            host = hostIp,
            port = port,
            fileName = fileName,
            fileSize = resolvedSize,
            mimeType = session.mimeType,
            bytesServed = 0L,
            activeClients = 0
        )

        TvLogger.i(
            TvLogger.TAG_GENERAL,
            "[LOCAL_MEDIA_SERVER] Started streaming server at $streamUrl (file='$fileName', size=$resolvedSize bytes, type='${session.mimeType}')"
        )

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && serverSocket?.isClosed == false) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch(Dispatchers.IO) {
                        handleClientRequest(clientSocket)
                    }
                } catch (e: Exception) {
                    if (isActive && serverSocket?.isClosed == false) {
                        TvLogger.w(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Accept error: ${e.message}")
                    }
                }
            }
        }

        return streamUrl
    }

    /**
     * Stops the HTTP media streaming server and releases sockets and temporary files.
     */
    @Synchronized
    fun stopStreaming() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        val prevSession = activeSession
        activeSession = null
        prevSession?.tempSeekableFile?.delete()

        _state.value = MediaServerState.Idle
        TvLogger.i(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Stopped media server")
    }

    private fun handleClientRequest(socket: Socket) {
        val session = activeSession ?: run {
            try {
                socket.close()
            } catch (_: Exception) {}
            return
        }

        activeClientCount.incrementAndGet()
        updateRunningState()

        try {
            socket.use { client ->
                // Set reasonable socket timeouts for read
                client.soTimeout = 30000

                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val output = client.getOutputStream()

                val requestLine = reader.readLine() ?: return
                TvLogger.d(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Request: $requestLine")

                val parts = requestLine.split(" ")
                val method = if (parts.isNotEmpty()) parts[0].uppercase() else "GET"
                val path = if (parts.size > 1) parts[1] else "/"

                var rangeHeader: String? = null
                var line: String? = reader.readLine()
                while (!line.isNullOrBlank()) {
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substring(6).trim()
                    }
                    line = reader.readLine()
                }

                if (!path.startsWith("/media")) {
                    sendNotFound(output)
                    return
                }

                serveMedia(output, session, method, rangeHeader)
            }
        } catch (e: SocketException) {
            // Client closed stream normally or disconnected
            TvLogger.d(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Client connection ended: ${e.message}")
        } catch (e: Exception) {
            TvLogger.d(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Client stream error: ${e.message}")
        } finally {
            activeClientCount.decrementAndGet()
            updateRunningState()
        }
    }

    private fun updateRunningState() {
        val current = _state.value
        if (current is MediaServerState.Running) {
            _state.value = current.copy(
                bytesServed = totalBytesServed.get(),
                activeClients = activeClientCount.get().coerceAtLeast(0)
            )
        }
    }

    private fun serveMedia(
        output: OutputStream,
        session: StreamingSession,
        method: String,
        rangeHeader: String?
    ) {
        // 1. Obtain FileDescriptor
        val pfd = try {
            context.contentResolver.openFileDescriptor(session.uri, "r")
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Cannot open file descriptor for ${session.uri}: ${e.message}", e)
            sendNotFound(output)
            return
        }

        if (pfd == null) {
            sendNotFound(output)
            return
        }

        pfd.use { descriptor ->
            val totalLength = if (session.fileSize > 0L) session.fileSize else descriptor.statSize
            val hasKnownLength = totalLength > 0L

            var isRangeRequest = !rangeHeader.isNullOrBlank()
            var startByte = 0L
            var endByte = if (hasKnownLength) totalLength - 1L else -1L

            if (isRangeRequest && hasKnownLength) {
                val matcher = RANGE_HEADER_PATTERN.matcher(rangeHeader ?: "")
                if (matcher.find()) {
                    val startStr = matcher.group(1)
                    val endStr = matcher.group(2)

                    if (startStr.isNullOrBlank() && !endStr.isNullOrBlank()) {
                        // Suffix byte range: bytes=-500 (last 500 bytes)
                        val suffixLen = endStr.toLongOrNull() ?: 0L
                        startByte = (totalLength - suffixLen).coerceAtLeast(0L)
                        endByte = totalLength - 1L
                    } else if (!startStr.isNullOrBlank()) {
                        startByte = startStr.toLongOrNull() ?: 0L
                        if (!endStr.isNullOrBlank()) {
                            endByte = endStr.toLongOrNull() ?: (totalLength - 1L)
                        } else {
                            endByte = totalLength - 1L
                        }
                    } else {
                        isRangeRequest = false
                    }
                } else {
                    isRangeRequest = false
                }

                // Check range validity
                if (startByte < 0L || startByte >= totalLength || endByte < startByte) {
                    TvLogger.w(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Invalid Range requested: $rangeHeader (size=$totalLength)")
                    sendRangeNotSatisfiable(output, totalLength)
                    return
                }

                // Coerce endByte to totalLength - 1
                endByte = endByte.coerceAtMost(totalLength - 1L)
            } else {
                isRangeRequest = false
                startByte = 0L
                if (hasKnownLength) {
                    endByte = totalLength - 1L
                }
            }

            val contentLength = if (hasKnownLength) {
                (endByte - startByte + 1L).coerceAtLeast(0L)
            } else {
                -1L
            }

            val headerBuilder = StringBuilder()
            if (isRangeRequest) {
                headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
                headerBuilder.append("Content-Range: bytes $startByte-$endByte/$totalLength\r\n")
            } else {
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
            }

            headerBuilder.append("Content-Type: ${session.mimeType}\r\n")
            if (contentLength >= 0L) {
                headerBuilder.append("Content-Length: $contentLength\r\n")
            }
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            headerBuilder.append("Connection: close\r\n")
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n\r\n")

            output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
            output.flush()

            if (method.equals("HEAD", ignoreCase = true)) {
                return
            }

            // Stream file contents incrementally with bounded buffer
            FileInputStream(descriptor.fileDescriptor).use { fis ->
                val channel = fis.channel
                if (startByte > 0L) {
                    channel.position(startByte)
                }

                val buffer = ByteArray(CHUNK_BUFFER_SIZE)
                var remaining = if (contentLength >= 0L) contentLength else Long.MAX_VALUE

                while (remaining > 0L && activeSession != null) {
                    val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                    val bytesRead = fis.read(buffer, 0, toRead)
                    if (bytesRead == -1) break

                    output.write(buffer, 0, bytesRead)
                    if (contentLength >= 0L) {
                        remaining -= bytesRead
                    }
                    totalBytesServed.addAndGet(bytesRead.toLong())
                    updateRunningState()
                }
                output.flush()
            }
        }
    }

    private fun sendNotFound(output: OutputStream) {
        val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun sendRangeNotSatisfiable(output: OutputStream, totalLength: Long) {
        val response = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLength\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /**
     * Resolves the primary local Wi-Fi IPv4 address of the phone.
     */
    fun getLocalWifiIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val candidateIps = mutableListOf<String>()

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val name = iface.name.lowercase()
                // Avoid tun/ppp/dummy VPN interfaces if possible
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy") || name.startsWith("tap")) {
                    continue
                }

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: ""
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            // If it's a wlan or eth interface, prefer it immediately
                            if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                                return host
                            }
                            candidateIps.add(host)
                        }
                    }
                }
            }

            if (candidateIps.isNotEmpty()) {
                return candidateIps.first()
            }
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "Could not determine local IP: ${e.message}")
        }
        return "127.0.0.1"
    }
}
