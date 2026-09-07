package com.apv.editor.platform

/**
 * When [com.apv.editor.state.EditorState] writes a dirty, already-named file back to disk on its
 * own, without the user explicitly hitting Save. Modeled after Zed's own `autosave` setting
 * (`off` / `after_delay` / `on_focus_change` / `on_window_change`).
 */
sealed class AutosaveMode {
    /** Never autosave; the user must save explicitly. */
    data object Off : AutosaveMode()

    /** Save [delayMs] after the last edit to a tab, debounced per tab (see [AutoSaver]). */
    data class AfterDelay(val delayMs: Long) : AutosaveMode()

    /** Save a tab when it stops being the active/displayed one (tab switch, tab close, pane close). */
    data object OnFocusChange : AutosaveMode()

    /** Save every dirty, saveable tab when the whole app window loses OS-level focus. */
    data object OnWindowChange : AutosaveMode()
}
