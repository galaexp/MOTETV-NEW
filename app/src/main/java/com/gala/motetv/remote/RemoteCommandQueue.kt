package com.gala.motetv.remote

import com.google.android.apps.tv.remote.protocol.Direction
import java.util.concurrent.atomic.AtomicLong

sealed class QueuedRemoteCommand(
    open val createdAtMs: Long = System.currentTimeMillis(),
    open val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    open val commandId: Long = nextId()
) {
    val isExpired: Boolean
        get() = (System.currentTimeMillis() - createdAtMs) > maxAgeMs

    data class KeyCommand(
        val keyCode: Int,
        val direction: Direction,
        override val createdAtMs: Long = System.currentTimeMillis(),
        override val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        override val commandId: Long = nextId()
    ) : QueuedRemoteCommand(createdAtMs, maxAgeMs, commandId)

    data class ImeCommand(
        val text: String,
        override val createdAtMs: Long = System.currentTimeMillis(),
        override val maxAgeMs: Long = 5000L,
        override val commandId: Long = nextId()
    ) : QueuedRemoteCommand(createdAtMs, maxAgeMs, commandId)

    data class AppLinkCommand(
        val appLink: String,
        override val createdAtMs: Long = System.currentTimeMillis(),
        override val maxAgeMs: Long = 6000L,
        override val commandId: Long = nextId()
    ) : QueuedRemoteCommand(createdAtMs, maxAgeMs, commandId)

    companion object {
        const val DEFAULT_MAX_AGE_MS = 3500L
        private val counter = AtomicLong(0)
        fun nextId(): Long = counter.incrementAndGet()
    }
}

/**
 * Thread-safe, bounded, and ordered command queue for remote interactions.
 * Applies short expiration times and handles long-press repeat deduplication.
 */
class RemoteCommandQueue(
    private val maxCapacity: Int = MAX_QUEUE_CAPACITY,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val MAX_QUEUE_CAPACITY = 25
    }

    private val queue = ArrayDeque<QueuedRemoteCommand>()
    private val lock = Any()

    private var _evictedCount = 0
    val evictedCount: Int
        get() = synchronized(lock) { _evictedCount }

    private var _expiredCount = 0
    val expiredCount: Int
        get() = synchronized(lock) { _expiredCount }

    private var _lastExpirationTimeMs = 0L
    val lastExpirationTimeMs: Long
        get() = synchronized(lock) { _lastExpirationTimeMs }

    val size: Int
        get() = synchronized(lock) {
            purgeExpiredInternal()
            queue.size
        }

    fun isCommandExpired(command: QueuedRemoteCommand): Boolean {
        return (timeProvider() - command.createdAtMs) > command.maxAgeMs
    }

    /**
     * Enqueues a command. Discards expired entries and evicts the oldest entry if full.
     * Long-press START_LONG commands replace earlier unreleased START_LONG commands for the same key.
     * END_LONG commands are preserved over other commands during eviction so key releases are not lost.
     */
    fun enqueue(command: QueuedRemoteCommand): Boolean = synchronized(lock) {
        purgeExpiredInternal()

        if (command is QueuedRemoteCommand.KeyCommand && command.direction == Direction.START_LONG) {
            queue.removeAll {
                it is QueuedRemoteCommand.KeyCommand &&
                    it.keyCode == command.keyCode &&
                    it.direction == Direction.START_LONG
            }
        }

        while (queue.size >= maxCapacity) {
            val evictIndex = queue.indexOfFirst {
                !(it is QueuedRemoteCommand.KeyCommand && it.direction == Direction.END_LONG)
            }
            if (evictIndex >= 0) {
                queue.removeAt(evictIndex)
            } else {
                queue.removeFirstOrNull()
            }
            _evictedCount++
        }
        queue.addLast(command)
        return true
    }

    /**
     * Peeks at the next eligible, non-expired command without removing it from the queue.
     * Returns null if queue is empty or all commands are expired.
     */
    fun peekNextValid(): QueuedRemoteCommand? = synchronized(lock) {
        purgeExpiredInternal()
        return queue.firstOrNull()
    }

    /**
     * Removes the specific command from the head of the queue after confirmed transmission.
     */
    fun removeHead(command: QueuedRemoteCommand): Boolean = synchronized(lock) {
        if (queue.isNotEmpty() && queue.first().commandId == command.commandId) {
            queue.removeFirst()
            return true
        }
        val idx = queue.indexOfFirst { it.commandId == command.commandId }
        if (idx >= 0) {
            queue.removeAt(idx)
            return true
        }
        return false
    }

    /**
     * Retrieves all unexpired commands currently in queue without clearing.
     */
    fun getAllValid(): List<QueuedRemoteCommand> = synchronized(lock) {
        purgeExpiredInternal()
        ArrayList(queue)
    }

    /**
     * Clears all commands.
     */
    fun clear() = synchronized(lock) {
        queue.clear()
    }

    private fun purgeExpiredInternal() {
        val now = timeProvider()
        val it = queue.iterator()
        while (it.hasNext()) {
            val cmd = it.next()
            if ((now - cmd.createdAtMs) > cmd.maxAgeMs) {
                it.remove()
                _expiredCount++
                _lastExpirationTimeMs = now
            }
        }
    }
}
