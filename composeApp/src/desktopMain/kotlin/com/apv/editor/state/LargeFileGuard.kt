package com.apv.editor.state

import java.io.File

/**
 * Jetpack Compose UI's [androidx.compose.ui.unit.Constraints] packs each dimension of a layout
 * constraint into 18 bits. Any measured width or height at or above 262_143px can't be encoded,
 * and Compose throws "Can't represent a width of W and height of H in Constraints()" deep inside
 * the measure pass — a crash application code has no way to catch.
 *
 * [com.apv.editor.ui.EditorPane] renders the whole document in a single `BasicTextField` that's
 * allowed to grow to its full content height (wrapped in a `verticalScroll` Box, so the field
 * itself does the scrolling), so a document whose rendered height would cross that ceiling
 * brings the app down as soon as it's laid out — there is currently no line-level virtualization
 * to fall back on.
 *
 * This object estimates that risk from a document's line count *before* the text ever reaches
 * [TabState.loadFrom], so opening (or restoring) an oversized file can be refused gracefully
 * instead of crashing. It only guards against the common case (many lines) that caused the
 * observed crash; it does not protect against a small number of pathologically long single
 * lines, or against zooming the font way up / shrinking the window on an already-borderline
 * file after it's open. Fixing those fully means paginating or virtualizing EditorPane itself.
 */
object LargeFileGuard {
    /** Compose's Constraints bit-packing ceiling per dimension (2^18 - 1, with one value reserved for "infinity"). */
    private const val MAX_CONSTRAINT_PX = 262_142

    /** Retina Macs commonly render at 2x; assuming this (rather than 1x) keeps the estimate safe on HiDPI displays. */
    private const val ASSUMED_DENSITY = 2.0

    /** Only budget a fraction of the theoretical ceiling, to leave headroom for word-wrapped lines. */
    private const val SAFETY_FACTOR = 0.6

    /** The largest font size the zoom shortcuts allow (see Main.kt's Cmd+/Cmd- handlers). */
    const val MAX_FONT_SIZE = 48

    /** The most lines a document can have at [fontSize] without risking the Constraints crash on layout. */
    fun maxSafeLines(fontSize: Int): Long {
        val lineHeightPx = fontSize.coerceAtLeast(1) * 1.4 * ASSUMED_DENSITY
        return ((MAX_CONSTRAINT_PX * SAFETY_FACTOR) / lineHeightPx).toLong().coerceAtLeast(1)
    }

    fun isOversized(lineCount: Long, fontSize: Int): Boolean = lineCount > maxSafeLines(fontSize)

    fun isOversized(text: String, fontSize: Int): Boolean = isOversized(countLines(text), fontSize)

    /** Streams the file rather than loading it fully, so the check itself stays cheap even for huge files. */
    fun isOversized(file: File, fontSize: Int): Boolean = isOversized(countLines(file), fontSize)

    fun countLines(text: String): Long {
        if (text.isEmpty()) return 1
        var count = 1L
        for (ch in text) if (ch == '\n') count++
        return count
    }

    fun countLines(file: File): Long = try {
        file.bufferedReader().use { reader ->
            var count = 1L
            val buf = CharArray(8192)
            while (true) {
                val read = reader.read(buf)
                if (read < 0) break
                for (i in 0 until read) if (buf[i] == '\n') count++
            }
            count
        }
    } catch (_: Throwable) {
        // Unreadable/missing — let the normal open flow surface the real error instead of blocking here.
        0L
    }
}
