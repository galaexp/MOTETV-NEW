package com.gala.motetv.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.AndroidTvKeyCodes
import com.gala.motetv.remote.RemoteState
import com.gala.motetv.remote.TvRemoteManager
import com.google.android.apps.tv.remote.protocol.Direction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SelectedMediaInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
)

sealed class CastSessionState {
    data object Idle : CastSessionState()
    data class Selected(val media: SelectedMediaInfo) : CastSessionState()
    data class Streaming(
        val media: SelectedMediaInfo,
        val streamUrl: String,
        val isPlaying: Boolean = true,
        val statusMessage: String = "Streaming to TV via VLC"
    ) : CastSessionState()
    data class Error(val message: String) : CastSessionState()
}

class LocalMediaCastController(
    private val context: Context,
    private val remoteManager: TvRemoteManager,
    private val mediaServer: LocalMediaServer = LocalMediaServer(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {
    private val _sessionState = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
    val sessionState: StateFlow<CastSessionState> = _sessionState.asStateFlow()

    val serverState: StateFlow<MediaServerState> = mediaServer.state

    /**
     * Called when the user picks a media file using Storage Access Framework (SAF).
     */
    fun onMediaFilePicked(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val mediaInfo = extractMediaInfo(uri)
            TvLogger.i(
                TvLogger.TAG_GENERAL,
                "[MEDIA_CAST] Selected media: name='${mediaInfo.name}', size=${mediaInfo.size} bytes, type='${mediaInfo.mimeType}'"
            )
            _sessionState.value = CastSessionState.Selected(mediaInfo)
        }
    }

    /**
     * Starts local streaming and sends launch request to Android TV VLC.
     */
    fun startCast(mediaInfo: SelectedMediaInfo, useDirectHttpFallback: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                val streamUrl = mediaServer.startStreaming(
                    uri = mediaInfo.uri,
                    fileName = mediaInfo.name,
                    fileSize = mediaInfo.size,
                    mimeType = mediaInfo.mimeType
                )

                if (streamUrl.isBlank()) {
                    _sessionState.value = CastSessionState.Error("Failed to start local streaming server.")
                    return@launch
                }

                if (remoteManager.remoteState.value != RemoteState.READY) {
                    TvLogger.w(TvLogger.TAG_REMOTE, "[MEDIA_CAST] Remote is not ready (${remoteManager.remoteState.value}), but server is running at $streamUrl")
                    _sessionState.value = CastSessionState.Streaming(
                        media = mediaInfo,
                        streamUrl = streamUrl,
                        statusMessage = "Server active! Connect TV to auto-launch VLC."
                    )
                    return@launch
                }

                // First strategy: vlc://<streamUrl>
                val launchUri = if (useDirectHttpFallback) streamUrl else "vlc://$streamUrl"
                TvLogger.i(TvLogger.TAG_REMOTE, "[MEDIA_CAST] Dispatching launch to TV: $launchUri")
                remoteManager.launchAppLink(launchUri)

                _sessionState.value = CastSessionState.Streaming(
                    media = mediaInfo,
                    streamUrl = streamUrl,
                    statusMessage = "Playing on TV via VLC ($streamUrl)"
                )
            } catch (e: Exception) {
                TvLogger.e(TvLogger.TAG_GENERAL, "[MEDIA_CAST] Cast error: ${e.message}", e)
                _sessionState.value = CastSessionState.Error(e.message ?: "Failed to start streaming")
            }
        }
    }

    /**
     * Stops the media cast session and shuts down the local HTTP streaming server.
     */
    fun stopCast() {
        scope.launch(Dispatchers.IO) {
            mediaServer.stopStreaming()
            if (remoteManager.remoteState.value == RemoteState.READY) {
                remoteManager.sendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_STOP, Direction.SHORT)
            }
            _sessionState.value = CastSessionState.Idle
            TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_CAST] Cast stopped and server shut down")
        }
    }

    // Media playback controls while casting
    fun togglePlayPause() {
        val current = _sessionState.value
        if (current is CastSessionState.Streaming) {
            remoteManager.sendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE, Direction.SHORT)
            _sessionState.value = current.copy(isPlaying = !current.isPlaying)
        }
    }

    fun seekBackward10s() {
        remoteManager.sendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_REWIND, Direction.SHORT)
    }

    fun seekForward10s() {
        remoteManager.sendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_FAST_FORWARD, Direction.SHORT)
    }

    fun volumeUp() {
        remoteManager.sendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_UP, Direction.SHORT)
    }

    fun volumeDown() {
        remoteManager.sendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_DOWN, Direction.SHORT)
    }

    fun volumeMute() {
        remoteManager.sendKey(AndroidTvKeyCodes.KEYCODE_VOLUME_MUTE, Direction.SHORT)
    }

    fun launchVlcAppStoreOnTv() {
        remoteManager.launchAppLink("market://details?id=org.videolan.vlc")
    }

    private fun extractMediaInfo(uri: Uri): SelectedMediaInfo {
        var displayName = "video.mp4"
        var size = 0L
        val mimeType = context.contentResolver.getType(uri) ?: "video/mp4"

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex) ?: displayName
                    }
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "Failed to query metadata for $uri: ${e.message}")
        }

        return SelectedMediaInfo(
            uri = uri,
            name = displayName,
            size = size,
            mimeType = mimeType
        )
    }
}
