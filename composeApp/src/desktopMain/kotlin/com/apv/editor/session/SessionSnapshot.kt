package com.apv.editor.session

import kotlinx.serialization.Serializable

@Serializable
data class SessionSnapshot(
    val version: Int = 1,
    val layout: String,
    val fontSize: Int,
    val focusedPaneIndex: Int,
    val window: WindowSnapshot? = null,
    val panes: List<PaneSnapshot>,
    /** Absolute path of the folder shown in the project sidebar, if one is open. */
    val projectRoot: String? = null,
    val sidebarVisible: Boolean = false,
    val sidebarWidth: Int = 240,
    /** Absolute paths of the folders expanded in the sidebar tree. */
    val expandedDirs: List<String> = emptyList(),
    /** Most recently opened folders, newest first. Absolute paths. */
    val recentFolders: List<String> = emptyList(),
    /** Most recently opened files, newest first. Absolute paths. */
    val recentFiles: List<String> = emptyList(),
    /** Unsaved buffers from earlier sessions that the start screen lists but no tab has open. */
    val unsaved: List<UnsavedSnapshot> = emptyList(),
)

/** One unsaved buffer kept for the start screen. Its text lives in the draft side file at [draftPath]. */
@Serializable
data class UnsavedSnapshot(
    val draftPath: String,
    val path: String? = null,
    val language: String = "NONE",
    val cursor: Int = 0,
    val diskMtime: Long? = null,
    val untitledIndex: Int? = null,
    /** Epoch millis of the last edit; 0 when unknown. */
    val editedAt: Long = 0,
)

@Serializable
data class WindowSnapshot(
    val width: Int,
    val height: Int,
    val x: Int? = null,
    val y: Int? = null,
)

@Serializable
data class PaneSnapshot(
    val selectedIndex: Int,
    val tabs: List<TabSnapshot>,
)

@Serializable
data class TabSnapshot(
    val path: String? = null,
    val language: String,
    val cursor: Int = 0,
    val showPreview: Boolean = false,
    val draftPath: String? = null,
    val diskMtime: Long? = null,
    /** Name of a [com.apv.editor.state.PreviewMode]; null in sessions written before it existed (then [showPreview] decides). */
    val previewMode: String? = null,
    /** The N in "Untitled N" for a tab with no file; null for file-backed tabs and old sessions. */
    val untitledIndex: Int? = null,
    /** Epoch millis of the last edit to a tab that has a draft; null in older sessions. */
    val editedAt: Long? = null,
)

/** What `EditorState.toWrite` produces and [SessionStore.schedule] consumes. */
data class SessionWrite(
    val snapshot: SessionSnapshot,
    /** Map from draftPath (relative to the store root) → buffer text. */
    val drafts: Map<String, String>,
    /** Draft side files to leave untouched: buffers the start screen still lists but no tab has open. */
    val keepDrafts: Set<String> = emptySet(),
)
