package com.gala.motetv.core.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object TvLogger {
    const val TAG_DISCOVERY = "Discovery"
    const val TAG_TLS = "TLS"
    const val TAG_PAIR = "PAIR"
    const val TAG_PROTO = "PROTO"
    const val TAG_REMOTE = "REMOTE"
    const val TAG_GENERAL = "General"

    private const val MAX_LOG_ENTRIES = 200

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: String,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            return "[$time] [MoteTV][$tag] $level: $message"
        }
    }

    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    var protocolLoggingEnabled: Boolean = false

    fun d(tag: String, message: String) {
        if (protocolLoggingEnabled || tag != TAG_PROTO) {
            safeLog { Log.d("MoteTV-$tag", message) }
            appendEntry("DEBUG", tag, message)
        }
    }

    fun i(tag: String, message: String) {
        safeLog { Log.i("MoteTV-$tag", message) }
        appendEntry("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        safeLog { Log.w("MoteTV-$tag", message) }
        appendEntry("WARN", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            safeLog { Log.e("MoteTV-$tag", message, throwable) }
            appendEntry("ERROR", tag, "$message (${throwable.message})")
        } else {
            safeLog { Log.e("MoteTV-$tag", message) }
            appendEntry("ERROR", tag, message)
        }
    }

    private inline fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Android Log not available on JVM unit test runner without Robolectric
        }
    }

    private fun appendEntry(level: String, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        logBuffer.add(entry)
        while (logBuffer.size > MAX_LOG_ENTRIES) {
            logBuffer.poll()
        }
        _logsFlow.value = logBuffer.toList()
    }

    fun getLogs(): List<LogEntry> = logBuffer.toList()

    fun clearLogs() {
        logBuffer.clear()
        _logsFlow.value = emptyList()
    }
}
