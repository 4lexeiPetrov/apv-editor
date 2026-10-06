package com.apv.editor.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Scrollbar look shared by [EditorPane] and [LargeEditorPane], from the design canvas: a slim
 * rounded thumb (8dp: the canvas draws 6dp, which was too thin to grab) in [EditorTheme.Outline], which is visible on the dark background without being
 * the brightest thing on screen. It lightens under the pointer so it is easy to tell when it is
 * grabbed. ([EditorTheme.Border] was tried first and was too close to the background to find.)
 */
@Composable
internal fun editorScrollbarStyle(): ScrollbarStyle = defaultScrollbarStyle().copy(
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    unhoverColor = EditorTheme.Outline,
    hoverColor = EditorTheme.GutterText,
)
