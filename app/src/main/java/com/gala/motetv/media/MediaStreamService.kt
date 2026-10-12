package com.gala.motetv.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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

class MediaStreamService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var mediaServer: LocalMediaServer

    private val _streamingState = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
    val streamingState: StateFlow<CastSessionState> = _streamingState.asStateFlow()

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "motetv_media_stream_channel"

        const val ACTION_START_CAST = "com.gala.motetv.action.START_CAST"
        const val ACTION_STOP_CAST = "com.gala.motetv.action.STOP_CAST"

        const val EXTRA_URI = "extra_uri"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_FILE_SIZE = "extra_file_size"
        const val EXTRA_MIME_TYPE = "extra_mime_type"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun startStreaming(
            context: Context,
            mediaInfo: SelectedMediaInfo
        ) {
            val intent = Intent(context, MediaStreamService::class.java).apply {
                action = ACTION_START_CAST
                putExtra(EXTRA_URI, mediaInfo.uri)
                putExtra(EXTRA_FILE_NAME, mediaInfo.name)
                putExtra(EXTRA_FILE_SIZE, mediaInfo.size)
                putExtra(EXTRA_MIME_TYPE, mediaInfo.mimeType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopStreaming(context: Context) {
            val intent = Intent(context, MediaStreamService::class.java).apply {
                action = ACTION_STOP_CAST
            }
            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaStreamService = this@MediaStreamService
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        mediaServer = LocalMediaServer(applicationContext)
        createNotificationChannel()
        TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Service onCreate")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CAST -> {
                TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Received ACTION_STOP_CAST")
                stopCastingSession()
                stopSelf()
            }
            ACTION_START_CAST -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_URI)
                }
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "media"
                val fileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
                val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"

                if (uri != null) {
                    val mediaInfo = SelectedMediaInfo(
                        uri = uri,
                        name = fileName,
                        size = fileSize,
                        mimeType = mimeType
                    )
                    startCastingSession(mediaInfo)
                } else {
                    TvLogger.e(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] URI was null in ACTION_START_CAST")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCastingSession(mediaInfo: SelectedMediaInfo) {
        val notification = buildNotification(mediaInfo.name, "Starting local HTTP streaming...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // targetSdk 36 requires specifying foregroundServiceType
                // We use FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            TvLogger.e(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] startForeground failed: ${e.message}", e)
            _streamingState.value = CastSessionState.Error("Foreground service startup failed: ${e.message}")
            releaseLocks()
            stopSelf()
            return
        }

        // Acquire Locks
        acquireLocks()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val streamUrl = mediaServer.startStreaming(
                    uri = mediaInfo.uri,
                    fileName = mediaInfo.name,
                    fileSize = mediaInfo.size,
                    mimeType = mediaInfo.mimeType
                )

                if (streamUrl.isBlank()) {
                    val errMsg = "Failed to bind streaming port or open file."
                    _streamingState.value = CastSessionState.Error(errMsg)
                    updateNotification(mediaInfo.name, "Streaming failed: $errMsg")
                    releaseLocks()
                    stopSelf()
                    return@launch
                }

                _streamingState.value = CastSessionState.Streaming(
                    media = mediaInfo,
                    streamUrl = streamUrl,
                    isPlaying = true,
                    statusMessage = "Streaming to TV via VLC ($streamUrl)"
                )
                updateNotification(mediaInfo.name, "Playing on TV via VLC ($streamUrl)")
                TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Server active at $streamUrl with Partial WakeLock and WifiLock held")
            } catch (e: Exception) {
                TvLogger.e(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Exception in startCastingSession: ${e.message}", e)
                _streamingState.value = CastSessionState.Error(e.message ?: "Failed to start streaming")
                releaseLocks()
                stopSelf()
            }
        }
    }

    private fun stopCastingSession() {
        mediaServer.stopStreaming()
        releaseLocks()
        _streamingState.value = CastSessionState.Idle
        TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Streaming session stopped and locks released")
    }

    @Synchronized
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoteTV:MediaCastingWakeLock")?.apply {
                    setReferenceCounted(false)
                    // Bounded timeout: 8 hours maximum to protect battery in edge cases
                    acquire(8 * 60 * 60 * 1000L)
                    TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] PARTIAL_WAKE_LOCK acquired")
                }
            }
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Failed to acquire WakeLock: ${e.message}")
        }

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                // Use WIFI_MODE_FULL_HIGH_PERF (or fallback to WIFI_MODE_FULL).
                // Note on API 29+ deprecation: WIFI_MODE_FULL_LOW_LATENCY is only active while the screen
                // is ON and the app is foregrounded according to Android documentation.
                // In contrast, WIFI_MODE_FULL_HIGH_PERF is explicitly documented to maintain high-performance
                // Wi-Fi operation with minimum packet loss even when the device screen is off.
                // On modern Android releases where WIFI_MODE_FULL_HIGH_PERF is deprecated, the platform
                // gracefully maps it to low power dissipation while keeping lock semantics active.
                @Suppress("DEPRECATION")
                val lockType = WifiManager.WIFI_MODE_FULL_HIGH_PERF

                wifiLock = wm?.createWifiLock(lockType, "MoteTV:MediaCastingWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                    TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] WifiLock acquired (mode=FULL_HIGH_PERF)")
                }
            }
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Failed to acquire WifiLock: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] PARTIAL_WAKE_LOCK released")
                }
            }
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Error releasing WakeLock: ${e.message}")
        } finally {
            wakeLock = null
        }

        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] WifiLock released")
                }
            }
        } catch (e: Exception) {
            TvLogger.w(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Error releasing WifiLock: ${e.message}")
        } finally {
            wifiLock = null
        }
    }

    private fun buildNotification(fileName: String, statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingLaunch = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaStreamService::class.java).apply {
            action = ACTION_STOP_CAST
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Casting to TV: $fileName")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingLaunch)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Casting", pendingStop)
            .build()
    }

    private fun updateNotification(fileName: String, statusText: String) {
        val notification = buildNotification(fileName, statusText)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "MoteTV Media Casting"
            val descriptionText = "Displays status while streaming media files to Android TV"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopCastingSession()
        serviceScope.cancel()
        TvLogger.i(TvLogger.TAG_GENERAL, "[MEDIA_STREAM_SERVICE] Service onDestroy")
    }
}
