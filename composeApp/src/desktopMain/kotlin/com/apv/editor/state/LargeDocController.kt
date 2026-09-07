package com.apv.editor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apv.editor.largefile.LargeDocument
import com.apv.editor.largefile.LineCol

/**
 * Thin Compose-observable wrapper around a [LargeDocument]. The document itself knows nothing
 * about Compose's snapshot system (see its own doc comment) — this is what lets
 * [com.apv.editor.ui.LargeEditorPane]'s line rows and the tab bar's dirty marker actually
 * recompose when something changes. It also adds line-oriented undo/redo: a whole [LargeDocument]
 * is far too big to snapshot per edit the way [TabState]'s small-file undo stack snapshots the
 * entire `TextFieldValue`, so instead each edit's inverse (what text was removed, what was
 * inserted, and where) is recorded and replayed.
 */
class LargeDocController(val doc: LargeDocument) {
    /**
     * Bumped on every edit. Visible line rows in [com.apv.editor.ui.LargeEditorPane] key their
     * content lookup off (lineIndex, revision) so they re-fetch fresh content only when something
     * actually changed, instead of on every recomposition.
     */
    var revision by mutableStateOf(0)
        private set

    var lineCount by mutableStateOf(doc.lineCount)
        private set

    var isDirty by mutableStateOf(doc.isDirty)
        private set

    /**
     * The line touched by the most recent [edit]/[undo]/[redo] call. Plain (non-Compose-observed)
     * state — read by [com.apv.editor.ui.HighlightStateCache] to invalidate only from this point
     * forward instead of re-deriving cross-line syntax-highlight state from the top of the
     * document on every keystroke.
     */
    var lastEditedLine: Int = 0
        private set

    /**
     * See [LargeDocument.maxKnownLineLength]. Plain (non-Compose-observed) state, same as
     * [lastEditedLine] — read once by [com.apv.editor.ui.LargeEditorPane] at doc-open time to seed
     * its horizontal-scroll-extent estimate, not observed reactively thereafter.
     */
    val maxKnownLineLength: Int get() = doc.maxKnownLineLength

    private val undoStack = ArrayDeque<EditRecord>()
    private val redoStack = ArrayDeque<EditRecord>()
    private var lastEditTime = 0L

    private data class EditRecord(val from: LineCol, val removedText: String, val insertedText: String)

    fun getLine(index: Int): String = doc.getLine(index)

    /** Text between [from] and [to] — used for cross-line copy/cut in [com.apv.editor.ui.LargeEditorPane]. */
    fun textInRange(from: LineCol, to: LineCol): String = extractText(from, to)

    /**
     * Replaces [from]..[to] with [replacement] and returns the caret position right after the
     * inserted text. This is the one primitive every large-file edit goes through — a same-line
     * keystroke, Enter (replacement contains "\n"), Backspace/Delete across a line boundary
     * (replacement is ""), and multi-line paste all reduce to one call here.
     */
    fun edit(from: LineCol, to: LineCol, replacement: String): LineCol {
        val removed = extractText(from, to)
        recordUndo(EditRecord(from, removed, replacement))
        return applyLocked(from, to, replacement)
    }

    fun undo(): LineCol? {
        val rec = undoStack.removeLastOrNull() ?: return null
        val insertedEnd = endOfInsert(rec.from, rec.insertedText)
        val caret = applyLocked(rec.from, insertedEnd, rec.removedText)
        redoStack.addLast(rec)
        while (redoStack.size > MAX_HISTORY) redoStack.removeFirst()
        return caret
    }

    fun redo(): LineCol? {
        val rec = redoStack.removeLastOrNull() ?: return null
        val removedEnd = endOfInsert(rec.from, rec.removedText)
        val caret = applyLocked(rec.from, removedEnd, rec.insertedText)
        undoStack.addLast(rec)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        return caret
    }

    fun markSaved() {
        doc.markSaved()
        isDirty = false
    }

    private fun applyLocked(from: LineCol, to: LineCol, replacement: String): LineCol {
        lastEditedLine = from.line
        val caret = doc.replaceRange(from, to, replacement)
        lineCount = doc.lineCount
        isDirty = true
        revision++
        return caret
    }

    /** Merges runs of plain forward typing into one undo step, mirroring [TabState]'s own coalescing for the small-file path. */
    private fun recordUndo(rec: EditRecord) {
        redoStack.clear()
        val now = System.currentTimeMillis()
        val top = undoStack.lastOrNull()
        val coalesced = top != null &&
            top.removedText.isEmpty() &&
            rec.removedText.isEmpty() &&
            rec.insertedText.length == 1 &&
            !rec.insertedText.contains('\n') &&
            (now - lastEditTime) < COALESCE_MS &&
            endOfInsert(top.from, top.insertedText) == rec.from
        lastEditTime = now
        if (coalesced && top != null) {
            undoStack.removeLast()
            undoStack.addLast(top.copy(insertedText = top.insertedText + rec.insertedText))
        } else {
            undoStack.addLast(rec)
            while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        }
    }

    private fun extractText(from: LineCol, to: LineCol): String {
        if (from.line == to.line) return doc.getLine(from.line).substring(from.col, to.col)
        val sb = StringBuilder()
        sb.append(doc.getLine(from.line).substring(from.col))
        for (l in from.line + 1 until to.line) {
            sb.append('\n')
            sb.append(doc.getLine(l))
        }
        sb.append('\n')
        sb.append(doc.getLine(to.line).substring(0, to.col))
        return sb.toString()
    }

    /** Where [text] (inserted starting at [from]) ends. Only its first line inherits [from]'s column — every line after an embedded '\n' starts fresh at column 0. */
    private fun endOfInsert(from: LineCol, text: String): LineCol {
        val lastNewline = text.lastIndexOf('\n')
        return if (lastNewline < 0) {
            LineCol(from.line, from.col + text.length)
        } else {
            val newlineCount = text.count { it == '\n' }
            LineCol(from.line + newlineCount, text.length - lastNewline - 1)
        }
    }

    companion object {
        private const val MAX_HISTORY = 100
        private const val COALESCE_MS = 500L
    }
}
