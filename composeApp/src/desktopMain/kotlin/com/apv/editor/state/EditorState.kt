package com.apv.editor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.apv.editor.largefile.LargeDocument
import com.apv.editor.largefile.LineCol
import com.apv.editor.platform.AutoSaver
import com.apv.editor.platform.AutosaveMode
import com.apv.editor.session.PaneSnapshot
import com.apv.editor.session.SessionSnapshot
import com.apv.editor.session.SessionWrite
import com.apv.editor.session.TabSnapshot
import com.apv.editor.session.UnsavedSnapshot
import com.apv.editor.session.WindowSnapshot
import com.apv.editor.syntax.SyntaxDetector
import com.apv.editor.syntax.SyntaxLanguage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** How a Markdown tab is shown: source only, source beside the rendered preview, or preview only. */
enum class PreviewMode { Edit, Split, Preview }

class TabState(
    initialFile: File? = null,
    initialText: String = "",
) {
    val id: Long = nextId.getAndIncrement()

    var file by mutableStateOf<File?>(initialFile)
    var textField by mutableStateOf(TextFieldValue(initialText))
    var savedText by mutableStateOf(initialText)
    var language by mutableStateOf(
        if (initialFile != null) SyntaxDetector.detect(initialFile.name) else SyntaxLanguage.NONE
    )
    var previewMode by mutableStateOf(PreviewMode.Edit)

    /** True whenever the rendered preview is visible at all. Kept for existing call sites and old session files. */
    var showPreview: Boolean
        get() = previewMode != PreviewMode.Edit
        set(value) { previewMode = if (value) PreviewMode.Split else PreviewMode.Edit }

    /** The N in "Untitled N" while this tab has no file; 0 when it was created for a file. */
    var untitledIndex by mutableStateOf(if (initialFile == null) nextUntitled.getAndIncrement() else 0)

    // Find bar state (small-file tabs only; see com.apv.editor.ui.FindBar).
    var findOpen by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var findCaseSensitive by mutableStateOf(false)
    var findRegex by mutableStateOf(false)
    var findMatches by mutableStateOf<List<IntRange>>(emptyList())
    var findIndex by mutableStateOf(0)
    /** Bumped to ask the editor to scroll the current match into view. */
    var findRevealTick by mutableStateOf(0)
    /** Bumped to ask the find field to take keyboard focus (Cmd/Ctrl+F while already open). */
    var findFocusTick by mutableStateOf(0)
    /** Bumped to ask the editor text field to take keyboard focus back. */
    var editorFocusTick by mutableStateOf(0)

    /** Last-modified time of [file] when this tab last read or wrote it; 0 when unknown. Plain state, not observed. */
    var loadedMtime: Long = 0L

    /**
     * Session-relative path of this tab's draft side file. Random per tab, so a draft kept for the
     * start screen is never overwritten by a tab from a later launch; a tab restored from such a
     * draft takes over that draft's path instead.
     */
    var draftPath: String = "drafts/${java.util.UUID.randomUUID()}.txt"

    /** Epoch millis of the last text change in this session; 0 when untouched. Plain state, not observed. */
    var editedAt: Long = 0L

    /**
     * Non-null when this tab holds a file too large for the plain [textField]/`BasicTextField`
     * path (see [LargeFileGuard]) — set by [loadLargeFrom], read by
     * [com.apv.editor.ui.LargeEditorPane]. [textField]/[savedText] are unused and stay empty for
     * such a tab; [largeCaret]/[largeSelectionAnchor] are its equivalent of
     * `textField.selection`.
     */
    var largeDoc by mutableStateOf<LargeDocController?>(null)
        private set

    var largeCaret by mutableStateOf(LineCol(0, 0))
    var largeSelectionAnchor by mutableStateOf<LineCol?>(null)

    val isLargeFile: Boolean get() = largeDoc != null

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastCoalesceTime = 0L
    private var lastCoalesceDirection = 0

    val isModified: Boolean
        get() = largeDoc?.isDirty ?: (textField.text != savedText)

    /**
     * The file name, or "Untitled N" for a buffer with no file. Deliberately never derived from the
     * buffer's content: an unsaved note's first line used to be shown here, which put whatever was
     * typed (including secrets) into the tab strip and the window title.
     */
    val title: String
        get() {
            val name = file?.name
            return when {
                !name.isNullOrBlank() -> name
                untitledIndex > 0 -> "Untitled $untitledIndex"
                else -> "Untitled"
            }
        }

    /** Short single-line preview of an unsaved buffer, shown only in the tab's hover tooltip. */
    val contentPreview: String
        get() = if (isLargeFile) "" else try {
            textField.text.take(200).replace(Regex("\\s+"), " ").trim().take(60)
        } catch (_: Throwable) {
            ""
        }

    fun updateText(new: TextFieldValue) {
        val old = textField
        if (new.text == old.text) {
            textField = new
            return
        }
        val now = System.currentTimeMillis()
        val delta = new.text.length - old.text.length
        val direction = if (delta > 0) 1 else if (delta < 0) -1 else 0
        val coalesce = direction != 0 &&
            direction == lastCoalesceDirection &&
            kotlin.math.abs(delta) == 1 &&
            (now - lastCoalesceTime) < COALESCE_MS &&
            undoStack.isNotEmpty()
        if (!coalesce) pushUndo(old)
        redoStack.clear()
        lastCoalesceTime = now
        lastCoalesceDirection = direction
        editedAt = now
        textField = new
    }

    /** Returns true if something changed (a caret-only move counts as a no-op for large-file undo, since it isn't recorded there). */
    fun undo(): Boolean {
        largeDoc?.let { doc ->
            val caret = doc.undo() ?: return false
            largeCaret = caret
            largeSelectionAnchor = null
            return true
        }
        val previous = undoStack.removeLastOrNull() ?: return false
        pushRedo(textField)
        textField = previous
        lastCoalesceTime = 0L
        lastCoalesceDirection = 0
        return true
    }

    fun redo(): Boolean {
        largeDoc?.let { doc ->
            val caret = doc.redo() ?: return false
            largeCaret = caret
            largeSelectionAnchor = null
            return true
        }
        val next = redoStack.removeLastOrNull() ?: return false
        pushUndo(textField)
        textField = next
        lastCoalesceTime = 0L
        lastCoalesceDirection = 0
        return true
    }

    private fun pushUndo(snapshot: TextFieldValue) {
        undoStack.addLast(snapshot)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
    }

    private fun pushRedo(snapshot: TextFieldValue) {
        redoStack.addLast(snapshot)
        while (redoStack.size > MAX_HISTORY) redoStack.removeFirst()
    }

    private fun resetHistory() {
        undoStack.clear()
        redoStack.clear()
        lastCoalesceTime = 0L
        lastCoalesceDirection = 0
    }

    fun loadFrom(f: File) {
        releaseLargeResources()
        val content = f.readText()
        file = f
        language = SyntaxDetector.detect(f.name)
        textField = TextFieldValue(content)
        savedText = content
        loadedMtime = mtimeOf(f)
        resetHistory()
    }

    /** Re-reads [file] from disk, discarding the buffer. Keeps the caret offset where possible. */
    fun reloadFromDisk() {
        val f = file ?: return
        if (isLargeFile) return
        val content = f.readText()
        val cursor = textField.selection.start.coerceIn(0, content.length)
        textField = TextFieldValue(content, selection = TextRange(cursor))
        savedText = content
        loadedMtime = mtimeOf(f)
        resetHistory()
    }

    private fun mtimeOf(f: File): Long = try { f.lastModified() } catch (_: Throwable) { 0L }

    /** The [loadFrom] equivalent for a file [LargeFileGuard] classified as [LargeFileGuard.FileSizeClass.LARGE]. */
    fun loadLargeFrom(f: File) {
        releaseLargeResources()
        val doc = LargeDocument.open(f)
        file = f
        language = SyntaxDetector.detect(f.name)
        textField = TextFieldValue("")
        savedText = ""
        largeDoc = LargeDocController(doc)
        largeCaret = LineCol(0, 0)
        largeSelectionAnchor = null
        resetHistory()
    }

    /** Releases the open file handle backing [largeDoc], if any. Safe to call even when this tab was never large. */
    fun releaseLargeResources() {
        largeDoc?.doc?.close()
        largeDoc = null
    }

    fun save(): Boolean {
        val f = file ?: return false
        val large = largeDoc
        if (large != null) {
            large.doc.writeTo(f)
            large.markSaved()
            return true
        }
        f.writeText(textField.text)
        savedText = textField.text
        loadedMtime = mtimeOf(f)
        return true
    }

    fun saveAs(f: File) {
        val large = largeDoc
        if (large != null) {
            large.doc.writeTo(f)
            large.markSaved()
            file = f
            language = SyntaxDetector.detect(f.name)
            return
        }
        file = f
        language = SyntaxDetector.detect(f.name)
        f.writeText(textField.text)
        savedText = textField.text
        loadedMtime = mtimeOf(f)
    }

    companion object {
        private val nextId = AtomicLong(1)
        private val nextUntitled = AtomicInteger(1)

        /** Makes the next file-less tab "Untitled [next]". Called after a session restore. */
        fun resetUntitledCounter(next: Int) {
            nextUntitled.set(next.coerceAtLeast(1))
        }
        private const val MAX_HISTORY = 15
        private const val COALESCE_MS = 500L
    }
}

enum class PaneLayout(val rows: Int, val cols: Int, val displayName: String) {
    Single(1, 1, "Single"),
    Columns2(1, 2, "Columns: 2"),
    Rows2(2, 1, "Rows: 2"),
    Grid2x2(2, 2, "Grid: 4");

    val paneCount: Int get() = rows * cols
}

/**
 * One pane in a (possibly split) editor window. Owns its own tab list and
 * selection. Tabs live in exactly one pane at a time.
 */
class PaneState {
    val id: Long = nextPaneId.getAndIncrement()
    val tabs = mutableStateListOf<TabState>()
    var selectedIndex by mutableStateOf(0)

    val currentTab: TabState? get() = tabs.getOrNull(selectedIndex)

    fun newTab(file: File? = null): TabState {
        val tab = TabState(initialFile = file)
        if (file != null) tab.loadFrom(file)
        tabs += tab
        selectedIndex = tabs.lastIndex
        return tab
    }

    /** [newTab]'s equivalent for a file [LargeFileGuard] classified as [LargeFileGuard.FileSizeClass.LARGE]. */
    fun newLargeTab(file: File): TabState {
        val tab = TabState(initialFile = file)
        tab.loadLargeFrom(file)
        tabs += tab
        selectedIndex = tabs.lastIndex
        return tab
    }

    /** Reuses an empty/untitled current tab if possible; otherwise opens a new one. */
    fun openFile(file: File): TabState {
        val current = currentTab
        return if (current != null &&
            current.file == null &&
            !current.isModified &&
            current.textField.text.isEmpty()
        ) {
            current.loadFrom(file)
            current
        } else {
            newTab(file)
        }
    }

    /** [openFile]'s equivalent for a file [LargeFileGuard] classified as [LargeFileGuard.FileSizeClass.LARGE]. */
    fun openLargeFile(file: File): TabState {
        val current = currentTab
        return if (current != null &&
            current.file == null &&
            !current.isModified &&
            current.textField.text.isEmpty()
        ) {
            current.loadLargeFrom(file)
            current
        } else {
            newLargeTab(file)
        }
    }

    fun removeTab(tab: TabState) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tab.releaseLargeResources()
        tabs.removeAt(index)
        selectedIndex = if (tabs.isNotEmpty()) {
            selectedIndex.coerceAtMost(tabs.lastIndex)
        } else {
            0
        }
    }

    companion object {
        private val nextPaneId = AtomicLong(1)
    }
}

class EditorState {
    val panes: SnapshotStateList<PaneState> = mutableStateListOf<PaneState>().also { it += PaneState() }
    var focusedPaneId by mutableStateOf(panes[0].id)
    var layout by mutableStateOf(PaneLayout.Single)
        private set
    var horizontalSplit by mutableStateOf(0.5f)
        private set
    var verticalSplit by mutableStateOf(0.5f)
        private set
    var fontSize by mutableStateOf(14)
    var statusMessage by mutableStateOf("")
    var paletteOpen by mutableStateOf(false)

    /** A dirty tab whose file changed on disk while the app was in the background; drives the reload notice. */
    var diskChangeNotice by mutableStateOf<TabState?>(null)

    // Project sidebar.
    var projectRoot by mutableStateOf<File?>(null)
    var sidebarVisible by mutableStateOf(false)
    var sidebarWidth by mutableStateOf(240f)
    val expandedDirs: SnapshotStateList<String> = mutableStateListOf()
    /** Bumped to make the sidebar re-read its folders from disk. */
    var sidebarRefreshTick by mutableStateOf(0)
    var pendingClose by mutableStateOf<TabState?>(null)
        private set
    var pendingLayoutChange by mutableStateOf<PendingLayoutChange?>(null)
        private set
    var pendingOversizedFile by mutableStateOf<File?>(null)
        private set
    var tabDrag by mutableStateOf<TabDragSession?>(null)
        private set
    var autosaveMode: AutosaveMode by mutableStateOf(AutosaveMode.AfterDelay(1500))
    val autoSaver = AutoSaver()

    // Start screen (shown while no tab is open): what earlier sessions left behind.
    /** Absolute paths, newest first. */
    val recentFolders: SnapshotStateList<String> = mutableStateListOf()
    /** Absolute paths, newest first. */
    val recentFiles: SnapshotStateList<String> = mutableStateListOf()
    /** Unsaved buffers that are not open in a tab, newest edit first. */
    val unsavedDrafts: SnapshotStateList<UnsavedDraft> = mutableStateListOf()
    /** A draft whose discard button was clicked; drives the confirmation dialog. */
    var pendingDraftDiscard by mutableStateOf<UnsavedDraft?>(null)
        private set
    /** Reads a draft side file by its session-relative path. Main points this at the session store. */
    var draftReader: (String) -> String? = { null }

    /** True while no pane has a tab: the start screen takes the place of the editor. */
    val hasNoTabs: Boolean get() = panes.all { it.tabs.isEmpty() }

    val focusedPane: PaneState
        get() = panes.firstOrNull { it.id == focusedPaneId } ?: panes[0]

    fun applyHorizontalSplit(ratio: Float) {
        horizontalSplit = ratio.coerceIn(SPLIT_MIN, SPLIT_MAX)
    }

    fun applyVerticalSplit(ratio: Float) {
        verticalSplit = ratio.coerceIn(SPLIT_MIN, SPLIT_MAX)
    }

    /** Switch the window layout. Growing adds empty panes; shrinking merges tabs from dropped panes into pane 0. */
    fun applyLayout(target: PaneLayout) {
        if (target == layout) return
        if (target.paneCount < panes.size) {
            val dirty = panes.drop(target.paneCount).flatMap { it.tabs }.filter { it.isModified }
            if (dirty.isNotEmpty()) {
                pendingLayoutChange = PendingLayoutChange(target, dirty)
                return
            }
        }
        applyLayoutNow(target)
    }

    private fun applyLayoutNow(target: PaneLayout) {
        val want = target.paneCount
        while (panes.size < want) panes += PaneState()
        if (panes.size > want) {
            val pane0 = panes[0]
            for (i in want until panes.size) {
                val dropped = panes[i]
                for (t in dropped.tabs.toList()) pane0.tabs += t
                dropped.tabs.clear()
            }
            while (panes.size > want) panes.removeAt(panes.size - 1)
        }
        if (panes.none { it.id == focusedPaneId }) {
            focusedPaneId = panes[0].id
        }
        layout = target
        statusMessage = "Layout: ${target.displayName}"
    }

    /** Save all dirty tabs in panes being dropped, then apply the pending layout. Aborts if any save returns false. */
    fun confirmLayoutChangeSaving(onSaveTab: (TabState) -> Boolean) {
        val pending = pendingLayoutChange ?: return
        pendingLayoutChange = null
        for (tab in pending.dirtyTabs) {
            if (!onSaveTab(tab)) return
        }
        applyLayoutNow(pending.target)
    }

    /** Apply the pending layout without saving dirty tabs (they keep their modified state). */
    fun confirmLayoutChangeMerging() {
        val pending = pendingLayoutChange ?: return
        pendingLayoutChange = null
        applyLayoutNow(pending.target)
    }

    fun cancelLayoutChange() {
        pendingLayoutChange = null
    }

    fun dismissOversizedFile() {
        pendingOversizedFile = null
    }

    data class PendingLayoutChange(val target: PaneLayout, val dirtyTabs: List<TabState>)

    data class TabDragSession(val tab: TabState, val source: PaneState)

    companion object {
        const val SPLIT_MIN = 0.1f
        const val SPLIT_MAX = 0.9f
        /** How many recent folders and recent files are remembered (each). */
        const val MAX_RECENT = 20
    }

    fun beginTabDrag(tab: TabState, source: PaneState) {
        tabDrag = TabDragSession(tab, source)
    }

    fun endTabDrag() {
        tabDrag = null
    }

    /**
     * Move the currently-dragged tab into [target] at [insertIndex].
     * Same-pane drops reorder; cross-pane drops move and refocus.
     * Caller passes the desired insertion position in the target's tab list before any removal.
     */
    fun moveDraggedTabTo(target: PaneState, insertIndex: Int) {
        val session = tabDrag ?: return
        tabDrag = null
        val fromIdx = session.source.tabs.indexOf(session.tab)
        if (fromIdx < 0) return

        if (session.source === target) {
            if (insertIndex == fromIdx || insertIndex == fromIdx + 1) return
            session.source.tabs.removeAt(fromIdx)
            val corrected = if (insertIndex > fromIdx) insertIndex - 1 else insertIndex
            session.source.tabs.add(corrected.coerceIn(0, session.source.tabs.size), session.tab)
            session.source.selectedIndex = session.source.tabs.indexOf(session.tab)
        } else {
            session.source.tabs.removeAt(fromIdx)
            if (session.source.tabs.isNotEmpty()) {
                session.source.selectedIndex = session.source.selectedIndex.coerceAtMost(session.source.tabs.lastIndex)
            } else {
                session.source.selectedIndex = 0
            }
            val to = insertIndex.coerceIn(0, target.tabs.size)
            target.tabs.add(to, session.tab)
            target.selectedIndex = to
            focusedPaneId = target.id
        }
    }

    val currentTab: TabState? get() = focusedPane.currentTab

    /** Shim: returns the focused pane's tab list. Will be removed when UI talks to panes directly. */
    val tabs: SnapshotStateList<TabState> get() = focusedPane.tabs

    /** Shim: delegates to the focused pane. */
    var selectedIndex: Int
        get() = focusedPane.selectedIndex
        set(value) { focusedPane.selectedIndex = value }

    fun newTab(file: File? = null) {
        if (file != null) {
            when (LargeFileGuard.classify(file, fontSize)) {
                LargeFileGuard.FileSizeClass.UNSUPPORTED -> {
                    pendingOversizedFile = file
                    statusMessage = "${file.name} is too large to open"
                    return
                }
                LargeFileGuard.FileSizeClass.LARGE -> {
                    focusedPane.newLargeTab(file)
                    noteRecentFile(file)
                    statusMessage = "Opened ${file.name} in large-file mode"
                    return
                }
                LargeFileGuard.FileSizeClass.NORMAL -> {}
            }
        }
        focusedPane.newTab(file)
        if (file != null) noteRecentFile(file)
    }

    fun openFile(file: File) = openFile(focusedPane, file)

    fun openFile(pane: PaneState, file: File) {
        val canonical = canonicalOf(file)
        for (p in panes) {
            val idx = p.tabs.indexOfFirst { matchesCanonical(it.file, canonical) }
            if (idx >= 0) {
                p.selectedIndex = idx
                focusedPaneId = p.id
                statusMessage = "Switched to ${file.name}"
                return
            }
        }
        // The file has unsaved changes waiting on the start screen: opening it brings those back
        // rather than leaving two versions of the same file around.
        val waiting = unsavedDrafts.firstOrNull { draft ->
            draft.path?.let { matchesCanonical(File(it), canonical) } == true
        }
        if (waiting != null) {
            focusedPaneId = pane.id
            restoreDraft(waiting)
            return
        }
        when (LargeFileGuard.classify(file, fontSize)) {
            LargeFileGuard.FileSizeClass.UNSUPPORTED -> {
                pendingOversizedFile = file
                statusMessage = "${file.name} is too large to open"
                return
            }
            LargeFileGuard.FileSizeClass.LARGE -> {
                pane.openLargeFile(file)
                noteRecentFile(file)
                statusMessage = "Opened ${file.name} in large-file mode"
                return
            }
            LargeFileGuard.FileSizeClass.NORMAL -> {}
        }
        pane.openFile(file)
        noteRecentFile(file)
        statusMessage = "Opened ${file.name}"
    }

    private fun noteRecent(list: SnapshotStateList<String>, path: String) {
        list.remove(path)
        list.add(0, path)
        while (list.size > MAX_RECENT) list.removeAt(list.lastIndex)
    }

    /** Moves [file] to the top of the start screen's recent files. */
    fun noteRecentFile(file: File) = noteRecent(recentFiles, file.absolutePath)

    /** Opens a recent file from the start screen. A path that no longer exists is dropped from the list instead. */
    fun openRecentFile(path: String) {
        val file = File(path)
        if (!file.isFile) {
            recentFiles.remove(path)
            statusMessage = "${file.name} no longer exists"
            return
        }
        openFile(file)
    }

    /** Opens a recent folder from the start screen. A path that no longer exists is dropped from the list instead. */
    fun openRecentFolder(path: String) {
        val dir = File(path)
        if (!dir.isDirectory) {
            recentFolders.remove(path)
            statusMessage = "${dir.name} no longer exists"
            return
        }
        openProject(dir)
    }

    /**
     * Opens an unsaved buffer from the start screen in the focused pane. The draft wins even when
     * its file changed on disk since: the user asked for this text, and a status message says so.
     */
    fun restoreDraft(draft: UnsavedDraft) {
        if (!unsavedDrafts.remove(draft)) return
        var status: String? = null
        val tab = restoreTab(draft.toTabSnapshot(), fontSize, draftReader) { status = it }
        tab.draftPath = draft.draftPath
        tab.editedAt = draft.editedAt
        val pane = focusedPane
        pane.tabs += tab
        pane.selectedIndex = pane.tabs.lastIndex
        val recorded = draft.diskMtime
        if (status == null && recorded != null && tab.file != null && tab.loadedMtime != recorded) {
            status = "${tab.title} changed on disk after this draft was written"
        }
        statusMessage = status ?: "Restored ${tab.title}"
    }

    fun restoreAllDrafts() {
        val drafts = unsavedDrafts.toList()
        for (draft in drafts) restoreDraft(draft)
        if (drafts.size > 1) statusMessage = "Restored ${drafts.size} unsaved files"
    }

    fun requestDraftDiscard(draft: UnsavedDraft) {
        pendingDraftDiscard = draft
    }

    /** Drops the draft from the list; its side file is pruned by the next session write. */
    fun confirmDraftDiscard() {
        val draft = pendingDraftDiscard ?: return
        pendingDraftDiscard = null
        unsavedDrafts.remove(draft)
    }

    fun cancelDraftDiscard() {
        pendingDraftDiscard = null
    }

    private fun canonicalOf(f: File): File = try { f.canonicalFile } catch (_: Throwable) { f }

    private fun matchesCanonical(existing: File?, target: File): Boolean {
        if (existing == null) return false
        return try {
            existing.canonicalFile == target
        } catch (_: Throwable) {
            existing == target
        }
    }

    fun closeTab(index: Int) = closeTab(focusedPane, index)

    fun closeTab(pane: PaneState, index: Int) {
        val tab = pane.tabs.getOrNull(index) ?: return
        if (tab.isModified) {
            pendingClose = tab
        } else {
            pane.removeTab(tab)
        }
    }

    fun closePending() {
        val tab = pendingClose ?: return
        pendingClose = null
        tab.savedText = tab.textField.text
        ownerPaneOf(tab)?.removeTab(tab)
    }

    fun cancelClose() {
        pendingClose = null
    }

    private fun ownerPaneOf(tab: TabState): PaneState? =
        panes.firstOrNull { it.tabs.contains(tab) }

    fun undo() {
        val tab = currentTab ?: return
        if (tab.undo()) statusMessage = "Undo"
    }

    /** Opens the find bar for the current tab (or re-focuses it). Not available for large-file tabs yet. */
    fun openFind() {
        val tab = currentTab ?: return
        if (tab.isLargeFile) {
            statusMessage = "Find is not available in large-file mode yet"
            return
        }
        tab.findOpen = true
        tab.findFocusTick++
    }

    fun closeFind(tab: TabState) {
        tab.findOpen = false
        tab.findMatches = emptyList()
        tab.findIndex = 0
        tab.editorFocusTick++
    }

    /** Moves to the next ([delta] = 1) or previous (-1) match, wrapping around. */
    fun stepFind(tab: TabState, delta: Int) {
        val count = tab.findMatches.size
        if (count == 0) return
        tab.findIndex = ((tab.findIndex + delta) % count + count) % count
        tab.findRevealTick++
    }

    /**
     * Compares every open file's modified time with what its tab last read or wrote. A clean tab is
     * reloaded silently; a dirty one raises [diskChangeNotice] so the user decides. Called when the
     * window regains focus. Large-file tabs are skipped: the file on disk is their buffer.
     */
    fun checkExternalChanges() {
        for (pane in panes) {
            for (tab in pane.tabs) {
                val f = tab.file ?: continue
                if (tab.isLargeFile || tab.loadedMtime == 0L) continue
                val now = try { f.lastModified() } catch (_: Throwable) { 0L }
                if (now == 0L || now == tab.loadedMtime) continue
                if (tab.isModified) {
                    tab.loadedMtime = now
                    diskChangeNotice = tab
                } else {
                    try {
                        tab.reloadFromDisk()
                        statusMessage = "Reloaded ${f.name}"
                    } catch (_: Throwable) {
                        tab.loadedMtime = now
                    }
                }
            }
        }
        if (projectRoot != null) sidebarRefreshTick++
    }

    fun reloadNoticedTab() {
        val tab = diskChangeNotice ?: return
        diskChangeNotice = null
        try {
            tab.reloadFromDisk()
            statusMessage = "Reloaded ${tab.file?.name}"
        } catch (_: Throwable) {
            statusMessage = "Could not reload ${tab.file?.name}"
        }
    }

    fun dismissDiskChangeNotice() {
        diskChangeNotice = null
    }

    fun openProject(dir: File) {
        projectRoot = dir
        noteRecent(recentFolders, dir.absolutePath)
        expandedDirs.clear()
        sidebarVisible = true
        sidebarRefreshTick++
        statusMessage = "Opened folder ${dir.name}"
    }

    fun toggleSidebar() {
        if (projectRoot == null) {
            statusMessage = "Open a folder first (File > Open Folder)"
            return
        }
        sidebarVisible = !sidebarVisible
    }

    fun toggleDir(dir: File) {
        val path = dir.absolutePath
        if (!expandedDirs.remove(path)) expandedDirs += path
    }

    fun redo() {
        val tab = currentTab ?: return
        if (tab.redo()) statusMessage = "Redo"
    }

    /**
     * Debounce-triggered autosave — only actually schedules anything in
     * [AutosaveMode.AfterDelay]; see [autosaveOnFocusLost]/[autosaveAllOnWindowFocusLost] for the
     * other modes.
     */
    fun scheduleAutosave(tab: TabState) {
        val mode = autosaveMode as? AutosaveMode.AfterDelay ?: return
        if (tab.file == null || !tab.isModified) return
        autoSaver.schedule(tab.id, mode.delayMs) {
            if (tab.isModified && tab.file != null) {
                tab.save()
                statusMessage = "Auto-saved ${tab.file?.name}"
            }
        }
    }

    /**
     * Called when [tab]'s editing surface (`EditorPane`/`LargeEditorPane`) is disposed because it
     * stopped being the active tab — switched away from, closed, or its pane closed. Only acts in
     * [AutosaveMode.OnFocusChange]; a tab that's dirty at dispose time in any other mode is left
     * alone (its content lives on in the session draft either way — see [tabToSnapshot]).
     *
     * Deliberately keyed off the tab's own composable lifecycle rather than lower-level Compose
     * focus events: in [com.apv.editor.ui.LargeEditorPane], the only real focusable field is
     * whichever line the caret is on, which gets reassigned — and re-focused — on every Up/Down
     * press, so raw focus-lost events there would fire far more often than a real "switched away"
     * does. One known gap: switching between panes in a split layout *without* switching tabs
     * doesn't currently count as losing focus here.
     */
    fun autosaveOnFocusLost(tab: TabState) {
        if (autosaveMode != AutosaveMode.OnFocusChange) return
        if (tab.file == null || !tab.isModified) return
        tab.save()
        statusMessage = "Auto-saved ${tab.file?.name}"
    }

    /**
     * Saves every dirty, saveable tab across all panes. Only acts in [AutosaveMode.OnWindowChange];
     * called when the whole app window loses OS-level focus (see Main.kt).
     */
    fun autosaveAllOnWindowFocusLost() {
        if (autosaveMode != AutosaveMode.OnWindowChange) return
        for (pane in panes) {
            for (tab in pane.tabs) {
                if (tab.file != null && tab.isModified) {
                    tab.save()
                }
            }
        }
        statusMessage = "Auto-saved"
    }

    fun toWrite(window: WindowSnapshot?): SessionWrite {
        val drafts = mutableMapOf<String, String>()
        val paneSnapshots = panes.map { pane ->
            PaneSnapshot(
                selectedIndex = pane.selectedIndex.coerceAtLeast(0),
                tabs = pane.tabs.map { tab -> tabToSnapshot(tab, drafts) },
            )
        }
        val focusedIdx = panes.indexOfFirst { it.id == focusedPaneId }
            .let { if (it < 0) 0 else it }
        return SessionWrite(
            snapshot = SessionSnapshot(
                version = 1,
                layout = layout.name,
                fontSize = fontSize,
                focusedPaneIndex = focusedIdx,
                window = window,
                panes = paneSnapshots,
                projectRoot = projectRoot?.absolutePath,
                sidebarVisible = sidebarVisible,
                sidebarWidth = sidebarWidth.toInt(),
                expandedDirs = expandedDirs.toList(),
                recentFolders = recentFolders.toList(),
                recentFiles = recentFiles.toList(),
                unsaved = unsavedDrafts.map { it.toSnapshot() },
            ),
            drafts = drafts,
            keepDrafts = unsavedDrafts.map { it.draftPath }.toSet(),
        )
    }

    private fun tabToSnapshot(tab: TabState, drafts: MutableMap<String, String>): TabSnapshot {
        val path = tab.file?.absolutePath
        if (tab.isLargeFile) {
            // Large-file tabs never buffer their full text as a draft (infeasible for a
            // multi-gigabyte document) — edits go straight through to disk via autosave/manual
            // save instead, so there's nothing to snapshot besides which file it is. The caret
            // position isn't restored across relaunches for these; see restoreTab below.
            return TabSnapshot(
                path = path,
                language = tab.language.name,
                cursor = 0,
                showPreview = tab.showPreview,
                draftPath = null,
                diskMtime = null,
                previewMode = tab.previewMode.name,
            )
        }
        val cursor = tab.textField.selection.start
        val text = tab.textField.text
        val needsDraft = (tab.file == null && text.isNotEmpty()) ||
            (tab.file != null && tab.isModified)
        val draftPath = if (needsDraft) tab.draftPath else null
        if (draftPath != null) drafts[draftPath] = text
        val mtime = if (needsDraft && tab.file != null) {
            try { tab.file?.lastModified() } catch (_: Throwable) { null }
        } else null
        return TabSnapshot(
            path = path,
            language = tab.language.name,
            cursor = cursor,
            showPreview = tab.showPreview,
            draftPath = draftPath,
            diskMtime = mtime,
            previewMode = tab.previewMode.name,
            untitledIndex = if (tab.file == null && tab.untitledIndex > 0) tab.untitledIndex else null,
            editedAt = if (needsDraft && tab.editedAt > 0L) tab.editedAt else null,
        )
    }

    /**
     * Seeds a freshly launched window from the previous session. Nothing is reopened: the app
     * starts on the start screen, with no tabs and no folder. Buffers that were unsaved when the
     * app closed become [unsavedDrafts]; the files and folder that were open join the recent lists.
     * Font size, layout and sidebar width carry over as preferences.
     *
     * Cheap enough for the UI thread: no file is read, and [draftModified] (last-modified time of
     * a draft side file, null when it is gone) is one stat per unsaved buffer.
     */
    fun applyStartup(snapshot: SessionSnapshot, draftModified: (String) -> Long?) {
        fontSize = snapshot.fontSize.coerceIn(8, 48)
        sidebarWidth = snapshot.sidebarWidth.coerceIn(160, 480).toFloat()
        val savedLayout = PaneLayout.entries.firstOrNull { it.name == snapshot.layout } ?: PaneLayout.Single
        while (panes.size < savedLayout.paneCount) panes += PaneState()
        layout = savedLayout

        val previousTabs = snapshot.panes.flatMap { it.tabs }

        // One entry per file (or per draft, for untitled buffers); the newer edit wins.
        val drafts = LinkedHashMap<String, UnsavedDraft>()
        fun keep(draft: UnsavedDraft) {
            val key = draft.path ?: draft.draftPath
            val existing = drafts[key]
            if (existing == null || existing.editedAt < draft.editedAt) drafts[key] = draft
        }
        for (tab in previousTabs) {
            val draftPath = tab.draftPath ?: continue
            val modified = draftModified(draftPath) ?: continue
            keep(
                UnsavedDraft(
                    draftPath = draftPath,
                    path = tab.path,
                    language = tab.language,
                    cursor = tab.cursor,
                    diskMtime = tab.diskMtime,
                    untitledIndex = tab.untitledIndex ?: 0,
                    editedAt = tab.editedAt ?: modified,
                ),
            )
        }
        for (saved in snapshot.unsaved) {
            val modified = draftModified(saved.draftPath) ?: continue
            keep(
                UnsavedDraft(
                    draftPath = saved.draftPath,
                    path = saved.path,
                    language = saved.language,
                    cursor = saved.cursor,
                    diskMtime = saved.diskMtime,
                    untitledIndex = saved.untitledIndex ?: 0,
                    editedAt = if (saved.editedAt > 0L) saved.editedAt else modified,
                ),
            )
        }
        unsavedDrafts.clear()
        unsavedDrafts.addAll(drafts.values.sortedByDescending { it.editedAt })
        // New "Untitled N" tabs must not reuse a number an unsaved buffer still shows.
        TabState.resetUntitledCounter((unsavedDrafts.maxOfOrNull { it.untitledIndex } ?: 0) + 1)

        // Sessions written before the recent lists existed still say which files and folder were open.
        val files = LinkedHashSet<String>(snapshot.recentFiles)
        previousTabs.mapNotNullTo(files) { it.path }
        recentFiles.clear()
        recentFiles.addAll(files.take(MAX_RECENT))

        val folders = LinkedHashSet<String>(snapshot.recentFolders)
        snapshot.projectRoot?.let { folders += it }
        recentFolders.clear()
        recentFolders.addAll(folders.take(MAX_RECENT))
    }
}

/**
 * An unsaved buffer kept from an earlier session. The start screen lists it until it is restored
 * into a tab or discarded; its text stays in the draft side file at [draftPath] until then.
 */
data class UnsavedDraft(
    val draftPath: String,
    /** Absolute path of the file the buffer belongs to; null for an untitled buffer. */
    val path: String?,
    val language: String,
    val cursor: Int,
    val diskMtime: Long?,
    val untitledIndex: Int,
    /** Epoch millis of the last edit. */
    val editedAt: Long,
) {
    /** Same rule as [TabState.title]: the file name or "Untitled N", never the content. */
    val title: String
        get() = when {
            path != null -> File(path).name
            untitledIndex > 0 -> "Untitled $untitledIndex"
            else -> "Untitled"
        }

    fun toSnapshot() = UnsavedSnapshot(
        draftPath = draftPath,
        path = path,
        language = language,
        cursor = cursor,
        diskMtime = diskMtime,
        untitledIndex = untitledIndex.takeIf { it > 0 },
        editedAt = editedAt,
    )

    /** For [restoreTab]. [diskMtime] is left out on purpose so a file changed on disk does not drop the draft. */
    fun toTabSnapshot() = TabSnapshot(
        path = path,
        language = language,
        cursor = cursor,
        draftPath = draftPath,
        diskMtime = null,
        untitledIndex = untitledIndex.takeIf { it > 0 },
    )
}

/** Applies the per-tab snapshot fields that every [restoreTab] branch shares. */
private fun applySnapshotExtras(tab: TabState, snap: TabSnapshot) {
    tab.previewMode = PreviewMode.entries.firstOrNull { it.name == snap.previewMode }
        ?: if (snap.showPreview) PreviewMode.Split else PreviewMode.Edit
    val savedIndex = snap.untitledIndex
    if (tab.file == null && savedIndex != null && savedIndex > 0) tab.untitledIndex = savedIndex
}

private fun restoreTab(
    snap: TabSnapshot,
    fontSize: Int,
    readDraft: (String) -> String?,
    onStatus: (String) -> Unit,
): TabState {
    val language = SyntaxLanguage.entries.firstOrNull { it.name == snap.language } ?: SyntaxLanguage.NONE
    val draftText = snap.draftPath?.let { readDraft(it) }
    val path = snap.path

    if (path == null) {
        val tab = TabState(initialFile = null, initialText = "")
        tab.language = language
        applySnapshotExtras(tab, snap)
        if (!draftText.isNullOrEmpty()) {
            val cursor = snap.cursor.coerceIn(0, draftText.length)
            tab.textField = TextFieldValue(draftText, selection = TextRange(cursor))
        }
        return tab
    }

    val file = File(path)
    if (!file.isFile) {
        val tab = TabState(initialFile = null, initialText = "")
        tab.language = language
        applySnapshotExtras(tab, snap)
        if (!draftText.isNullOrEmpty()) {
            val cursor = snap.cursor.coerceIn(0, draftText.length)
            tab.textField = TextFieldValue(draftText, selection = TextRange(cursor))
        }
        onStatus("Missing file: $path — recovered unsaved buffer")
        return tab
    }

    // Classify from the file's line count (streamed, not loaded) before ever reading its content —
    // the whole point of large-file mode is to avoid pulling a huge file fully into memory, and
    // that includes during session restore.
    when (LargeFileGuard.classify(file, fontSize)) {
        LargeFileGuard.FileSizeClass.UNSUPPORTED -> {
            val tab = TabState(initialFile = null, initialText = "")
            tab.language = language
            applySnapshotExtras(tab, snap)
            onStatus("${file.name} is too large to reopen automatically — use File > Open to load it")
            return tab
        }
        LargeFileGuard.FileSizeClass.LARGE -> {
            val tab = TabState(initialFile = file)
            tab.loadLargeFrom(file)
            tab.language = language
            applySnapshotExtras(tab, snap)
            // Large-file tabs don't carry a draft or a restorable cursor position (see
            // tabToSnapshot) — the caret simply starts at the top of the file.
            return tab
        }
        LargeFileGuard.FileSizeClass.NORMAL -> {}
    }

    val diskText = try { file.readText() } catch (_: Throwable) { null }

    if (diskText == null) {
        val tab = TabState(initialFile = null, initialText = "")
        tab.language = language
        applySnapshotExtras(tab, snap)
        if (!draftText.isNullOrEmpty()) {
            val cursor = snap.cursor.coerceIn(0, draftText.length)
            tab.textField = TextFieldValue(draftText, selection = TextRange(cursor))
        }
        onStatus("Missing file: $path — recovered unsaved buffer")
        return tab
    }

    val tab = TabState(initialFile = file)
    tab.loadFrom(file)
    tab.language = language
    applySnapshotExtras(tab, snap)

    if (draftText == null) {
        val cursor = snap.cursor.coerceIn(0, diskText.length)
        tab.textField = tab.textField.copy(selection = TextRange(cursor))
        return tab
    }

    val recordedMtime = snap.diskMtime
    val currentMtime = try { file.lastModified() } catch (_: Throwable) { 0L }
    if (recordedMtime != null && recordedMtime != currentMtime) {
        val cursor = snap.cursor.coerceIn(0, diskText.length)
        tab.textField = tab.textField.copy(selection = TextRange(cursor))
        onStatus("${file.name} changed externally — discarded unsaved buffer")
        return tab
    }

    if (LargeFileGuard.isOversized(draftText, fontSize)) {
        val cursor = snap.cursor.coerceIn(0, diskText.length)
        tab.textField = tab.textField.copy(selection = TextRange(cursor))
        onStatus("${file.name}'s unsaved draft was too large to restore — showing saved content instead")
        return tab
    }

    val cursor = snap.cursor.coerceIn(0, draftText.length)
    tab.textField = TextFieldValue(draftText, selection = TextRange(cursor))
    return tab
}
