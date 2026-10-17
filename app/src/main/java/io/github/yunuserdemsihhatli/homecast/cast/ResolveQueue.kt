package io.github.yunuserdemsihhatli.homecast.cast

/** Schedules [task] after [delayMs]; the returned function cancels it. */
internal fun interface Scheduler {
    fun postDelayed(
        delayMs: Long,
        task: () -> Unit,
    ): () -> Unit
}

/**
 * Runs asynchronous resolves strictly one at a time (NsdManager allows a single active resolve
 * below API 34). Failed resolves are retried up to [maxRetries] times after [retryDelayMs]; a resolve
 * that never reports back is abandoned after [watchdogMs] so the queue cannot stall.
 */
internal class ResolveQueue<T : Any>(
    private val scheduler: Scheduler,
    private val resolve: (item: T, done: (success: Boolean) -> Unit) -> Unit,
    private val maxRetries: Int = 2,
    private val retryDelayMs: Long = 500,
    private val watchdogMs: Long = 8_000,
) {
    private class Entry<T>(
        val item: T,
    ) {
        var attempts = 0
        var cancelled = false
    }

    private class Attempt<T>(
        val entry: Entry<T>,
        var cancelWatchdog: () -> Unit = {},
    )

    private val lock = Any()
    private val pending = ArrayDeque<Entry<T>>()
    private val live = mutableSetOf<Entry<T>>()
    private var current: Attempt<T>? = null
    private var closed = false

    fun enqueue(item: T) {
        synchronized(lock) {
            if (closed) return
            val entry = Entry(item)
            live += entry
            pending.addLast(entry)
        }
        pump()
    }

    /** Drops queued, in-flight (result ignored on retry) and waiting-to-retry items matching [predicate]. */
    fun remove(predicate: (T) -> Boolean) {
        synchronized(lock) {
            live.filter { predicate(it.item) }.forEach {
                it.cancelled = true
                live -= it
                pending.remove(it)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            current?.cancelWatchdog?.invoke()
            current = null
            pending.clear()
            live.clear()
        }
    }

    private fun pump() {
        val attempt: Attempt<T>
        synchronized(lock) {
            if (closed || current != null) return
            val entry = pending.removeFirstOrNull() ?: return
            attempt = Attempt(entry)
            current = attempt
            attempt.cancelWatchdog = scheduler.postDelayed(watchdogMs) { finish(attempt, false) }
        }
        try {
            resolve(attempt.entry.item) { success -> finish(attempt, success) }
        } catch (_: Exception) {
            finish(attempt, false)
        }
    }

    private fun finish(
        attempt: Attempt<T>,
        success: Boolean,
    ) {
        synchronized(lock) {
            if (current !== attempt) return // stale: watchdog or callback already handled it
            attempt.cancelWatchdog()
            current = null
            val entry = attempt.entry
            if (success || entry.cancelled) {
                live -= entry
            } else if (entry.attempts < maxRetries) {
                entry.attempts++
                scheduler.postDelayed(retryDelayMs) {
                    synchronized(lock) {
                        if (!closed && !entry.cancelled) pending.addLast(entry)
                    }
                    pump()
                }
            } else {
                live -= entry
            }
        }
        pump()
    }
}
