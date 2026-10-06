package com.apv.editor.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope

/** Per-OS switches for shortcuts and window chrome. */
object Platform {
    val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /**
     * A menu shortcut on the platform's primary modifier: Cmd on macOS, Ctrl everywhere else. The
     * menu used to hard-code `meta = true`, which is the Windows key on Windows.
     */
    fun shortcut(key: Key, shift: Boolean = false, alt: Boolean = false): KeyShortcut =
        KeyShortcut(key, ctrl = !isMac, meta = isMac, alt = alt, shift = shift)

    /** The same shortcut as text for hints and the command palette, e.g. "⇧⌘P" or "Ctrl+Shift+P". */
    fun shortcutLabel(key: String, shift: Boolean = false, alt: Boolean = false): String =
        if (isMac) {
            (if (alt) "⌥" else "") + (if (shift) "⇧" else "") + "⌘" + key
        } else {
            "Ctrl+" + (if (alt) "Alt+" else "") + (if (shift) "Shift+" else "") + key
        }

    /** The same shortcut as separate key caps for the start screen, e.g. ["⌘", "⇧", "O"] or ["Ctrl", "Shift", "O"]. */
    fun shortcutKeys(key: String, shift: Boolean = false): List<String> = buildList {
        add(if (isMac) "⌘" else "Ctrl")
        if (shift) add(if (isMac) "⇧" else "Shift")
        add(key)
    }
}

/** Width reserved at the window's top-left for the macOS traffic lights when the title bar is unified. */
val TrafficLightInset: Dp = 78.dp

/**
 * The window's scope, provided by Main.kt only when the app draws under a unified (transparent)
 * title bar. Tab bars and the sidebar header use it to make their empty areas drag the window.
 * Null means a normal native title bar: nothing extra to do.
 */
val LocalUnifiedWindowScope = staticCompositionLocalOf<WindowScope?> { null }

/** Start padding the top-left pane's tab bar needs to clear the traffic lights; 0 in fullscreen and on other platforms. */
val LocalTitleBarInset = compositionLocalOf { 0.dp }
