package com.gala.motetv.mirror

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import com.gala.motetv.core.logging.TvLogger
import com.gala.motetv.core.model.TvDevice
import com.gala.motetv.remote.TvRemoteManager
import kotlinx.coroutines.flow.StateFlow

class ScreenMirrorController(
    private val context: Context,
    private val remoteManager: TvRemoteManager
) {
    val isMirroring: StateFlow<Boolean> = ScreenMirrorService.isMirroring
    val streamUrl: StateFlow<String> = ScreenMirrorService.streamUrl
    val webUrl: StateFlow<String> = ScreenMirrorService.webUrl
    val currentFps: StateFlow<Int> = ScreenMirrorService.currentFps
    val activeViewers: StateFlow<Int> = ScreenMirrorService.activeViewers

    fun createScreenCaptureIntent(): Intent {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }

    fun startMirroring(resultCode: Int, data: Intent) {
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
        val intent = Intent(context, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_STOP_MIRROR
        }
        context.startService(intent)
    }

    suspend fun launchOnTvViaVlc(): Boolean {
        val url = streamUrl.value
        if (url.isEmpty()) return false
        TvLogger.i(TvLogger.TAG_GENERAL, "Launching mirror stream in TV VLC: $url")
        val vlcUri = "vlc://$url"
        remoteManager.launchAppLink(vlcUri)
        return true
    }

    suspend fun launchOnTvViaBrowser(): Boolean {
        val url = webUrl.value
        if (url.isEmpty()) return false
        TvLogger.i(TvLogger.TAG_GENERAL, "Launching mirror web player on TV browser: $url")
        remoteManager.launchAppLink(url)
        return true
    }
}
