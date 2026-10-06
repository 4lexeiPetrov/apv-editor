package com.apv.editor.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Largest index in this sorted (ascending) list whose value is <= [target], or -1 if none. */
private fun floorIndex(sorted: List<Int>, target: Int): Int {
    val r = sorted.binarySearch(target)
    return if (r >= 0) r else (-r - 2)
}

@Composable
internal fun LineGutter(
    text: String,
    layoutResult: TextLayoutResult?,
    scrollState: ScrollState,
    fontSize: Int,
    /** Caret offset in [text]; the logical line containing it gets the accent colour. */
    cursor: Int = -1,
) {
    val gutterStyle = TextStyle(
        color = EditorTheme.GutterText,
        fontSize = fontSize.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = (fontSize * 1.4).sp,
        textAlign = TextAlign.End,
    )
    val activeStyle = gutterStyle.copy(color = EditorTheme.Accent)
    val measurer = rememberTextMeasurer()
    val lineCount = remember(text) { 1 + text.count { it == '\n' } }
    val widestNumber = remember(lineCount) { "9".repeat(lineCount.toString().length.coerceAtLeast(2)) }
    val measuredWidthPx = remember(widestNumber, fontSize) {
        measurer.measure(widestNumber, gutterStyle.copy(textAlign = TextAlign.Start)).size.width
    }
    val density = LocalDensity.current
    val contentWidth = with(density) { measuredWidthPx.toDp() }
    val totalWidth = contentWidth + 24.dp

    // Sorted ascending by construction (one entry per '\n'), which is what lets the render loop
    // below find the visible slice with a binary search instead of a linear scan.
    val lineStartOffsets = remember(text) {
        val list = mutableListOf(0)
        for (i in text.indices) {
            if (text[i] == '\n') list += i + 1
        }
        list
    }

    val activeLineIndex = if (cursor < 0) -1 else floorIndex(lineStartOffsets, cursor)

    val contentHeightDp = layoutResult?.let { with(density) { it.size.height.toDp() } } ?: 0.dp

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(totalWidth)
            .background(EditorTheme.Background)
            .verticalScroll(scrollState)
            .padding(start = 8.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(contentHeightDp)) {
            if (layoutResult != null && layoutResult.lineCount > 0) {
                val textLen = layoutResult.layoutInput.text.length
                val visualLineCount = layoutResult.lineCount

                // Only the lines actually on screen (plus a comfortable overscan buffer, so a fast
                // scroll or a fling doesn't show a blank gutter for a frame) need a Text composable
                // here — previously every logical line in the whole document got one unconditionally,
                // which made gutter rendering O(document size) per recomposition instead of O(viewport
                // size). scrollState and layoutResult are the same ones the main text field already
                // computes, shared via EditorPane, so no extra measurement work is introduced.
                val overscanLines = 30
                val viewTop = scrollState.value.toFloat()
                val viewBottom = (scrollState.value + scrollState.viewportSize).toFloat()
                val firstVisualLine = (layoutResult.getLineForVerticalPosition(viewTop) - overscanLines)
                    .coerceIn(0, visualLineCount - 1)
                val lastVisualLine = (layoutResult.getLineForVerticalPosition(viewBottom) + overscanLines)
                    .coerceIn(0, visualLineCount - 1)
                val startChar = layoutResult.getLineStart(firstVisualLine)
                val endChar = layoutResult.getLineEnd(lastVisualLine).coerceAtMost(textLen)

                // lineStartOffsets is sorted, so this gives the logical-line index range covering
                // [startChar, endChar] in O(log n) instead of a full scan of every line in the doc.
                val loIdx = floorIndex(lineStartOffsets, startChar).coerceAtLeast(0)
                val hiIdx = floorIndex(lineStartOffsets, endChar).coerceIn(0, lineStartOffsets.lastIndex)

                for (idx in loIdx..hiIdx) {
                    val startOffset = lineStartOffsets[idx]
                    val safeOffset = startOffset.coerceAtMost(textLen)
                    val visualLine = layoutResult.getLineForOffset(safeOffset)
                    val topPx = layoutResult.getLineTop(visualLine)
                    val topDp = with(density) { topPx.toDp() }
                    Text(
                        text = (idx + 1).toString(),
                        style = if (idx == activeLineIndex) activeStyle else gutterStyle,
                        softWrap = false,
                        modifier = Modifier
                            .offset(y = topDp)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}
