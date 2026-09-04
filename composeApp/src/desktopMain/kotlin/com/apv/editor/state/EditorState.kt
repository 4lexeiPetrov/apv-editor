package com.apv.editor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.apv.editor.platform.AutoSaver
import com.apv.editor.session.PaneSnapshot
import com.apv.editor.session.SessionSnapshot
import com.apv.editor.session.SessionWrite
import com.apv.editor.session.TabSnapshot
import com.apv.editor.session.WindowSnapshot
import com.apv.editor.syntax.SyntaxDetector
import com.apv.editor.syntax.SyntaxLanguage
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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
    var showPreview by mutableStateOf(false)

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastCoalesceTime = 0L
    private var lastCoalesceDirection = 0

    val isModified: Boolean
        get() = textField.text != savedText

    val title: String
        get() = try {
            val name = file?.name
            if (!name.isNullOrBlank()) {
                name
            } else {
                val preview = textField.text
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(50)
                if (preview.isEmpty()) "Untitled" else preview
            }
        } catch (_: Throwable) {
            "Untitled"
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
        textField = new
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        pushRedo(textField)
        textField = previous
        lastCoalesceTime = 0L
        lastCoalesceDirection = 0
        return true
    }

    fun redo(): Boolean {
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
        val content = f.readText()
        file = f
        language = SyntaxDetector.detect(f.name)
        textField = TextFieldValue(content)
        savedText = content
        resetHistory()
    }

    fun save(): Boolean {
        val f = file ?: return false
        f.writeText(textField.text)
        savedText = textField.text
        return true
    }

    fun saveAs(f: File) {
        file = f
        language = SyntaxDetector.detect(f.name)
        f.writeText(textField.text)
        savedText = textField.text
    }

    companion object {
        private val nextId = AtomicLong(1)
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

    fun removeTab(tab: TabState) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
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
    var statusMessage by mutableStateOf("Ready")
    var pendingClose by mutableStateOf<TabState?>(null)
        private set
    var pendingLayoutChange by mutableStateOf<PendingLayoutChange?>(null)
        private set
    var pendingOversizedFile by mutableStateOf<File?>(null)
        private set
    var tabDrag by mutableStateOf<TabDragSession?>(null)
        private set
    val autoSaver = AutoSaver(delayMs = 1500)

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
        if (file != null && LargeFileGuard.isOversized(file, fontSize)) {
            pendingOversizedFile = file
            statusMessage = "${file.name} is too large to open"
            return
        }
        focusedPane.newTab(file)
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
        if (LargeFileGuard.isOversized(file, fontSize)) {
            pendingOversizedFile = file
            statusMessage = "${file.name} is too large to open"
            return
        }
        pane.openFile(file)
        statusMessage = "Opened ${file.name}"
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

    fun redo() {
        val tab = currentTab ?: return
        if (tab.redo()) statusMessage = "Redo"
    }

    fun scheduleAutosave(tab: TabState) {
        if (tab.file == null || !tab.isModified) return
        autoSaver.schedule(tab.id) {
            if (tab.isModified && tab.file != null) {
                tab.save()
                statusMessage = "Auto-saved ${tab.file?.name}"
            }
        }
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
            ),
            drafts = drafts,
        )
    }

    private fun tabToSnapshot(tab: TabState, drafts: MutableMap<String, String>): TabSnapshot {
        val path = tab.file?.absolutePath
        val cursor = tab.textField.selection.start
        val text = tab.textField.text
        val needsDraft = (tab.file == null && text.isNotEmpty()) ||
            (tab.file != null && tab.isModified)
        val draftPath = if (needsDraft) "drafts/${tab.id}.txt" else null
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
        )
    }

    /** Synchronous convenience wrapper — reads every restored tab's file from disk on the calling thread. */
    fun restoreFrom(
        snapshot: SessionSnapshot,
        readDraft: (String) -> String?,
        onStatus: (String) -> Unit,
    ) {
        applyRestored(buildRestoredSession(snapshot, readDraft, onStatus))
    }

    /**
     * Applies an already-built [RestoredSession] (see [buildRestoredSession]) to this state.
     * Touches only in-memory objects — no I/O — so it's cheap and safe to call from the UI thread
     * even when [buildRestoredSession] itself ran on a background dispatcher.
     */
    fun applyRestored(restored: RestoredSession) {
        layout = restored.layout
        fontSize = restored.fontSize
        panes.clear()
        panes.addAll(restored.panes)
        val focusIdx = restored.focusedPaneIndex.coerceIn(0, panes.lastIndex)
        focusedPaneId = panes[focusIdx].id
        restored.statusMessage?.let { statusMessage = it }
    }
}

/** Result of restoring a [SessionSnapshot] into fresh, unattached [PaneState]s — see [buildRestoredSession]. */
data class RestoredSession(
    val layout: PaneLayout,
    val fontSize: Int,
    val panes: List<PaneState>,
    val focusedPaneIndex: Int,
    val statusMessage: String?,
)

/**
 * Builds a [RestoredSession] from a [SessionSnapshot] — the I/O-heavy part of session restore.
 * For every restored tab this reads its draft side-file (via [readDraft]) and, for tabs backed by
 * a file, the file itself (`File.readText()`) to detect external changes and re-highlight it. For
 * a session with several tabs — or a couple of large files — that's real disk I/O.
 *
 * This function touches no Compose state: it only builds plain [PaneState]/[TabState] objects that
 * aren't attached to any [EditorState] yet, so it's safe to run on a background dispatcher (e.g.
 * `Dispatchers.IO`). Callers on a UI thread should do exactly that, then hand the result to
 * [EditorState.applyRestored] — which is cheap — back on the UI thread.
 */
fun buildRestoredSession(
    snapshot: SessionSnapshot,
    readDraft: (String) -> String?,
    onStatus: (String) -> Unit = {},
): RestoredSession {
    val layout = PaneLayout.entries.firstOrNull { it.name == snapshot.layout } ?: PaneLayout.Single
    val fontSize = snapshot.fontSize.coerceIn(8, 48)

    var lastStatus: String? = null
    val trackStatus: (String) -> Unit = { msg -> lastStatus = msg; onStatus(msg) }

    val panes = mutableListOf<PaneState>()
    for (paneSnap in snapshot.panes) {
        val pane = PaneState()
        for (tabSnap in paneSnap.tabs) {
            pane.tabs += restoreTab(tabSnap, fontSize, readDraft, trackStatus)
        }
        pane.selectedIndex = if (pane.tabs.isEmpty()) 0
            else paneSnap.selectedIndex.coerceIn(0, pane.tabs.lastIndex)
        panes += pane
    }
    if (panes.isEmpty()) {
        panes += PaneState().also { it.newTab() }
    } else if (panes.all { it.tabs.isEmpty() }) {
        panes[0].newTab()
    }
    val focusedPaneIndex = snapshot.focusedPaneIndex.coerceIn(0, panes.lastIndex)
    return RestoredSession(layout, fontSize, panes, focusedPaneIndex, lastStatus)
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
        tab.showPreview = snap.showPreview
        if (!draftText.isNullOrEmpty()) {
            val cursor = snap.cursor.coerceIn(0, draftText.length)
            tab.textField = TextFieldValue(draftText, selection = TextRange(cursor))
        }
        return tab
    }

    val file = File(path)
    val diskText = try { if (file.isFile) file.readText() else null } catch (_: Throwable) { null }

    if (diskText == null) {
        val tab = TabState(initialFile = null, initialText = "")
        tab.language = language
        tab.showPreview = snap.showPreview
        if (!draftText.isNullOrEmpty()) {
            val cursor = snap.cursor.coerceIn(0, draftText.length)
            tab.textField = TextFieldValue(draftText, selection = TextRange(cursor))
        }
        onStatus("Missing file: $path — recovered unsaved buffer")
        return tab
    }

    if (LargeFileGuard.isOversized(diskText, fontSize)) {
        val tab = TabState(initialFile = null, initialText = "")
        tab.language = language
        tab.showPreview = snap.showPreview
        if (!draftText.isNullOrEmpty() && !LargeFileGuard.isOversized(draftText, fontSize)) {
            val cursor = snap.cursor.coerceIn(0, draftText.length)
            tab.textField = TextFieldValue(draftText, selection = TextRange(cursor))
        }
        onStatus("${file.name} is too large to reopen automatically — use File > Open to load it")
        return tab
    }

    val tab = TabState(initialFile = file)
    tab.loadFrom(file)
    tab.language = language
    tab.showPreview = snap.showPreview

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
