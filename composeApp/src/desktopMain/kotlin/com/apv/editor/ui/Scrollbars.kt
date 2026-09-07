package com.apv.editor.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Scrollbar coloring shared by [EditorPane] and [LargeEditorPane].
 *
 * Uses [EditorTheme.OnSurfaceMuted] (not [EditorTheme.Border]) as the resting color: Border reads
 * almost the same as [EditorTheme.Background] against the dark theme, which made the bar
 * effectively invisible until hovered — bad for a control the user needs to *find* first. Slightly
 * thicker than Compose's own default (8.dp) for the same reason.
 */
@Composable
internal fun editorScrollbarStyle(): ScrollbarStyle = defaultScrollbarStyle().copy(
    thickness = 10.dp,
    unhoverColor = EditorTheme.OnSurfaceMuted,
    hoverColor = EditorTheme.Accent,
)
