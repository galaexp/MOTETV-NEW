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
    private val maxCapacity: Int = MAX_QUEUE_CAPACITY
) {
    companion object {
        const val MAX_QUEUE_CAPACITY = 25
    }

    private val queue = ArrayDeque<QueuedRemoteCommand>()
    private val lock = Any()

    val size: Int
        get() = synchronized(lock) {
            purgeExpiredInternal()
            queue.size
        }

    /**
     * Enqueues a command. Discards expired entries and evicts the oldest entry if full.
     */
    fun enqueue(command: QueuedRemoteCommand): Boolean = synchronized(lock) {
        purgeExpiredInternal()

        if (command is QueuedRemoteCommand.KeyCommand) {
            when (command.direction) {
                Direction.END_LONG -> {
                    // Check if a matching START_LONG is still in queue
                    val hasStart = queue.any {
                        it is QueuedRemoteCommand.KeyCommand &&
                                it.keyCode == command.keyCode &&
                                it.direction == Direction.START_LONG
                    }
                    if (!hasStart) {
                        // Orphaned END_LONG with no queued START_LONG; don't queue
                        return false
                    }
                }
                Direction.START_LONG -> {
                    // Remove any older unfulfilled START_LONG for the same key
                    queue.removeAll {
                        it is QueuedRemoteCommand.KeyCommand &&
                                it.keyCode == command.keyCode &&
                                it.direction == Direction.START_LONG
                    }
                }
                else -> { /* SHORT is normal */ }
            }
        }

        while (queue.size >= maxCapacity) {
            queue.removeFirstOrNull()
        }
        queue.addLast(command)
        return true
    }

    /**
     * Retrieves and removes all unexpired commands in their original order.
     */
    fun drainAllValid(): List<QueuedRemoteCommand> = synchronized(lock) {
        purgeExpiredInternal()
        val list = ArrayList<QueuedRemoteCommand>(queue)
        queue.clear()
        list
    }

    /**
     * Clears all commands.
     */
    fun clear() = synchronized(lock) {
        queue.clear()
    }

    private fun purgeExpiredInternal() {
        val now = System.currentTimeMillis()
        queue.removeAll { (now - it.createdAtMs) > it.maxAgeMs }
    }
}
