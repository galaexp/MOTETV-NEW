package com.gala.motetv.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mirrorServer: ScreenMirrorServer? = null

    private var displayListener: DisplayManager.DisplayListener? = null
    private var displayManager: DisplayManager? = null

    private val isCompressing = AtomicBoolean(false)
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var lastFrameTimestamp = 0L

    // Reusable buffers to minimize per-frame allocations and eliminate GC pressure
    private var cachedBitmap: Bitmap? = null
    private var cachedCroppedBitmap: Bitmap? = null
    private val reusableOutputStream = ByteArrayOutputStream(128 * 1024)

    // Configurable quality & rate limiting
    @Volatile
    var targetFps: Int = 30

    @Volatile
    var jpegQuality: Int = 75

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

        private val _statusMessage = MutableStateFlow("Ready")
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            TvLogger.i(TvLogger.TAG_GENERAL, "MediaProjection stopped by Android system or user revocation")
            mainHandler.post {
                stopMirroring()
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        setupDisplayListener()
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
                    // For Android 14+ (API 34+), the foreground service must be started with
                    // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION BEFORE calling getMediaProjection.
                    startForegroundInternal("Starting Screen Mirroring...")
                    startMirroring(resultCode, data)
                } else {
                    TvLogger.w(TvLogger.TAG_GENERAL, "ScreenMirrorService received invalid result code or null intent data")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundInternal(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMirroring(resultCode: Int, data: Intent) {
        try {
            // 1. Start HTTP MJPEG Server with secure token
            val server = ScreenMirrorServer(8088)
            val started = server.start()
            if (!started) {
                _statusMessage.value = "Failed to bind streaming server"
                TvLogger.e(TvLogger.TAG_GENERAL, "Unable to bind ScreenMirrorServer")
                stopMirroring()
                stopSelf()
                return
            }
            mirrorServer = server

            val stream = server.getStreamUrl()
            val web = server.getWebUrl()

            if (stream == null || web == null) {
                _statusMessage.value = "No local Wi-Fi IP address detected"
                TvLogger.w(TvLogger.TAG_GENERAL, "No Wi-Fi address available for streaming to TV")
            } else {
                _streamUrl.value = stream
                _webUrl.value = web
                _statusMessage.value = "Server ready"
            }

            // 2. Obtain MediaProjection (one-time consent token)
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                _statusMessage.value = "Failed to obtain media projection"
                stopMirroring()
                stopSelf()
                return
            }
            mediaProjection = projection

            // Critical Android 14 requirement: Register Callback BEFORE createVirtualDisplay()
            projection.registerCallback(projectionCallback, mainHandler)

            // 3. Compute display metrics & allocate surface
            val metrics = resources.displayMetrics
            val width = 720
            val aspectRatio = metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()
            val height = (720f * aspectRatio).toInt().coerceAtLeast(480)
            val density = metrics.densityDpi

            isMirroringActive.set(true)
            createCapturePipeline(width, height, density)

            _isMirroring.value = true
            updateNotification("Live Mirror Active: ${_webUrl.value.ifEmpty { "Waiting for Wi-Fi" }}")
            TvLogger.i(TvLogger.TAG_GENERAL, "Screen Mirroring capture pipeline established successfully")
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_GENERAL, "Failed to start screen mirroring: ${e.message}", e)
            _statusMessage.value = "Error: ${e.message}"
            stopMirroring()
            stopSelf()
        }
    }

    private val pipelineLock = Any()
    private val isMirroringActive = AtomicBoolean(false)

    private fun createCapturePipeline(width: Int, height: Int, density: Int) {
        synchronized(pipelineLock) {
            val proj = mediaProjection ?: return
            val oldReader = imageReader

            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
                setOnImageAvailableListener({ reader ->
                    val image = try {
                        reader.acquireLatestImage()
                    } catch (e: Exception) {
                        null
                    } ?: return@setOnImageAvailableListener

                    // Battery & CPU optimization: Skip compression entirely when no TV or client is viewing
                    val viewers = mirrorServer?.connectedClientsCount?.get() ?: 0
                    _activeViewers.value = viewers
                    if (viewers <= 0) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    // Framerate throttle
                    val now = System.currentTimeMillis()
                    val minIntervalMs = 1000L / targetFps.coerceIn(5, 60)
                    if (now - lastFrameTimestamp < minIntervalMs) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    // Discard frame if previous compression is still in progress (bounded work queue)
                    if (!isCompressing.compareAndSet(false, true)) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    serviceScope.launch {
                        try {
                            lastFrameTimestamp = System.currentTimeMillis()
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bufferWidth = width + rowPadding / pixelStride

                            // Reuse cached bitmap if dimensions match
                            var bmp = cachedBitmap
                            if (bmp == null || bmp.isRecycled || bmp.width != bufferWidth || bmp.height != height) {
                                bmp?.recycle()
                                bmp = Bitmap.createBitmap(bufferWidth, height, Bitmap.Config.ARGB_8888)
                                cachedBitmap = bmp
                            }

                            bmp.copyPixelsFromBuffer(buffer)
                            image.close() // Close image immediately after copying pixels

                            val finalBitmap = if (rowPadding > 0) {
                                var cropped = cachedCroppedBitmap
                                if (cropped == null || cropped.isRecycled || cropped.width != width || cropped.height != height) {
                                    cropped?.recycle()
                                    cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                                    cachedCroppedBitmap = cropped
                                } else {
                                    val canvas = android.graphics.Canvas(cropped)
                                    val srcRect = android.graphics.Rect(0, 0, width, height)
                                    val dstRect = android.graphics.Rect(0, 0, width, height)
                                    canvas.drawBitmap(bmp, srcRect, dstRect, null)
                                }
                                cropped
                            } else {
                                bmp
                            }

                            reusableOutputStream.reset()
                            finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, reusableOutputStream)
                            val jpegBytes = reusableOutputStream.toByteArray()
                            mirrorServer?.onNewFrame(jpegBytes)

                            frameCount++
                            val currentTimestamp = System.currentTimeMillis()
                            if (currentTimestamp - lastFpsTimestamp >= 1000) {
                                _currentFps.value = frameCount
                                frameCount = 0
                                lastFpsTimestamp = currentTimestamp
                            }
                        } catch (e: Exception) {
                            TvLogger.e(TvLogger.TAG_GENERAL, "Frame processing error: ${e.message}")
                        } finally {
                            isCompressing.set(false)
                        }
                    }
                }, null)
            }

            imageReader = newReader

            if (virtualDisplay == null) {
                virtualDisplay = proj.createVirtualDisplay(
                    "MoteTvMirrorDisplay",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newReader.surface,
                    null,
                    mainHandler
                )
            } else {
                virtualDisplay?.resize(width, height, density)
                virtualDisplay?.setSurface(newReader.surface)
            }

            try {
                oldReader?.close()
            } catch (_: Exception) {}
        }
    }

    private fun setupDisplayListener() {
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (!isMirroringActive.get() || mediaProjection == null) return
                // Handle device rotation or display resize
                val metrics = resources.displayMetrics
                val width = 720
                val aspectRatio = metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()
                val height = (720f * aspectRatio).toInt().coerceAtLeast(480)
                val density = metrics.densityDpi

                TvLogger.i(TvLogger.TAG_GENERAL, "Display configuration changed: ${width}x${height} ($density dpi)")
                createCapturePipeline(width, height, density)
            }
        }
        displayManager?.registerDisplayListener(displayListener, mainHandler)
    }

    private fun stopMirroring() {
        if (!isMirroringActive.getAndSet(false)) return
        _isMirroring.value = false
        _streamUrl.value = ""
        _webUrl.value = ""
        _currentFps.value = 0
        _activeViewers.value = 0
        _statusMessage.value = "Stopped"

        synchronized(pipelineLock) {
            try {
                mediaProjection?.unregisterCallback(projectionCallback)
            } catch (_: Exception) {}

            try {
                virtualDisplay?.release()
            } catch (_: Exception) {}
            virtualDisplay = null

            try {
                imageReader?.close()
            } catch (_: Exception) {}
            imageReader = null

            try {
                mediaProjection?.stop()
            } catch (_: Exception) {}
            mediaProjection = null

            try {
                mirrorServer?.stop()
            } catch (_: Exception) {}
            mirrorServer = null

            cachedBitmap?.recycle()
            cachedBitmap = null
            cachedCroppedBitmap?.recycle()
            cachedCroppedBitmap = null
            reusableOutputStream.reset()
        }

        TvLogger.i(TvLogger.TAG_GENERAL, "Screen Mirroring resources released cleanly")
    }

    override fun onDestroy() {
        super.onDestroy()
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
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
            .setContentTitle("MoteTV Screen Mirroring Active")
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
