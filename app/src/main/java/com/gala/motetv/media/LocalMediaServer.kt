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
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
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
        val bytesServed: Long = 0L
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
    private var currentUri: Uri? = null
    private var currentFileName: String = ""
    private var currentFileSize: Long = 0L
    private var currentMimeType: String = "video/mp4"
    private var totalBytesServed: Long = 0L

    companion object {
        const val DEFAULT_PORT = 8989
        private val RANGE_HEADER_PATTERN = Pattern.compile("bytes=(\\d+)-(\\d*)")
    }

    /**
     * Starts the HTTP media streaming server on local Wi-Fi.
     * Returns the complete HTTP stream URL for VLC on TV.
     */
    fun startStreaming(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        mimeType: String
    ): String {
        stopStreaming()

        currentUri = uri
        currentFileName = fileName
        currentFileSize = fileSize
        currentMimeType = if (mimeType.isBlank()) "video/mp4" else mimeType
        totalBytesServed = 0L

        val hostIp = getLocalWifiIpAddress()
        var port = DEFAULT_PORT

        var socket: ServerSocket? = null
        for (candidatePort in DEFAULT_PORT..(DEFAULT_PORT + 10)) {
            try {
                socket = ServerSocket(candidatePort)
                port = candidatePort
                break
            } catch (_: Exception) {
                // Try next port
            }
        }

        if (socket == null) {
            val errorMsg = "Could not bind local streaming port ($DEFAULT_PORT-${DEFAULT_PORT + 10})"
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
            fileSize = fileSize,
            mimeType = currentMimeType,
            bytesServed = 0L
        )

        TvLogger.i(
            TvLogger.TAG_GENERAL,
            "[LOCAL_MEDIA_SERVER] Started streaming server at $streamUrl (file='$fileName', size=$fileSize bytes, type='$currentMimeType')"
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
     * Stops the HTTP media streaming server and releases resources.
     */
    fun stopStreaming() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        currentUri = null
        _state.value = MediaServerState.Idle
        TvLogger.i(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Stopped media server")
    }

    private fun handleClientRequest(socket: Socket) {
        try {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val output = client.getOutputStream()

                val requestLine = reader.readLine() ?: return
                TvLogger.d(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Request: $requestLine")

                val parts = requestLine.split(" ")
                val method = if (parts.isNotEmpty()) parts[0].uppercase() else "GET"

                var rangeHeader: String? = null
                var line: String? = reader.readLine()
                while (!line.isNullOrBlank()) {
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substring(6).trim()
                    }
                    line = reader.readLine()
                }

                val uri = currentUri ?: run {
                    sendNotFound(output)
                    return
                }

                serveMedia(output, uri, method, rangeHeader)
            }
        } catch (e: Exception) {
            TvLogger.d(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Client stream closed: ${e.message}")
        }
    }

    private fun serveMedia(
        output: OutputStream,
        uri: Uri,
        method: String,
        rangeHeader: String?
    ) {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_SERVER] Cannot open file descriptor for $uri: ${e.message}", e)
            sendNotFound(output)
            return
        }

        if (pfd == null) {
            sendNotFound(output)
            return
        }

        pfd.use { descriptor ->
            val totalLength = if (currentFileSize > 0L) currentFileSize else descriptor.statSize
            var startByte = 0L
            var endByte = (totalLength - 1L).coerceAtLeast(0L)
            val isRangeRequest = !rangeHeader.isNullOrBlank()

            if (isRangeRequest) {
                val matcher = RANGE_HEADER_PATTERN.matcher(rangeHeader ?: "")
                if (matcher.find()) {
                    val startStr = matcher.group(1)
                    val endStr = matcher.group(2)
                    startByte = startStr?.toLongOrNull() ?: 0L
                    if (!endStr.isNullOrBlank()) {
                        val parsedEnd = endStr.toLongOrNull()
                        if (parsedEnd != null && parsedEnd in startByte until totalLength) {
                            endByte = parsedEnd
                        }
                    }
                }
            }

            val contentLength = (endByte - startByte + 1L).coerceAtLeast(0L)

            val headerBuilder = StringBuilder()
            if (isRangeRequest) {
                headerBuilder.append("HTTP/1.1 206 Partial Content\r\n")
                headerBuilder.append("Content-Range: bytes $startByte-$endByte/$totalLength\r\n")
            } else {
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
            }

            headerBuilder.append("Content-Type: $currentMimeType\r\n")
            headerBuilder.append("Content-Length: $contentLength\r\n")
            headerBuilder.append("Accept-Ranges: bytes\r\n")
            headerBuilder.append("Connection: keep-alive\r\n")
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n\r\n")

            output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
            output.flush()

            if (method.equals("HEAD", ignoreCase = true)) {
                return
            }

            FileInputStream(descriptor.fileDescriptor).use { fis ->
                val channel = fis.channel
                channel.position(startByte)

                val buffer = ByteArray(64 * 1024)
                var remaining = contentLength

                while (remaining > 0L) {
                    val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                    val bytesRead = fis.read(buffer, 0, toRead)
                    if (bytesRead == -1) break

                    output.write(buffer, 0, bytesRead)
                    remaining -= bytesRead
                    totalBytesServed += bytesRead

                    val currentState = _state.value
                    if (currentState is MediaServerState.Running) {
                        _state.value = currentState.copy(bytesServed = totalBytesServed)
                    }
                }
                output.flush()
            }
        }
    }

    private fun sendNotFound(output: OutputStream) {
        val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /**
     * Resolves the primary local Wi-Fi IPv4 address of the phone.
     */
    fun getLocalWifiIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: ""
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "Could not determine local IP: ${e.message}")
        }
        return "127.0.0.1"
    }
}
