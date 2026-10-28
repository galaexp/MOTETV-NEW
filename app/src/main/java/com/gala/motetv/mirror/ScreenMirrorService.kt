package com.gala.motetv.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.gala.motetv.core.logging.TvLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMirrorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mirrorServer: ScreenMirrorServer? = null

    private var isCompressing = AtomicBoolean(false)
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()

    companion object {
        const val ACTION_START_MIRROR = "com.gala.motetv.action.START_MIRROR"
        const val ACTION_STOP_MIRROR = "com.gala.motetv.action.STOP_MIRROR"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"

        private const val CHANNEL_ID = "mote_screen_mirror_channel"
        private const val NOTIFICATION_ID = 2002

        private val _isMirroring = MutableStateFlow(false)
        val isMirroring: StateFlow<Boolean> = _isMirroring.asStateFlow()

        private val _streamUrl = MutableStateFlow("")
        val streamUrl: StateFlow<String> = _streamUrl.asStateFlow()

        private val _webUrl = MutableStateFlow("")
        val webUrl: StateFlow<String> = _webUrl.asStateFlow()

        private val _currentFps = MutableStateFlow(0)
        val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

        private val _activeViewers = MutableStateFlow(0)
        val activeViewers: StateFlow<Int> = _activeViewers.asStateFlow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_MIRROR -> {
                stopMirroring()
                stopSelf()
            }
            ACTION_START_MIRROR -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                }

                if (resultCode != 0 && data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Starting Screen Mirroring..."))
                    startMirroring(resultCode, data)
                } else {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startMirroring(resultCode: Int, data: Intent) {
        try {
            mirrorServer = ScreenMirrorServer(8088).apply { start() }
            _streamUrl.value = mirrorServer?.getStreamUrl() ?: ""
            _webUrl.value = mirrorServer?.getWebUrl() ?: ""

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            val metrics = resources.displayMetrics
            // Balanced streaming resolution for ultra smooth 30fps with low network latency
            val width = 720
            val height = (720f * (metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat())).toInt()
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                if (isCompressing.compareAndSet(false, true)) {
                    serviceScope.launch {
                        try {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                            image.close()

                            val croppedBitmap = if (rowPadding > 0) {
                                Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            } else {
                                bitmap
                            }

                            val out = ByteArrayOutputStream()
                            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                            val jpegBytes = out.toByteArray()
                            mirrorServer?.onNewFrame(jpegBytes)

                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTimestamp >= 1000) {
                                _currentFps.value = frameCount
                                frameCount = 0
                                lastFpsTimestamp = now
                                _activeViewers.value = mirrorServer?.connectedClientsCount?.get() ?: 0
                            }
                        } catch (e: Exception) {
                            TvLogger.e(TvLogger.TAG_GENERAL, "Frame processing error: ${e.message}")
                        } finally {
                            isCompressing.set(false)
                        }
                    }
                } else {
                    image.close()
                }
            }, null)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MoteTvMirrorDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            _isMirroring.value = true
            updateNotification("Live Mirror Active: ${_webUrl.value}")
            TvLogger.i(TvLogger.TAG_GENERAL, "Screen Mirroring active at ${_streamUrl.value}")
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_GENERAL, "Failed to start screen mirroring: ${e.message}")
            stopMirroring()
            stopSelf()
        }
    }

    private fun stopMirroring() {
        _isMirroring.value = false
        _streamUrl.value = ""
        _webUrl.value = ""
        _currentFps.value = 0
        _activeViewers.value = 0

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        mirrorServer?.stop()
        mirrorServer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMirroring()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MoteTV Screen Mirroring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification displayed while mirroring screen to TV"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenMirrorService::class.java).apply {
            action = ACTION_STOP_MIRROR
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MoteTV Screen Mirroring")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Mirror", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
