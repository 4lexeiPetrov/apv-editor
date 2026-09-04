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
)

/** What `EditorState.toWrite` produces and [SessionStore.schedule] consumes. */
data class SessionWrite(
    val snapshot: SessionSnapshot,
    /** Map from draftPath (relative to the store root) → buffer text. */
    val drafts: Map<String, String>,
)
