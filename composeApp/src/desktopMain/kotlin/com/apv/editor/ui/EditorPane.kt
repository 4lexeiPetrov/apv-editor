package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.state.EditorState
import com.apv.editor.state.PaneState
import com.apv.editor.state.TabState
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun EditorPane(tab: TabState, pane: PaneState, state: EditorState) {
    val verticalScroll = rememberScrollState()
    var layoutResult by remember(tab.id) { mutableStateOf<TextLayoutResult?>(null) }
    val focusRequester = remember(tab.id) { FocusRequester() }

    LaunchedEffect(tab.id) {
        snapshotFlow { tab.textField.text }
            .distinctUntilChanged()
            .collect { state.scheduleAutosave(tab) }
    }

    Row(modifier = Modifier.fillMaxSize().background(EditorTheme.Background)) {
        LineGutter(
            text = tab.textField.text,
            layoutResult = layoutResult,
            scrollState = verticalScroll,
            fontSize = state.fontSize,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScroll)
                .pointerInput(tab.id, pane.id) {
                    detectTapGestures {
                        state.focusedPaneId = pane.id
                        val end = tab.textField.text.length
                        tab.textField = tab.textField.copy(selection = TextRange(end))
                        focusRequester.requestFocus()
                    }
                }
                .padding(start = 8.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
        ) {
            BasicTextField(
                value = tab.textField,
                onValueChange = { tab.updateText(it) },
                textStyle = TextStyle(
                    color = EditorTheme.OnSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = state.fontSize.sp,
                    lineHeight = (state.fontSize * 1.4).sp,
                ),
                cursorBrush = SolidColor(EditorTheme.Accent),
                visualTransformation = remember(tab.language) { HighlightTransformation(tab.language) },
                onTextLayout = { layoutResult = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }
}
