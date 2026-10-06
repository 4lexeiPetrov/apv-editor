package com.apv.editor.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

/** Extra room left at a scrollbar's far end so it doesn't sit under the other axis's scrollbar in the corner. */
private val ScrollbarClearance = 12.dp

@Composable
internal fun EditorPane(tab: TabState, pane: PaneState, state: EditorState) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    var layoutResult by remember(tab.id) { mutableStateOf<TextLayoutResult?>(null) }
    val focusRequester = remember(tab.id) { FocusRequester() }
    val transformation = remember(tab.language) { HighlightTransformation(tab.language) }

    LaunchedEffect(tab.id) {
        snapshotFlow { tab.textField.text }
            .distinctUntilChanged()
            .collect { state.scheduleAutosave(tab) }
    }

    // See EditorState.autosaveOnFocusLost: fires when this tab stops being the active/displayed one.
    DisposableEffect(tab.id) {
        onDispose { state.autosaveOnFocusLost(tab) }
    }

    // The find bar and the command palette hand keyboard focus back to the editor by bumping this.
    LaunchedEffect(tab.id) {
        snapshotFlow { tab.editorFocusTick }.collect { tick ->
            if (tick > 0) runCatching { focusRequester.requestFocus() }
        }
    }

    // Scroll the current find match into view when the find bar asks for it (Enter, arrows, new query).
    LaunchedEffect(tab.id) {
        snapshotFlow { tab.findRevealTick }.collect { tick ->
            val lr = layoutResult
            val match = tab.findMatches.getOrNull(tab.findIndex)
            if (tick > 0 && lr != null && match != null) {
                val textLen = lr.layoutInput.text.length
                if (textLen > 0) {
                    val offset = match.first.coerceIn(0, textLen - 1)
                    val line = lr.getLineForOffset(offset)
                    val targetY = (lr.getLineTop(line) - verticalScroll.viewportSize / 3f).toInt().coerceAtLeast(0)
                    verticalScroll.scrollTo(targetY)
                    val box = lr.getBoundingBox(offset)
                    val viewLeft = horizontalScroll.value.toFloat()
                    val viewRight = viewLeft + horizontalScroll.viewportSize
                    if (box.left < viewLeft || box.right > viewRight - 40f) {
                        horizontalScroll.scrollTo((box.left - 80f).toInt().coerceAtLeast(0))
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorTheme.Background)
            // All wheel/trackpad scrolling for this pane goes through here, one axis per gesture;
            // the verticalScroll/horizontalScroll below no longer see wheel events (see WheelScroll.kt).
            .axisLockedWheelScroll(
                verticalScroll,
                horizontalScroll,
                scrollHorizontally = { horizontalScroll.dispatchRawDelta(it) },
                scrollVertically = { verticalScroll.dispatchRawDelta(it) },
            ),
    ) {
        LineGutter(
            text = tab.textField.text,
            layoutResult = layoutResult,
            scrollState = verticalScroll,
            fontSize = state.fontSize,
            cursor = tab.textField.selection.start,
        )
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScroll)
                    .horizontalScroll(horizontalScroll)
                    .pointerInput(tab.id, pane.id) {
                        detectTapGestures {
                            state.focusedPaneId = pane.id
                            val end = tab.textField.text.length
                            tab.textField = tab.textField.copy(selection = TextRange(end))
                            focusRequester.requestFocus()
                        }
                    }
                    // Drawn in the scrolled content's own coordinates, behind the text: the caret line's
                    // highlight and the find matches. Both read only the existing layoutResult and the
                    // visible line range, so neither adds per-keystroke work proportional to the document.
                    .drawBehind {
                        val lr = layoutResult ?: return@drawBehind
                        val textLen = lr.layoutInput.text.length
                        val padX = 8.dp.toPx()
                        val padY = 6.dp.toPx()

                        val selection = tab.textField.selection
                        if (selection.collapsed && lr.lineCount > 0) {
                            val line = lr.getLineForOffset(selection.start.coerceIn(0, textLen))
                            val top = lr.getLineTop(line)
                            drawRect(
                                color = EditorTheme.SurfaceVariant,
                                topLeft = Offset(0f, padY + top),
                                size = Size(size.width, lr.getLineBottom(line) - top),
                            )
                        }

                        val matches = tab.findMatches
                        if (tab.findOpen && matches.isNotEmpty() && textLen > 0 && lr.lineCount > 0) {
                            val viewTop = (verticalScroll.value - padY).coerceAtLeast(0f)
                            val viewBottom = viewTop + verticalScroll.viewportSize
                            val firstChar = lr.getLineStart(lr.getLineForVerticalPosition(viewTop))
                            val lastChar = lr.getLineEnd(lr.getLineForVerticalPosition(viewBottom))
                            var index = matches.binarySearch { it.first.compareTo(firstChar) }
                            if (index < 0) index = -index - 1
                            if (index > 0) index--
                            val currentIndex = tab.findIndex
                            while (index < matches.size) {
                                val match = matches[index]
                                if (match.first > lastChar) break
                                val start = match.first
                                val endInclusive = match.last.coerceAtMost(textLen - 1)
                                if (start in 0 until textLen && endInclusive >= start) {
                                    val startBox = lr.getBoundingBox(start)
                                    val sameLine = lr.getLineForOffset(start) == lr.getLineForOffset(endInclusive)
                                    val right = if (sameLine) lr.getBoundingBox(endInclusive).right else size.width
                                    drawRect(
                                        color = if (index == currentIndex) EditorTheme.Accent.copy(alpha = 0.45f) else EditorTheme.Outline,
                                        topLeft = Offset(padX + startBox.left, padY + startBox.top),
                                        size = Size((right - startBox.left).coerceAtLeast(2f), startBox.height),
                                    )
                                }
                                index++
                            }
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
                    visualTransformation = transformation,
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(verticalScroll),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(bottom = ScrollbarClearance),
                style = editorScrollbarStyle(),
            )
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontalScroll),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(end = ScrollbarClearance),
                style = editorScrollbarStyle(),
            )
        }
        // Only worth its width when there is something to scroll: a file that fits on screen
        // gets the full pane instead. maxValue is Int.MAX_VALUE until the first layout pass.
        val scrollRange = verticalScroll.maxValue
        if (scrollRange > 0 && scrollRange != Int.MAX_VALUE) {
            Minimap(
                text = tab.textField.text,
                transformation = transformation,
                scrollState = verticalScroll,
            )
        }
    }
}
