package com.gala.motetv.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {
    private val _sessionState = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
    val sessionState: StateFlow<CastSessionState> = _sessionState.asStateFlow()

    private var boundService: MediaStreamService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MediaStreamService.LocalBinder
            val svc = binder?.getService()
            boundService = svc
            isBound = true
            TvLogger.i(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_CAST_CONTROLLER] Bound to MediaStreamService")

            // Observe streaming state from service
            if (svc != null) {
                scope.launch {
                    svc.streamingState.collect { state ->
                        // Only override if active, or if we were streaming
                        if (state is CastSessionState.Streaming || state is CastSessionState.Error) {
                            _sessionState.value = state
                        } else if (state is CastSessionState.Idle && _sessionState.value is CastSessionState.Streaming) {
                            _sessionState.value = CastSessionState.Idle
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
            TvLogger.i(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_CAST_CONTROLLER] Unbound from MediaStreamService")
        }
    }

    init {
        // Attempt to bind to service if already running
        try {
            val intent = Intent(context, MediaStreamService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "[LOCAL_MEDIA_CAST_CONTROLLER] bindService init error: ${e.message}")
        }
    }

    /**
     * Called when the user picks a media file using Storage Access Framework (SAF).
     */
    fun onMediaFilePicked(uri: Uri) {
        val mediaInfo = extractMediaInfo(uri)
        TvLogger.i(
            TvLogger.TAG_GENERAL,
            "[MEDIA_CAST] Selected media: name='${mediaInfo.name}', size=${mediaInfo.size} bytes, type='${mediaInfo.mimeType}'"
        )
        _sessionState.value = CastSessionState.Selected(mediaInfo)
    }

    /**
     * Starts local streaming by launching MediaStreamService in foreground and
     * sending launch request to Android TV VLC.
     */
    fun startCast(mediaInfo: SelectedMediaInfo, useDirectHttpFallback: Boolean = false) {
        val tempServer = LocalMediaServer(context)
        val hostIp = tempServer.getLocalWifiIpAddress()
        val streamUrl = "http://$hostIp:8989/media"

        _sessionState.value = CastSessionState.Streaming(
            media = mediaInfo,
            streamUrl = streamUrl,
            isPlaying = true,
            statusMessage = "Starting playback on TV via VLC..."
        )

        scope.launch(Dispatchers.IO) {
            try {
                TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_CAST] Starting MediaStreamService for ${mediaInfo.name}")

                // 1. Start foreground service
                MediaStreamService.startStreaming(context, mediaInfo)

                // Bind if not bound
                if (!isBound) {
                    val intent = Intent(context, MediaStreamService::class.java)
                    context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                }

                // Dispatch to TV if Remote is ready.
                // NOTE: TV Remote connection is separate from the HTTP stream!
                if (remoteManager.remoteState.value == RemoteState.READY) {
                    val launchUri = if (useDirectHttpFallback) streamUrl else "vlc://$streamUrl"
                    TvLogger.i(TvLogger.TAG_REMOTE, "[MEDIA_CAST] Dispatching launch to TV: $launchUri")
                    remoteManager.launchAppLink(launchUri)
                } else {
                    TvLogger.w(
                        TvLogger.TAG_REMOTE,
                        "[MEDIA_CAST] Remote is not currently connected (${remoteManager.remoteState.value}), but HTTP streaming service is running at $streamUrl"
                    )
                }
            } catch (e: Exception) {
                TvLogger.e(TvLogger.TAG_GENERAL, "[MEDIA_CAST] Cast error: ${e.message}", e)
                _sessionState.value = CastSessionState.Error(e.message ?: "Failed to start streaming")
            }
        }
    }

    /**
     * Stops the media cast session and shuts down the foreground streaming service.
     */
    fun stopCast() {
        scope.launch(Dispatchers.IO) {
            TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_CAST] Stopping cast and service")
            MediaStreamService.stopStreaming(context)

            if (remoteManager.remoteState.value == RemoteState.READY) {
                remoteManager.sendKey(AndroidTvKeyCodes.KEYCODE_MEDIA_STOP, Direction.SHORT)
            }
            _sessionState.value = CastSessionState.Idle
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

    fun cleanUpOnAppExit() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
        }
    }
}
