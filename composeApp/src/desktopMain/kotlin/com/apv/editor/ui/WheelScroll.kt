@file:OptIn(ExperimentalComposeUiApi::class)

package com.apv.editor.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The editor's one wheel/trackpad handler: takes every scroll event for the area it is applied to
 * and moves either the horizontal or the vertical position, never both in the same gesture.
 *
 * Why not leave it to the nested `verticalScroll`/`horizontalScroll` (or a `LazyColumn` with a
 * horizontal `scrollable` per row): Compose's wheel handling gives each event to the innermost
 * scrollable first. A vertical trackpad swipe always carries a little sideways movement, and once
 * the horizontal scroller had room to move both ways (scrolled right) it took that movement, then
 * stayed "in progress" for 50 ms and swallowed the vertical events that followed — the vertical
 * scroller received a fraction of the swipe, late, which showed as lag and jumps.
 *
 * Events are taken on the Initial pass (parent before child) and consumed, so the scrollables
 * underneath never see them; they stay in place for layout, clipping, the scrollbars and
 * bring-caret-into-view. The callbacks get a delta in pixels (positive = towards the end) and
 * return how much of it they used, like `ScrollableState.dispatchRawDelta`.
 */
internal fun Modifier.axisLockedWheelScroll(
    vararg keys: Any?,
    scrollHorizontally: (Float) -> Float,
    scrollVertically: (Float) -> Float,
): Modifier = pointerInput(*keys) {
    coroutineScope {
        val lock = WheelAxisLock()
        val minStepPx = SmoothMinStep.toPx()
        val horizontal = WheelAxisScroller(this, scrollHorizontally, minStepPx)
        val vertical = WheelAxisScroller(this, scrollVertically, minStepPx)
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                if (event.changes.any { it.isConsumed }) continue
                val delta = wheelDeltaPx(event, size)
                event.changes.forEach { it.consume() }
                if (delta == Offset.Zero) continue
                val precise = isPreciseWheel(event)
                if (lock.isHorizontal(delta, event.changes.first().uptimeMillis)) {
                    vertical.stop()
                    horizontal.scroll(delta.x, precise)
                } else {
                    horizontal.stop()
                    vertical.scroll(delta.y, precise)
                }
            }
        }
    }
}

/**
 * Decides which axis the current gesture belongs to. Keeps a short, decaying total of the movement
 * seen on each axis; the gesture stays on its axis until the other one clearly outweighs it, so
 * sideways jitter in a vertical swipe (and the reverse) is dropped while a deliberate change of
 * direction still gets through within a few events. A pause longer than [GESTURE_GAP_MS] starts over.
 */
private class WheelAxisLock {
    private var lastMillis = 0L
    private var sumX = 0f
    private var sumY = 0f
    private var horizontal = false

    fun isHorizontal(delta: Offset, uptimeMillis: Long): Boolean {
        val elapsed = uptimeMillis - lastMillis
        lastMillis = uptimeMillis
        if (elapsed < 0 || elapsed > GESTURE_GAP_MS) {
            sumX = 0f
            sumY = 0f
            horizontal = abs(delta.x) > abs(delta.y)
        } else {
            val keep = exp(-elapsed / DECAY_MS)
            sumX *= keep
            sumY *= keep
        }
        sumX += abs(delta.x)
        sumY += abs(delta.y)
        if (horizontal) {
            if (sumY > sumX * SWITCH_RATIO) horizontal = false
        } else {
            if (sumX > sumY * SWITCH_RATIO) horizontal = true
        }
        return horizontal
    }
}

/**
 * Applies one axis's deltas. Trackpads and free-spinning wheels report many small deltas and are
 * applied as they arrive; a notched wheel reports a few large ones, which are spread over the next
 * frames so a notch glides instead of jumping (what Compose's own wheel handling does).
 */
private class WheelAxisScroller(
    private val scope: CoroutineScope,
    private val apply: (Float) -> Float,
    private val minStepPx: Float,
) {
    private var pending = 0f
    private var job: Job? = null

    fun stop() {
        pending = 0f
    }

    fun scroll(deltaPx: Float, precise: Boolean) {
        if (deltaPx == 0f) return
        if (precise) {
            pending = 0f
            apply(deltaPx)
            return
        }
        pending += deltaPx
        if (job?.isActive == true) return
        job = scope.launch {
            while (abs(pending) >= 0.5f) {
                val step = if (abs(pending) <= minStepPx) {
                    pending
                } else {
                    sign(pending) * max(minStepPx, abs(pending) * SMOOTH_FRACTION)
                }
                pending -= step
                val used = apply(step)
                if (abs(step - used) > 0.5f) {
                    // Reached the start or the end: drop what is left instead of spinning on it.
                    pending = 0f
                    break
                }
                withFrameNanos { }
            }
            pending = 0f
        }
    }
}

/** The event's delta in pixels, positive towards the end; same per-OS scale Compose's own scrolling uses. */
private fun Density.wheelDeltaPx(event: PointerEvent, bounds: IntSize): Offset {
    val raw = event.changes.fold(Offset.Zero) { acc, change -> acc + change.scrollDelta }
    val awt = event.awtEventOrNull as? MouseWheelEvent
    val amount = awt?.scrollAmount?.toFloat() ?: 1f
    val width = bounds.width.toFloat()
    val height = bounds.height.toFloat()
    val unitX: Float
    val unitY: Float
    when {
        awt?.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL -> { unitX = width; unitY = height }
        Platform.isMac -> { unitX = 10.dp.toPx(); unitY = unitX }
        IsLinux -> { unitX = sqrt(width); unitY = sqrt(height) }
        else -> { unitX = width / 20f; unitY = height / 20f }
    }
    return Offset(raw.x * unitX * amount, raw.y * unitY * amount)
}

/** True for a trackpad or a free-spinning wheel (fractional rotation); Windows always reports false, as in Compose. */
private fun isPreciseWheel(event: PointerEvent): Boolean {
    if (IsWindows) return false
    val awt = event.awtEventOrNull as? MouseWheelEvent ?: return false
    return abs(awt.preciseWheelRotation - awt.wheelRotation.toDouble()) > 0.001
}

private val OsName = System.getProperty("os.name").orEmpty().lowercase()
private val IsWindows = OsName.contains("win")
private val IsLinux = OsName.contains("linux") || OsName.contains("nux")

private const val GESTURE_GAP_MS = 200L
private const val DECAY_MS = 120f
private const val SWITCH_RATIO = 2f
private const val SMOOTH_FRACTION = 0.3f
private val SmoothMinStep = 6.dp
