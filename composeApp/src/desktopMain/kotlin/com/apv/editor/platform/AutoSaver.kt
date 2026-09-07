package com.apv.editor.platform

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Debounced, per-key delayed-action scheduler backing [AutosaveMode.AfterDelay]. The delay is
 * passed per call (from that mode's own `delayMs`) rather than fixed at construction, so changing
 * [com.apv.editor.state.EditorState.autosaveMode]'s delay at runtime doesn't need a new scheduler.
 */
class AutoSaver {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "autosave-thread").also { it.isDaemon = true }
    }

    private val pending = ConcurrentHashMap<Long, ScheduledFuture<*>>()

    /**
     * Debounce per [key]. A new schedule under the same key cancels its prior
     * pending action; schedules for different keys are independent, so concurrent
     * edits in multiple panes/tabs do not interfere with one another.
     */
    fun schedule(key: Long, delayMs: Long, action: () -> Unit) {
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

    /** Cancels [key]'s pending delayed autosave, if any, without running it. */
    fun cancel(key: Long) {
        pending.remove(key)?.cancel(false)
    }

    fun shutdown() {
        scheduler.shutdownNow()
    }
}
