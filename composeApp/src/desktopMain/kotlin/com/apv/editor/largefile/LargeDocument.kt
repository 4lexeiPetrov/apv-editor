package com.apv.editor.largefile

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Arrays

/** A logical (line, column) position within a [LargeDocument]. [col] is a UTF-16 char offset into that line's content. */
data class LineCol(val line: Int, val col: Int)

/**
 * One contiguous run of logical lines making up part of a [LargeDocument].
 *  - [Original] lines haven't been touched since the document was opened (or last saved) and are
 *    read on demand, straight off disk, via [LargeDocument] — nothing about them is held in memory.
 *  - [Edited] lines are held directly as Strings because something about that run has changed
 *    (typed, pasted, split, merged, or newly created).
 */
private sealed class Chunk {
    abstract val lineCount: Int
    class Original(val startLine: Int, val endLineExclusive: Int) : Chunk() {
        override val lineCount: Int get() = endLineExclusive - startLine
    }
    class Edited(val lines: MutableList<String>) : Chunk() {
        override val lineCount: Int get() = lines.size
    }
}

private val NEWLINE_BYTE: Byte = '\n'.code.toByte()

/**
 * Line-addressable, edit-in-place view over a file too large to hold as a single in-memory
 * String/TextFieldValue (see [com.apv.editor.state.LargeFileGuard]'s doc comment for the *layout*
 * crash this exists to avoid — that ceiling is about Compose measuring one giant text block, not
 * about how this class stores bytes). Backs [com.apv.editor.ui.LargeEditorPane]'s LazyColumn-based
 * virtualized editing surface — only lines actually asked for (the visible window, plus a little
 * overscan) are ever measured or laid out, regardless of how [readSourceLine] gets their bytes.
 *
 * [readSourceLine] prefers [sourceBytes]: the *entire* source file loaded into one in-memory
 * buffer, up front, whenever it's under [MAX_IN_MEMORY_BYTES] — which every file large enough to
 * even reach this class via [com.apv.editor.state.LargeFileGuard]'s (much lower, visual-row-based)
 * threshold normally is. This is the same trade Zed's editor makes: keep the buffer resident so
 * scrolling is pure in-memory slicing with no disk I/O on the hot path at all, rather than
 * streaming lines from disk on demand and papering over the resulting stalls with caching. Only a
 * source file that's *itself* too big for that budget falls back to seeking into [raf] per line —
 * still correct, just no longer the common case, and still the safety net that keeps a
 * multi-gigabyte file from being forced into memory.
 *
 * Line semantics deliberately match Kotlin's `String.split("\n")` exactly (so behavior lines up
 * with the existing small-file path, which just holds the raw file text): a file ending in '\n'
 * has an extra empty last line, an empty file is one empty line, etc. [writeTo] reproduces the
 * source exactly under this model — join lines with "\n", nothing more.
 *
 * Internally a document is an ordered list of [Chunk]s. Editing splits/splices chunks around the
 * touched lines rather than ever rewriting the whole thing — [sourceLineStarts] locates any given
 * Original line's bytes (in [sourceBytes] or, on the disk-fallback path, in [raf]) without needing
 * to touch anything outside the edited region. Adjacent edited chunks are coalesced back together
 * so ordinary typing (many edits to the same handful of lines) doesn't grow the chunk list without
 * bound.
 *
 * Every mutating call and [writeTo] hold an internal lock, so concurrent calls (e.g. a UI-thread
 * edit racing a background-thread autosave, or the prefetch read-ahead in
 * [com.apv.editor.ui.LargeEditorPane] racing either of those) can't corrupt internal state —
 * callers can still observe a save landing between two of their own reads, same as the existing
 * small-file path.
 */
class LargeDocument private constructor(
    initialFile: File,
    private var raf: RandomAccessFile,
    private var sourceLineStarts: LongArray,
    private var sourceFileLength: Long,
    private val charset: Charset,
    initialMaxLineLength: Int = 0,
    initialSourceBytes: ByteArray? = null,
) {
    private val lock = Any()

    /** See the class doc. Null means the source file exceeded [MAX_IN_MEMORY_BYTES]; [readSourceLine] falls back to [raf]. */
    private var sourceBytes: ByteArray? = initialSourceBytes

    var file: File = initialFile
        private set

    private val chunks = mutableListOf<Chunk>(Chunk.Original(0, sourceLineStarts.size))

    /** Fast path for the overwhelmingly common case: several edits in a row to the same line. */
    private var cachedChunkIndex = -1
    private var cachedChunkStartLine = -1

    /**
     * Bounded read-through cache for [readSourceLine]'s disk-fallback path only — irrelevant
     * while [sourceBytes] is resident (the common case; see the class doc), since decoding
     * straight out of an in-memory buffer needs no cache. For a source file too big for that
     * budget, this is what the earlier fix for touchpad-scroll stuttering relied on before
     * [sourceBytes] existed: without it, every newly-visible row in
     * [com.apv.editor.ui.LargeEditorPane]'s LazyColumn did a synchronous `raf.seek` +
     * `raf.readFully` *on the UI thread*, once per row, every time that row scrolled into view —
     * including re-scrolling back over an already-visited stretch, and the LazyColumn's own
     * overscan re-requesting rows near the visible edge as it shifts by one. Caching by
     * [sourceLine] is safe indefinitely — that index's bytes in the *current* source file never
     * change (edits move a line into a [Chunk.Edited] instead of mutating this one) — until
     * [writeTo] repoints [raf] at a rewritten file, at which point every old sourceLine index
     * means something else and this is cleared. Capped well above a comfortable scroll window so
     * memory use stays trivial even on the fallback path through a huge file, rather than caching
     * the whole thing.
     */
    private val sourceLineCache = object : LinkedHashMap<Int, String>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>): Boolean =
            size > SOURCE_LINE_CACHE_CAP
    }

    var isDirty: Boolean = false
        private set

    var lineCount: Int = sourceLineStarts.size
        private set

    /**
     * An upper bound, in characters, on the length of the longest line currently in the
     * document — not necessarily exact. Seeded from [open]'s single streaming pass over the raw
     * file, which counts UTF-8 *bytes* per line rather than decoding: since a UTF-8 character is
     * always at least one byte, byte count is always >= char count, so this only ever
     * over-estimates non-ASCII lines, never under-estimates — the same safe direction
     * [com.apv.editor.state.LargeFileGuard] leans on elsewhere. [replaceRange] keeps it updated
     * from real (exact) [String.length] values as edits come in, but only ever grows it: if the
     * single longest line is later deleted, this stays at its old (now stale-high) value rather
     * than re-scanning every remaining line to find a new true maximum. Consumed by
     * [com.apv.editor.ui.LargeEditorPane] to seed its horizontal-scroll-extent estimate before any
     * row has actually been measured.
     */
    var maxKnownLineLength: Int = initialMaxLineLength
        private set

    fun getLine(index: Int): String = synchronized(lock) { getLineLocked(index) }

    private fun getLineLocked(index: Int): String {
        require(index in 0 until lineCount) { "line $index out of range (0..<$lineCount)" }
        val (chunk, startLine) = locate(index)
        return when (chunk) {
            is Chunk.Edited -> chunk.lines[index - startLine]
            is Chunk.Original -> readSourceLine(chunk.startLine + (index - startLine))
        }
    }

    /** Replaces the whole content of one line — the common case for in-place typing within a line. */
    fun setLine(index: Int, newText: String) {
        val len = getLine(index).length
        replaceRange(LineCol(index, 0), LineCol(index, len), newText)
    }

    /**
     * Replaces everything between [from] and [to] (from must not be after to, in document order)
     * with [replacement]. This is the single primitive every edit in the large-file editor funnels
     * through: a same-line keystroke, Enter (splits a line — [replacement] contains "\n"),
     * Backspace/Delete across a line boundary ([replacement] is ""), and multi-line paste or
     * selection-replace all reduce to one call. Returns where the caret should land afterwards.
     */
    fun replaceRange(from: LineCol, to: LineCol, replacement: String): LineCol = synchronized(lock) {
        val start = normalizeLocked(from)
        val end = normalizeLocked(to)
        require(start.line < end.line || (start.line == end.line && start.col <= end.col)) {
            "from ($from) must not be after to ($to)"
        }
        val beforeLine = getLineLocked(start.line)
        val afterLine = getLineLocked(end.line)
        val before = beforeLine.substring(0, start.col.coerceIn(0, beforeLine.length))
        val after = afterLine.substring(end.col.coerceIn(0, afterLine.length))
        val combined = before + replacement + after
        val newLines = combined.split("\n")
        val longestNewLine = newLines.maxOf { it.length }
        if (longestNewLine > maxKnownLineLength) maxKnownLineLength = longestNewLine

        spliceLines(start.line, end.line, newLines)
        isDirty = true

        val caretLine = start.line + newLines.size - 1
        val caretCol = (newLines.last().length - after.length).coerceAtLeast(0)
        LineCol(caretLine, caretCol)
    }

    private fun normalizeLocked(pos: LineCol): LineCol {
        val line = pos.line.coerceIn(0, lineCount - 1)
        val len = getLineLocked(line).length
        return LineCol(line, pos.col.coerceIn(0, len))
    }

    /** Splices the chunk(s) covering logical lines out, replacing them with [newContents]. */
    private fun spliceLines(fromLine: Int, toLineInclusive: Int, newContents: List<String>) {
        // Fast path: the whole affected range sits inside the last-touched Edited chunk, so we
        // can mutate it in place with no chunk-list scan or restructuring at all.
        val cachedIdx = cachedChunkIndex
        if (cachedIdx in chunks.indices) {
            val chunk = chunks[cachedIdx]
            val start = cachedChunkStartLine
            val end = start + chunk.lineCount
            if (chunk is Chunk.Edited && fromLine >= start && toLineInclusive < end) {
                val localFrom = fromLine - start
                val localTo = toLineInclusive - start
                repeat(localTo - localFrom + 1) { chunk.lines.removeAt(localFrom) }
                chunk.lines.addAll(localFrom, newContents)
                lineCount += newContents.size - (toLineInclusive - fromLine + 1)
                return
            }
        }

        val splitAtStart = chunkBoundaryIndex(fromLine)
        val splitAtEnd = chunkBoundaryIndex(toLineInclusive + 1)
        val removed = chunks.subList(splitAtStart, splitAtEnd)
        removed.clear()
        chunks.add(splitAtStart, Chunk.Edited(newContents.toMutableList()))

        lineCount += newContents.size - (toLineInclusive - fromLine + 1)
        coalesceAround(splitAtStart)
    }

    /**
     * Ensures a chunk boundary exists exactly at logical line [line] (splitting whichever chunk
     * currently straddles it, if any), and returns the index of the chunk that now starts there.
     * [line] == chunks-spanned-line-count (i.e. the very end) is valid.
     */
    private fun chunkBoundaryIndex(line: Int): Int {
        if (line == 0) return 0
        var offset = 0
        for (i in chunks.indices) {
            val chunk = chunks[i]
            val start = offset
            val end = offset + chunk.lineCount
            if (line == start) return i
            if (line in (start + 1) until end) {
                val local = line - start
                val (left, right) = splitChunk(chunk, local)
                chunks[i] = left
                chunks.add(i + 1, right)
                return i + 1
            }
            offset = end
        }
        return chunks.size
    }

    private fun splitChunk(chunk: Chunk, local: Int): Pair<Chunk, Chunk> = when (chunk) {
        is Chunk.Original -> Chunk.Original(chunk.startLine, chunk.startLine + local) to
            Chunk.Original(chunk.startLine + local, chunk.endLineExclusive)
        is Chunk.Edited -> {
            val left = ArrayList(chunk.lines.subList(0, local))
            val right = ArrayList(chunk.lines.subList(local, chunk.lines.size))
            Chunk.Edited(left) to Chunk.Edited(right)
        }
    }

    /** Merges chunk [index] with its Edited neighbours (if any) so the chunk list stays small under ordinary typing. */
    private fun coalesceAround(index: Int) {
        var i = index
        val cur = chunks.getOrNull(i) as? Chunk.Edited
        if (cur == null) {
            cachedChunkIndex = -1
            cachedChunkStartLine = -1
            return
        }
        val next = chunks.getOrNull(i + 1)
        if (next is Chunk.Edited) {
            cur.lines.addAll(next.lines)
            chunks.removeAt(i + 1)
        }
        if (i > 0) {
            val prev = chunks[i - 1]
            if (prev is Chunk.Edited) {
                prev.lines.addAll(cur.lines)
                chunks.removeAt(i)
                i -= 1
            }
        }
        var offset = 0
        for (j in 0 until i) offset += chunks[j].lineCount
        cachedChunkIndex = i
        cachedChunkStartLine = offset
    }

    private fun locate(index: Int): Pair<Chunk, Int> {
        val cachedIdx = cachedChunkIndex
        if (cachedIdx in chunks.indices) {
            val chunk = chunks[cachedIdx]
            val start = cachedChunkStartLine
            if (index in start until start + chunk.lineCount) return chunk to start
        }
        var offset = 0
        for (i in chunks.indices) {
            val chunk = chunks[i]
            if (index < offset + chunk.lineCount) {
                cachedChunkIndex = i
                cachedChunkStartLine = offset
                return chunk to offset
            }
            offset += chunk.lineCount
        }
        error("line $index not found in $lineCount-line document ($offset lines spanned by ${chunks.size} chunks)")
    }

    private fun readSourceLine(sourceLine: Int): String {
        val start = sourceLineStarts[sourceLine]
        val isLast = sourceLine == sourceLineStarts.size - 1
        val rawEnd = if (isLast) sourceFileLength else sourceLineStarts[sourceLine + 1]
        // Every non-final entry in sourceLineStarts points one past a '\n' — the byte right
        // before the next entry (or EOF, for the final one) is always that separator, except for
        // the file's own last line, whose range genuinely has none to strip.
        val sliceEnd = if (isLast) rawEnd else rawEnd - 1
        val len = (sliceEnd - start).toInt().coerceAtLeast(0)

        val resident = sourceBytes
        if (resident != null) {
            // Common case (see the class doc): decode straight out of the in-memory buffer. No
            // disk I/O, so no benefit from sourceLineCache here — go straight to the String.
            return if (len > 0) String(resident, start.toInt(), len, charset) else ""
        }

        // Fallback path: source file was too big to hold resident (see MAX_IN_MEMORY_BYTES).
        // sourceLineCache still earns its keep here — see its own doc comment.
        sourceLineCache[sourceLine]?.let { return it }
        val bytes = ByteArray(len)
        if (len > 0) {
            raf.seek(start)
            raf.readFully(bytes)
        }
        val line = String(bytes, charset)
        sourceLineCache[sourceLine] = line
        return line
    }

    fun markSaved() {
        isDirty = false
    }

    fun close() {
        try { raf.close() } catch (_: Throwable) {}
    }

    /**
     * Streams the whole document to [target] (sibling temp file + atomic rename, so a crash or a
     * full disk mid-write can't corrupt [target]). Untouched (Original) runs are byte-copied
     * straight from the current source file — never decoded/re-encoded — so anything the user
     * never touched round-trips exactly, encoding quirks and all. On success the document
     * collapses back to a single Original chunk over the freshly written file: everything is
     * "unmodified" again relative to what's now on disk, which also naturally bounds the chunk
     * list across repeated saves instead of letting it grow forever.
     */
    fun writeTo(target: File) {
        synchronized(lock) {
            val tmp = File(target.absoluteFile.parentFile, ".${target.name}.apv-tmp-${System.nanoTime()}")
            val newStarts = LongArrayBuilder(lineCount.coerceAtLeast(16))
            var pos = 0L
            var lineIndex = 0
            var scratch = ByteArray(4096)
            BufferedOutputStream(FileOutputStream(tmp)).use { out ->
                for (chunk in chunks) {
                    when (chunk) {
                        is Chunk.Original -> {
                            for (i in chunk.startLine until chunk.endLineExclusive) {
                                newStarts.add(pos)
                                val start = sourceLineStarts[i]
                                val isSourceLast = i == sourceLineStarts.size - 1
                                val rawEnd = if (isSourceLast) sourceFileLength else sourceLineStarts[i + 1]
                                val sliceEnd = if (isSourceLast) rawEnd else rawEnd - 1
                                val copyLen = (sliceEnd - start).toInt().coerceAtLeast(0)
                                if (copyLen > scratch.size) scratch = ByteArray(copyLen)
                                if (copyLen > 0) {
                                    raf.seek(start)
                                    raf.readFully(scratch, 0, copyLen)
                                    out.write(scratch, 0, copyLen)
                                }
                                pos += copyLen
                                lineIndex++
                                if (lineIndex < lineCount) {
                                    out.write('\n'.code)
                                    pos += 1
                                }
                            }
                        }
                        is Chunk.Edited -> {
                            for (line in chunk.lines) {
                                newStarts.add(pos)
                                val bytes = line.toByteArray(charset)
                                out.write(bytes)
                                pos += bytes.size
                                lineIndex++
                                if (lineIndex < lineCount) {
                                    out.write('\n'.code)
                                    pos += 1
                                }
                            }
                        }
                    }
                }
            }
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
            raf.close()
            raf = RandomAccessFile(target, "r")
            sourceLineStarts = newStarts.toLongArray()
            sourceFileLength = pos
            file = target
            // sourceLine indices are only meaningful relative to the raf/sourceLineStarts pair
            // that was current when they were cached — both just changed. Re-load the resident
            // buffer against the freshly-written file too (or drop it, past the size budget) —
            // otherwise every post-save read would silently fall back to disk forever.
            sourceLineCache.clear()
            sourceBytes = loadFullyIfWithinBudget(raf, pos)
            chunks.clear()
            chunks.add(Chunk.Original(0, sourceLineStarts.size))
            cachedChunkIndex = -1
            cachedChunkStartLine = -1
            lineCount = sourceLineStarts.size
            isDirty = false
        }
    }

    companion object {
        /** Cap on [sourceLineCache]'s size — see its doc comment. */
        private const val SOURCE_LINE_CACHE_CAP = 20_000

        /**
         * Upper bound, in bytes, on a source file [LargeDocument] will hold fully resident in
         * memory (see the class doc and [loadFullyIfWithinBudget]) rather than streaming from
         * disk per line. 512 MiB comfortably covers every file that actually needs
         * [com.apv.editor.ui.LargeEditorPane] in the first place — [com.apv.editor.state.LargeFileGuard]
         * routes files here well before their raw byte size gets anywhere close to this (its
         * threshold is visual *row* count, typically tripped in the low thousands of lines) —
         * while still refusing to gamble an unbounded chunk of heap on a source file that's
         * unusually large for reasons other than line count (e.g. one truly enormous line, or a
         * file near [MAX_INDEXABLE_LINES]).
         */
        private const val MAX_IN_MEMORY_BYTES = 512L * 1024 * 1024

        /**
         * Refuse to index a file with more lines than this. Keeps the line-offset index (8
         * bytes/line — ~240MB at this ceiling) and every Int-indexed use of it (Compose
         * LazyColumn item counts included) comfortably within sane memory/addressing limits. See
         * [com.apv.editor.state.LargeFileGuard] for where this is enforced before opening.
         */
        const val MAX_INDEXABLE_LINES = 30_000_000

        /**
         * Streams the file once to build a byte-offset line index, tracking the longest line seen
         * (in raw UTF-8 bytes) along the way, at negligible extra cost, to seed
         * [maxKnownLineLength] — see its doc comment for why bytes stand in for characters here.
         * Also copies each chunk it reads into [resident] — see the class doc — as long as the
         * file is within [MAX_IN_MEMORY_BYTES], so opening a file costs exactly one pass over it
         * either way, whether or not it ends up resident.
         */
        fun open(file: File, charset: Charset = Charsets.UTF_8): LargeDocument {
            val raf = RandomAccessFile(file, "r")
            val length = raf.length()
            val starts = LongArrayBuilder(1024)
            starts.add(0)
            // Filled in alongside the scan below (one arraycopy per chunk already being read
            // regardless) rather than with a second pass over the file afterwards — see the class
            // doc for why holding this resident is worth doing at all, and loadFullyIfWithinBudget
            // (used by writeTo, where a second pass is fine — saves are far rarer than opens) for
            // why this file's own copy stops short of MAX_IN_MEMORY_BYTES.
            val resident: ByteArray? = if (length in 0L..MAX_IN_MEMORY_BYTES) {
                try {
                    ByteArray(length.toInt())
                } catch (_: OutOfMemoryError) {
                    null
                }
            } else {
                null
            }
            val buf = ByteArray(1 shl 20)
            var pos = 0L
            var currentLineBytes = 0L
            var maxLineBytes = 0L
            raf.seek(0)
            while (true) {
                val read = raf.read(buf)
                if (read <= 0) break
                resident?.let { System.arraycopy(buf, 0, it, pos.toInt(), read) }
                for (i in 0 until read) {
                    if (buf[i] == NEWLINE_BYTE) {
                        starts.add(pos + i + 1)
                        if (starts.size > MAX_INDEXABLE_LINES) {
                            raf.close()
                            error("File exceeds $MAX_INDEXABLE_LINES lines — too large to open even in large-file mode")
                        }
                        if (currentLineBytes > maxLineBytes) maxLineBytes = currentLineBytes
                        currentLineBytes = 0
                    } else {
                        currentLineBytes++
                    }
                }
                pos += read
            }
            if (currentLineBytes > maxLineBytes) maxLineBytes = currentLineBytes
            val maxLineLength = maxLineBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return LargeDocument(file, raf, starts.toLongArray(), length, charset, maxLineLength, resident)
        }

        /**
         * [writeTo]'s equivalent of the residency check inlined into [open]'s own scan: reads
         * [source] fully into one [ByteArray] if [length] is within [MAX_IN_MEMORY_BYTES], else
         * returns null (the caller keeps reading from [source] on demand instead) — see the class
         * doc. A plain second pass here rather than threading the same trick through [writeTo]'s
         * copy loop: that loop already does real work per byte (streaming Original chunks from
         * [raf] to the new file, encoding Edited ones), and saves happen far less often than
         * opens, so one more sequential read of a file small enough to buffer at all is cheap by
         * comparison. Caught OutOfMemoryError falls back to disk streaming rather than taking the
         * app down — a generous but not exhaustive budget can still collide with a
         * memory-constrained JVM.
         */
        private fun loadFullyIfWithinBudget(source: RandomAccessFile, length: Long): ByteArray? {
            if (length !in 0L..MAX_IN_MEMORY_BYTES) return null
            return try {
                val bytes = ByteArray(length.toInt())
                source.seek(0)
                source.readFully(bytes)
                bytes
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }
}

/** Minimal growable long array — avoids boxing a MutableList<Long> for a structure sized to the document's line count. */
private class LongArrayBuilder(initialCapacity: Int) {
    private var array = LongArray(initialCapacity.coerceAtLeast(16))
    var size = 0
        private set

    fun add(value: Long) {
        if (size == array.size) array = Arrays.copyOf(array, array.size * 2)
        array[size++] = value
    }

    fun toLongArray(): LongArray = Arrays.copyOf(array, size)
}
