package com.gala.motetv.mirror

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.ConnectionState
import com.gala.motetv.remote.TvRemoteManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class TvLaunchResult {
    data class Dispatched(val message: String) : TvLaunchResult()
    data class Failed(val error: String) : TvLaunchResult()
}

class ScreenMirrorController(
    private val context: Context,
    private val remoteManager: TvRemoteManager
) {
    val isMirroring: StateFlow<Boolean> = ScreenMirrorService.isMirroring
    val streamUrl: StateFlow<String> = ScreenMirrorService.streamUrl
    val webUrl: StateFlow<String> = ScreenMirrorService.webUrl
    val currentFps: StateFlow<Int> = ScreenMirrorService.currentFps
    val activeViewers: StateFlow<Int> = ScreenMirrorService.activeViewers
    val statusMessage: StateFlow<String> = ScreenMirrorService.statusMessage

    private val _lastLaunchResult = MutableStateFlow<TvLaunchResult?>(null)
    val lastLaunchResult: StateFlow<TvLaunchResult?> = _lastLaunchResult.asStateFlow()

    fun createScreenCaptureIntent(): Intent {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }

    /**
     * Starts mirroring with the single-use consent intent.
     * The intent is dispatched immediately to the service and NEVER cached or reused.
     */
    fun startMirroring(resultCode: Int, data: Intent) {
        _lastLaunchResult.value = null
        val intent = Intent(context, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_START_MIRROR
            putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenMirrorService.EXTRA_DATA_INTENT, data)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopMirroring() {
        _lastLaunchResult.value = null
        val intent = Intent(context, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_STOP_MIRROR
        }
        context.startService(intent)
    }

    /**
     * Sends launch request to VLC on Android TV.
     * Returns a TvLaunchResult without falsely assuming playback has succeeded.
     */
    suspend fun launchOnTvViaVlc(): TvLaunchResult {
        val conn = remoteManager.connectionState.value
        if (conn !is ConnectionState.Ready) {
            val res = TvLaunchResult.Failed("Android TV is not connected. Connect via Remote tab first.")
            _lastLaunchResult.value = res
            return res
        }

        val url = streamUrl.value
        if (url.isEmpty()) {
            val res = TvLaunchResult.Failed("Mirroring stream not ready or no local Wi-Fi IP address.")
            _lastLaunchResult.value = res
            return res
        }

        TvLogger.i(TvLogger.TAG_GENERAL, "Dispatching mirror stream launch to TV VLC")
        val vlcUri = "vlc://$url"
        try {
            remoteManager.launchAppLink(vlcUri)
            val res = TvLaunchResult.Dispatched("Launch command sent to TV VLC. Waiting for TV connection...")
            _lastLaunchResult.value = res
            return res
        } catch (e: Exception) {
            val res = TvLaunchResult.Failed("Failed to send launch command: ${e.message}")
            _lastLaunchResult.value = res
            return res
        }
    }

    /**
     * Sends launch request to TV Browser.
     * Returns a TvLaunchResult without falsely assuming playback has succeeded.
     */
    suspend fun launchOnTvViaBrowser(): TvLaunchResult {
        val conn = remoteManager.connectionState.value
        if (conn !is ConnectionState.Ready) {
            val res = TvLaunchResult.Failed("Android TV is not connected. Connect via Remote tab first.")
            _lastLaunchResult.value = res
            return res
        }

        val url = webUrl.value
        if (url.isEmpty()) {
            val res = TvLaunchResult.Failed("Mirroring stream not ready or no local Wi-Fi IP address.")
            _lastLaunchResult.value = res
            return res
        }

        TvLogger.i(TvLogger.TAG_GENERAL, "Dispatching mirror web player launch to TV browser")
        try {
            remoteManager.launchAppLink(url)
            val res = TvLaunchResult.Dispatched("Web stream link sent to TV browser. Waiting for TV connection...")
            _lastLaunchResult.value = res
            return res
        } catch (e: Exception) {
            val res = TvLaunchResult.Failed("Failed to send launch command: ${e.message}")
            _lastLaunchResult.value = res
            return res
        }
    }
}
