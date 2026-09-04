package com.apv.editor.platform

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AutoSaver(private val delayMs: Long = 1500) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "autosave-thread").also { it.isDaemon = true }
    }

    private val pending = ConcurrentHashMap<Long, ScheduledFuture<*>>()

    /**
     * Debounce per [key]. A new schedule under the same key cancels its prior
     * pending action; schedules for different keys are independent, so concurrent
     * edits in multiple panes/tabs do not interfere with one another.
     */
    fun schedule(key: Long, action: () -> Unit) {
        pending.remove(key)?.cancel(false)
        val future = scheduler.schedule(
            {
                try {
                    action()
                } finally {
                    pending.remove(key)
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        pending[key] = future
    }

    fun shutdown() {
        scheduler.shutdownNow()
    }
}
