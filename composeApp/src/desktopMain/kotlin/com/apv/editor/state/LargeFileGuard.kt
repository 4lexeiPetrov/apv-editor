package com.apv.editor.state

import com.apv.editor.largefile.LargeDocument
import java.io.File

/**
 * Jetpack Compose UI's [androidx.compose.ui.unit.Constraints] packs each dimension of a layout
 * constraint into 18 bits. Any measured width or height at or above 262_143px can't be encoded,
 * and Compose throws "Can't represent a width of W and height of H in Constraints()" deep inside
 * the measure pass — a crash application code has no way to catch.
 *
 * The original (small-file) [com.apv.editor.ui.EditorPane] renders the whole document in a
 * single `BasicTextField` that's allowed to grow to its full content height (wrapped in a
 * `verticalScroll` Box, so the field itself does the scrolling), so a document whose rendered
 * height would cross that ceiling brings the app down as soon as it's laid out. Crucially, that
 * field soft-wraps: a line longer than the window is wide renders as several visual rows, so the
 * relevant quantity isn't the file's raw line count but how many visual rows it wraps into — a
 * file with a modest number of lines can still crash if enough of those lines are very long (a
 * data export with wide rows, a minified bundle, a single-line JSON blob). See
 * [estimatedWrappedRows] for how that's approximated.
 *
 * This object estimates that risk from a document's content *before* the text ever reaches
 * [TabState.loadFrom] — [classify] is what [EditorState] uses to route a file to the
 * LazyColumn-backed [com.apv.editor.ui.LargeEditorPane] (via [TabState.loadLargeFrom]) instead,
 * which only ever measures the visible window of lines (and never soft-wraps them) and so isn't
 * subject to this ceiling at all. Only a genuinely unworkable file — one whose line count is so
 * large that even the [LargeDocument] line-offset index would be unreasonably big, see
 * [LargeDocument.MAX_INDEXABLE_LINES] — is still refused outright.
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

    /**
     * Average monospace glyph advance width as a fraction of the font's em size. Real monospace
     * fonts vary a little around this, but it's in the right ballpark and — being on the narrow
     * side — only ever *over*-estimates how many characters fit per row, which in turn only ever
     * over-estimates wrapping. See [estimatedWrappedRows].
     */
    private const val ASSUMED_CHAR_WIDTH_EM = 0.62

    /**
     * A deliberately narrow assumed editor content width (in density-independent dp, i.e. before
     * the density multiplier applied below), used only to estimate how many rows a long line
     * wraps into in the plain editor. The user's real window is usually wider than this, so this
     * only ever over-counts wrapped rows — never under-counts them — which is the safe direction
     * for a check whose whole job is to keep [com.apv.editor.ui.EditorPane] under the Constraints
     * ceiling. It isn't a guarantee for an arbitrarily narrow window: someone who resizes their
     * editor pane narrower than this after opening a borderline file can still, in principle, wrap
     * their way past the ceiling — this closes the common case (opening a file with long lines),
     * not every conceivable one.
     */
    private const val ASSUMED_MIN_CONTENT_WIDTH_DP = 900.0

    enum class FileSizeClass {
        /** Safe for the plain single-`BasicTextField` editor. */
        NORMAL,

        /** Would risk the Constraints crash in the plain editor; opened via [com.apv.editor.ui.LargeEditorPane] instead. */
        LARGE,

        /** Beyond even what [LargeDocument] can reasonably index; refused (see [EditorState.pendingOversizedFile]). */
        UNSUPPORTED,
    }

    /** The most *visual rows* (after word-wrap) a document can occupy at [fontSize] without risking the Constraints crash on layout. */
    fun maxSafeLines(fontSize: Int): Long {
        val lineHeightPx = fontSize.coerceAtLeast(1) * 1.4 * ASSUMED_DENSITY
        return ((MAX_CONSTRAINT_PX * SAFETY_FACTOR) / lineHeightPx).toLong().coerceAtLeast(1)
    }

    /**
     * How many monospace characters are assumed to fit on one visual row at [fontSize] before the
     * plain editor wraps to the next one. Density cancels out of this ratio (both the assumed
     * content width and the glyph width scale with it), so this is expressed purely in terms of
     * [ASSUMED_MIN_CONTENT_WIDTH_DP] and the font's em size.
     */
    private fun charsPerWrappedRow(fontSize: Int): Int {
        val emSize = fontSize.coerceAtLeast(1).toDouble()
        return (ASSUMED_MIN_CONTENT_WIDTH_DP / (emSize * ASSUMED_CHAR_WIDTH_EM)).toInt().coerceAtLeast(1)
    }

    /** How many wrapped visual rows one line of [lineLength] characters is assumed to occupy. Always at least 1, even for an empty line. */
    private fun estimatedWrappedRows(lineLength: Int, charsPerRow: Int): Long {
        if (lineLength <= 0) return 1L
        return ((lineLength.toLong() + charsPerRow - 1) / charsPerRow)
    }

    /** Raw-line-count check — doesn't know individual line lengths, so it can't account for wrapping. Prefer the [File]/[String] overloads, which do. */
    fun isOversized(lineCount: Long, fontSize: Int): Boolean = lineCount > maxSafeLines(fontSize)

    /**
     * Wrap-aware: accounts for the plain editor soft-wrapping long lines to the window's width, so
     * a small number of very long lines can trip this even when [text]'s raw line count is tiny.
     * See the class doc comment and [ASSUMED_MIN_CONTENT_WIDTH_DP].
     */
    fun isOversized(text: String, fontSize: Int): Boolean {
        val charsPerRow = charsPerWrappedRow(fontSize)
        val limit = maxSafeLines(fontSize)
        var visualRows = 0L
        var lineStart = 0
        var i = 0
        val n = text.length
        while (i <= n) {
            if (i == n || text[i] == '\n') {
                visualRows += estimatedWrappedRows(i - lineStart, charsPerRow)
                if (visualRows > limit) return true
                lineStart = i + 1
            }
            i++
        }
        return visualRows > limit
    }

    /** Streams the file rather than loading it fully, so the check itself stays cheap even for huge files. */
    fun isOversized(file: File, fontSize: Int): Boolean = scan(file, fontSize) == FileSizeClass.LARGE

    /**
     * Classifies [file] by streaming it once — never loading its content — so this is cheap to
     * call even for a multi-gigabyte file. [EditorState.newTab]/[EditorState.openFile] use this to
     * decide which editing surface to open the file in.
     */
    fun classify(file: File, fontSize: Int): FileSizeClass = scan(file, fontSize)

    /**
     * Single streaming pass that both counts raw lines (for the [LargeDocument.MAX_INDEXABLE_LINES]
     * check) and estimates wrapped visual rows (for the Constraints-crash check), so classifying a
     * file only ever costs one read pass over it regardless of which check ends up mattering.
     * Bails out as soon as either check's answer is settled, so a file that's obviously huge or
     * obviously full of very long lines doesn't need to be read to the end to know it.
     */
    private fun scan(file: File, fontSize: Int): FileSizeClass {
        val charsPerRow = charsPerWrappedRow(fontSize)
        val wrapLimit = maxSafeLines(fontSize)
        // Once a single line alone would wrap past wrapLimit, the file is LARGE regardless of
        // anything else in it — this lets a pathological giant single line (no newlines at all)
        // bail out without having to stream the rest of a potentially huge file just to confirm it.
        val maxCharsBeforeLineAloneIsLarge = wrapLimit * charsPerRow
        return try {
            file.bufferedReader().use { reader ->
                var rawLines = 0L
                var visualRows = 0L
                var currentLineLength = 0
                var sawAnyChar = false
                val buf = CharArray(8192)
                while (true) {
                    val read = reader.read(buf)
                    if (read < 0) break
                    for (i in 0 until read) {
                        sawAnyChar = true
                        if (buf[i] == '\n') {
                            rawLines++
                            if (rawLines > LargeDocument.MAX_INDEXABLE_LINES) return FileSizeClass.UNSUPPORTED
                            visualRows += estimatedWrappedRows(currentLineLength, charsPerRow)
                            if (visualRows > wrapLimit) return FileSizeClass.LARGE
                            currentLineLength = 0
                        } else {
                            currentLineLength++
                            if (currentLineLength > maxCharsBeforeLineAloneIsLarge) return FileSizeClass.LARGE
                        }
                    }
                }
                if (!sawAnyChar) return FileSizeClass.NORMAL // empty file
                rawLines++ // the final (possibly unterminated) line, matching String.split("\n") semantics
                if (rawLines > LargeDocument.MAX_INDEXABLE_LINES) return FileSizeClass.UNSUPPORTED
                visualRows += estimatedWrappedRows(currentLineLength, charsPerRow)
                if (visualRows > wrapLimit) FileSizeClass.LARGE else FileSizeClass.NORMAL
            }
        } catch (_: Throwable) {
            // Unreadable/missing — let the normal open flow surface the real error instead of blocking here.
            FileSizeClass.NORMAL
        }
    }

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
