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

@Composable
internal fun LineGutter(
    text: String,
    layoutResult: TextLayoutResult?,
    scrollState: ScrollState,
    fontSize: Int,
) {
    val gutterStyle = TextStyle(
        color = EditorTheme.GutterText,
        fontSize = fontSize.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = (fontSize * 1.4).sp,
        textAlign = TextAlign.End,
    )
    val measurer = rememberTextMeasurer()
    val lineCount = remember(text) { 1 + text.count { it == '\n' } }
    val widestNumber = remember(lineCount) { "9".repeat(lineCount.toString().length.coerceAtLeast(2)) }
    val measuredWidthPx = remember(widestNumber, fontSize) {
        measurer.measure(widestNumber, gutterStyle.copy(textAlign = TextAlign.Start)).size.width
    }
    val density = LocalDensity.current
    val contentWidth = with(density) { measuredWidthPx.toDp() }
    val totalWidth = contentWidth + 24.dp

    val lineStartOffsets = remember(text) {
        val list = mutableListOf(0)
        for (i in text.indices) {
            if (text[i] == '\n') list += i + 1
        }
        list
    }

    val contentHeightDp = layoutResult?.let { with(density) { it.size.height.toDp() } } ?: 0.dp

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(totalWidth)
            .background(EditorTheme.Surface)
            .verticalScroll(scrollState)
            .padding(start = 8.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(contentHeightDp)) {
            if (layoutResult != null) {
                val textLen = layoutResult.layoutInput.text.length
                lineStartOffsets.forEachIndexed { idx, startOffset ->
                    val safeOffset = startOffset.coerceAtMost(textLen)
                    val visualLine = layoutResult.getLineForOffset(safeOffset)
                    val topPx = layoutResult.getLineTop(visualLine)
                    val topDp = with(density) { topPx.toDp() }
                    Text(
                        text = (idx + 1).toString(),
                        style = gutterStyle,
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
