package com.apv.editor.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sublime-style minimap: a scaled-down, non-editable render of the whole document, with a
 * draggable/clickable box showing which slice [scrollState] currently has in view.
 *
 * Only wired up for [EditorPane] (regular, non-large-file tabs). [LargeEditorPane] intentionally
 * never measures its whole document at once (see that file's class doc) — rendering an accurate
 * minimap would mean reading and laying out every line, defeating the point of that virtualized
 * path, so large-file tabs simply don't get one.
 *
 * Rendering strategy: the entire document is laid out once as ordinary (tiny-font) text — cheap
 * here because [EditorPane] itself is only ever used below [com.apv.editor.state.LargeFileGuard]'s
 * size ceiling — then shrunk to fit the available height with a single [graphicsLayer] scale
 * transform, rather than by shrinking the font size further. That keeps glyph rendering in Skia's
 * normal size range (an arbitrarily small font size degrades badly) while still letting very long
 * documents compress down to fit, exactly like Sublime's own minimap does.
 *
 * [transformation] is the same [HighlightTransformation] instance [EditorPane] passes to its
 * `BasicTextField`: since both call `filter` with the same backing `String` (Kotlin strings are
 * immutable, so `tab.textField.text` is the same object either call sees), whichever call runs
 * first tokenizes and the other reuses its cached result for free — see that class's doc comment.
 */
private val MinimapWidth = 96.dp
private const val MinimapFontSize = 4
private const val MinIndicatorHeightPx = 6f

@Composable
internal fun Minimap(
    text: String,
    transformation: HighlightTransformation,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val annotated = remember(text, transformation) { transformation.filter(AnnotatedString(text)).text }

    // Both populated via onSizeChanged/onTextLayout below; geometry is all-zero (and the indicator
    // hidden) until the first layout pass reports real numbers.
    var viewportHeightPx by remember { mutableStateOf(0) }
    var naturalHeightPx by remember { mutableStateOf(0) }

    val geometry = minimapGeometry(naturalHeightPx, viewportHeightPx, scrollState)

    Box(
        modifier = modifier
            .width(MinimapWidth)
            .fillMaxHeight()
            .background(EditorTheme.Surface)
            .clipToBounds()
            .onSizeChanged { viewportHeightPx = it.height }
            .pointerInput(scrollState) {
                // Manual down→drag→up loop (rather than detectDragGestures) so a plain click with
                // no movement still jumps the scroll position — detectDragGestures only recognizes
                // a gesture once movement clears touch slop, which would make a single click on the
                // minimap do nothing.
                while (true) {
                    awaitPointerEventScope {
                        val down = awaitFirstDown()
                        jumpTo(down.position.y, naturalHeightPx, viewportHeightPx, scrollState, scope)
                        down.consume()
                        drag(down.id) { change ->
                            jumpTo(change.position.y, naturalHeightPx, viewportHeightPx, scrollState, scope)
                            change.consume()
                        }
                    }
                }
            },
    ) {
        Text(
            text = annotated,
            color = EditorTheme.OnSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = MinimapFontSize.sp,
            lineHeight = (MinimapFontSize * 1.4).sp,
            softWrap = false,
            onTextLayout = { layoutResult -> naturalHeightPx = layoutResult.size.height },
            modifier = Modifier.graphicsLayer(
                scaleX = geometry.scale,
                scaleY = geometry.scale,
                transformOrigin = TransformOrigin(0f, 0f),
            ),
        )

        if (geometry.indicatorHeightPx > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(x = 0, y = geometry.indicatorTopPx.roundToInt()) }
                    .height(with(density) { geometry.indicatorHeightPx.toDp() })
                    .background(EditorTheme.OnSurface.copy(alpha = 0.08f)),
            )
        }
    }
}

private data class MinimapGeometry(
    val scale: Float,
    val displayedHeightPx: Float,
    val indicatorHeightPx: Float,
    val indicatorTopPx: Float,
)

/**
 * Derives every pixel measurement the minimap needs from the raw inputs. Pulled out as a plain
 * function (rather than inlined where it's used) so the pointer handler below can recompute it
 * fresh on every down/drag event — it reads [ScrollState.value] at the moment of the event rather
 * than a value captured when the gesture started, which is what lets a click-drag track the
 * pointer continuously instead of only reacting to the initial press.
 */
private fun minimapGeometry(naturalHeightPx: Int, viewportHeightPx: Int, scrollState: ScrollState): MinimapGeometry {
    if (naturalHeightPx <= 0 || viewportHeightPx <= 0) return MinimapGeometry(1f, 0f, 0f, 0f)

    // Never scale up: a short document is shown at its natural (tiny-font) size, top-aligned,
    // with blank space below — matching Sublime, which doesn't stretch a short file's minimap to
    // fill the pane either.
    val scale = minOf(1f, viewportHeightPx.toFloat() / naturalHeightPx.toFloat())
    val displayedHeightPx = naturalHeightPx * scale

    val scrollMax = scrollState.maxValue
    val scrollViewport = scrollState.viewportSize
    val visibleFraction = if (scrollMax > 0) {
        (scrollViewport.toFloat() / (scrollViewport + scrollMax).toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }
    val indicatorHeightPx = (displayedHeightPx * visibleFraction)
        .coerceAtLeast(minOf(MinIndicatorHeightPx, displayedHeightPx))
        .coerceAtMost(displayedHeightPx)

    val usableRange = (displayedHeightPx - indicatorHeightPx).coerceAtLeast(0f)
    val scrollFraction = if (scrollMax > 0) (scrollState.value.toFloat() / scrollMax.toFloat()).coerceIn(0f, 1f) else 0f
    val indicatorTopPx = usableRange * scrollFraction

    return MinimapGeometry(scale, displayedHeightPx, indicatorHeightPx, indicatorTopPx)
}

/** Scrolls [scrollState] so the indicator centers on pointer position [y] (in the minimap's own coordinate space). No-op once the document already fits without scrolling. */
private fun jumpTo(
    y: Float,
    naturalHeightPx: Int,
    viewportHeightPx: Int,
    scrollState: ScrollState,
    scope: CoroutineScope,
) {
    if (scrollState.maxValue <= 0) return
    val geometry = minimapGeometry(naturalHeightPx, viewportHeightPx, scrollState)
    val usableRange = (geometry.displayedHeightPx - geometry.indicatorHeightPx).coerceAtLeast(0f)
    if (usableRange <= 0f) return
    val target = (y - geometry.indicatorHeightPx / 2f).coerceIn(0f, usableRange)
    val fraction = target / usableRange
    val targetScroll = (fraction * scrollState.maxValue).roundToInt().coerceIn(0, scrollState.maxValue)
    scope.launch { scrollState.scrollTo(targetScroll) }
}
