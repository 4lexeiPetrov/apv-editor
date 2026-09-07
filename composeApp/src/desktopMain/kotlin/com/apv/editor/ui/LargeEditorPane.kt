package com.apv.editor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.largefile.LineCol
import com.apv.editor.state.EditorState
import com.apv.editor.state.LargeDocController
import com.apv.editor.state.PaneState
import com.apv.editor.state.TabState
import com.apv.editor.syntax.SyntaxHighlighter
import com.apv.editor.syntax.SyntaxLanguage
import com.apv.editor.syntax.Token
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Editing surface for a file [com.apv.editor.state.LargeFileGuard] classified as too large for the
 * single-`BasicTextField` [EditorPane]. Each visible line is one [LazyColumn] item, so — unlike
 * [EditorPane] — only a bounded window of lines is ever measured or laid out, regardless of the
 * document's total size; that's what avoids the Compose `Constraints` bit-packing crash
 * [com.apv.editor.state.LargeFileGuard] documents.
 *
 * Only the line the caret is on ([TabState.largeCaret]) is a real, focused `BasicTextField`
 * ([ActiveLineField]); every other visible line is a plain, non-editable [Text] ([PassiveLine]).
 * Clicking a passive line or pressing Up/Down/Left/Right at the active line's edge moves
 * [TabState.largeCaret], which makes that line become the new active field on the next
 * recomposition. This keeps focus management to a single [FocusRequester] instead of one per
 * visible row.
 *
 * Selection ([TabState.largeSelectionAnchor] paired with [TabState.largeCaret]) can span multiple
 * lines via Shift+Arrow; passive lines draw their own portion of it directly (there's no single
 * `TextFieldValue.selection` spanning rows the way there is within one line), and Cmd+C/X/V are
 * intercepted at the active line specifically when the selection extends onto another line, since
 * the field's own clipboard handling only knows about its own text.
 *
 * Long lines are not soft-wrapped (see [PassiveLine] and [ActiveLineField]: wrapping would make a
 * row's height depend on its content, which [LazyColumn] can't cheaply account for at this
 * document scale), so horizontal scrolling is how the rest of a long line is reached instead.
 * Every row applies the *same* plain pixel offset, [hScrollPx][LargeEditorPane] — tracked by hand,
 * not a shared Compose `ScrollState`/`Modifier.horizontalScroll`. That API's own `maxValue` is
 * overwritten by whichever row last measured, so sharing one `ScrollState` across rows of very
 * different natural width (a long URL versus a short id) meant the *shortest currently-visible*
 * row kept silently coercing the shared value back down to its own (small) max — snapping every
 * longer row backward, and leaving a short row looking permanently "stuck" instead of scrolling
 * out of view like the others. The fix: each row measures at its own natural width regardless of
 * its neighbors (`Modifier.wrapContentWidth(unbounded = true)`), gets shifted by the identical
 * `hScrollPx` (`Modifier.offset`), and has whatever that pushes outside its own slot hidden
 * (`Modifier.clipToBounds()`) — so a short line simply slides fully out of view, revealing blank
 * space, exactly like a long one, and nothing about it can pull any *other* row backward. The
 * widest row measured so far is tracked in `maxLineWidthPx` (only ever grows, as further rows are
 * measured) to bound how far right the shared offset — and the [LargeHorizontalScrollbar] below
 * the list — is allowed to go.
 *
 * Remaining known gaps versus [EditorPane]: no mouse drag-select or Shift+click across lines
 * (Shift+Arrow works; a plain click always collapses to a single point); syntax highlighting for
 * the *active* line is still computed in isolation (only passive lines carry cross-line
 * comment/string state, via [HighlightStateCache] — see [SyntaxHighlighter.highlightLine]); and
 * bracket rainbow-depth doesn't nest across lines even on passive lines.
 */
private const val SEED_MEASURE_CHAR_CAP = 4096

/** See [HighlightStateCache]'s class doc. */
private const val SYNC_WALK_LIMIT = 2000

/**
 * How many lines beyond each edge of the visible window the background prefetch effect (in
 * [LargeEditorPane]) tries to keep warm in [com.apv.editor.largefile.LargeDocument]'s
 * sourceLineCache. Large enough to comfortably outrun a sustained scroll/fling before it reaches
 * un-prefetched territory; small enough that a jump to a far-off line (dragging the scrollbar deep
 * into the file) doesn't kick off a huge background read for a range you may scroll straight past.
 */
private const val PREFETCH_MARGIN_LINES = 150

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LargeEditorPane(tab: TabState, pane: PaneState, state: EditorState) {
    val controller = tab.largeDoc ?: return
    val listState = rememberLazyListState()
    val hScrollPx = remember(tab.id) { mutableStateOf(0f) }
    val focusRequester = remember(tab.id) { FocusRequester() }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val maxLineWidthPx = remember(tab.id) {
        // Seed from the document's actual content instead of starting at 0f, which otherwise left
        // the horizontal scrollbar (and hScrollableState's own clamp) reporting a too-small max
        // until the file's genuinely longest line happened to scroll into view and get measured
        // for real — see the class doc for how each row's true width grows this value further
        // from here on. controller.maxKnownLineLength is itself a safe over-estimate (raw UTF-8
        // byte count from the initial disk scan, always >= true character count); capped below so
        // a file with one absurdly long line doesn't spend real time up front measuring a
        // multi-million-character string just to open.
        val seedChars = controller.maxKnownLineLength.coerceIn(0, SEED_MEASURE_CHAR_CAP)
        val seedWidth = if (seedChars > 0) {
            measurer.measure(
                AnnotatedString("0".repeat(seedChars)),
                TextStyle(fontFamily = FontFamily.Monospace, fontSize = state.fontSize.sp),
            ).size.width.toFloat()
        } else {
            0f
        }
        mutableStateOf(seedWidth)
    }
    val highlightCache = remember(tab.id, controller, tab.language) { HighlightStateCache(controller, tab.language) }

    LaunchedEffect(tab.id, controller) {
        snapshotFlow { controller.revision }
            .distinctUntilChanged()
            .collect { state.scheduleAutosave(tab) }
    }

    // Fires when this tab stops being the active/displayed one (switched away from, closed, or
    // its pane closed) — see EditorState.autosaveOnFocusLost for why this, rather than raw
    // BasicTextField focus events, is what AutosaveMode.OnFocusChange listens for here.
    DisposableEffect(tab.id) {
        onDispose { state.autosaveOnFocusLost(tab) }
    }

    // Keep the caret's line scrolled into view when it moves off-screen — Up/Down at the visible
    // window's edge, Home/End jumps, undo/redo. Direct clicks/scrolling are already handled by
    // the LazyColumn itself and don't need this.
    LaunchedEffect(tab.id, controller) {
        snapshotFlow { tab.largeCaret.line }.collect { line ->
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index
            val last = visible.lastOrNull()?.index
            if (first == null || line < first || (last != null && line > last)) {
                listState.scrollToItem((line - 3).coerceAtLeast(0))
            }
        }
    }

    // Safety net for LargeDocument's disk-streaming fallback path only (a source file too big to
    // hold resident in memory — see LargeDocument's class doc for the size budget and why the
    // common case now needs none of this at all: an in-memory line read is already fast enough
    // not to need prefetching). For that fallback path, this reads a margin of lines around the
    // visible window on a background thread, so that by the time each row's own
    // remember(index, controller.revision) { controller.getLine(index) } call runs (synchronously,
    // on the UI thread, as part of composing that row), the line is already warm in
    // LargeDocument's sourceLineCache instead of blocking on raf.seek/readFully — see that cache's
    // doc comment for why a cold read there was the original cause of touchpad scroll
    // stuttering/freezing until the gesture ends, before the in-memory path existed.
    // controller.getLine is safe to call off the UI thread either way (LargeDocument's own doc
    // comment: it's built for exactly this, AutoSaver already reads it from a background thread
    // today), so this effect costs nothing beyond some redundant cache-hit lookups once a file is
    // resident — left in rather than special-cased away, since it's harmless there and still
    // earns its keep on the fallback path.
    //
    // collectLatest is what makes this race-safe during a fast fling: each new scroll position
    // cancels whatever prefetch range was still in flight for the previous one (including
    // mid-loop, via ensureActive()), so a rapid scroll doesn't leave a growing backlog of
    // now-irrelevant background reads competing against the one that actually matters — the
    // window around where the view actually is right now.
    LaunchedEffect(tab.id, controller) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.firstOrNull()?.index to visible.lastOrNull()?.index
        }.collectLatest { (first, last) ->
            if (first == null || last == null) return@collectLatest
            val from = (first - PREFETCH_MARGIN_LINES).coerceAtLeast(0)
            val to = (last + PREFETCH_MARGIN_LINES).coerceAtMost(controller.lineCount - 1)
            withContext(Dispatchers.IO) {
                for (i in from..to) {
                    ensureActive()
                    controller.getLine(i)
                }
            }
        }
    }

    val gutterStyle = TextStyle(
        color = EditorTheme.GutterText,
        fontSize = state.fontSize.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = (state.fontSize * 1.4).sp,
        textAlign = TextAlign.End,
    )
    val widestNumber = remember(controller.lineCount) {
        "9".repeat(controller.lineCount.toString().length.coerceAtLeast(2))
    }
    val gutterContentWidthPx = remember(widestNumber, state.fontSize) {
        measurer.measure(widestNumber, gutterStyle.copy(textAlign = TextAlign.Start)).size.width
    }
    // 16.dp of the digits' own breathing room, plus 8.dp reserved as trailing padding (see
    // the gutter Box below) so the right-aligned number doesn't sit flush against the first
    // character of the line — LineGutter (the small-file editor's equivalent) gets the same
    // separation from its own end-padding.
    val gutterWidth = with(density) { gutterContentWidthPx.toDp() } + 24.dp
    val listHorizontalPadding = 20.dp // start = 8.dp + end = 12.dp, applied to the LazyColumn below

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorTheme.Background)
            .pointerInput(tab.id) {
                detectTapGestures { state.focusedPaneId = pane.id }
            },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Floor width for a row's content field/text: short (or empty) lines still fill the
            // row — matching a plain fillMaxWidth's old click/background behavior — while a longer
            // line is still free to measure wider than this and get scrolled to via hScrollPx.
            val lineContentMinWidth = (maxWidth - gutterWidth - listHorizontalPadding).coerceAtLeast(0.dp)
            val viewportWidthPx = with(density) { lineContentMinWidth.toPx() }

            // Every row's Modifier.scrollable below feeds gestures through this one handler, which
            // is what actually keeps every visible line — however long or short — moving in
            // lockstep; see the class doc for why this replaces a shared Compose ScrollState.
            val hScrollableState = rememberScrollableState { delta ->
                val maxScroll = (maxLineWidthPx.value - viewportWidthPx).coerceAtLeast(0f)
                val new = (hScrollPx.value + delta).coerceIn(0f, maxScroll)
                val consumed = new - hScrollPx.value
                hScrollPx.value = new
                consumed
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(start = 8.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
            ) {
                items(count = controller.lineCount) { index ->
                    LargeDocumentLineRow(
                        tab = tab,
                        controller = controller,
                        index = index,
                        pane = pane,
                        state = state,
                        focusRequester = focusRequester,
                        gutterWidth = gutterWidth,
                        gutterStyle = gutterStyle,
                        highlightCache = highlightCache,
                        hScrollPx = hScrollPx,
                        maxLineWidthPx = maxLineWidthPx,
                        hScrollableState = hScrollableState,
                        viewportWidthPx = viewportWidthPx,
                        lineContentMinWidth = lineContentMinWidth,
                        listState = listState,
                    )
                }
            }

            LargeHorizontalScrollbar(
                hScrollPx = hScrollPx,
                maxLineWidthPx = maxLineWidthPx,
                viewportWidthPx = viewportWidthPx,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = gutterWidth + 8.dp, end = 12.dp),
            )

            // Unlike the horizontal scrollbar above, listState's own extent/position is exactly
            // what a standard ScrollbarAdapter wants (one LazyColumn, no per-row measurement
            // quirks) — no hand-rolled equivalent needed here.
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(bottom = 14.dp),
                style = editorScrollbarStyle(),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LargeDocumentLineRow(
    tab: TabState,
    controller: LargeDocController,
    index: Int,
    pane: PaneState,
    state: EditorState,
    focusRequester: FocusRequester,
    gutterWidth: Dp,
    gutterStyle: TextStyle,
    highlightCache: HighlightStateCache,
    hScrollPx: MutableState<Float>,
    maxLineWidthPx: MutableState<Float>,
    hScrollableState: ScrollableState,
    viewportWidthPx: Float,
    lineContentMinWidth: Dp,
    listState: LazyListState,
) {
    val isActive = tab.largeCaret.line == index
    val content = remember(index, controller.revision) { controller.getLine(index) }

    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(gutterWidth).padding(end = 8.dp)) {
            Text(text = (index + 1).toString(), style = gutterStyle, softWrap = false, modifier = Modifier.fillMaxWidth())
        }
        Box(
            modifier = Modifier
                .weight(1f)
                // Bounds this row's content to exactly the row's own slot (matching the old
                // fillMaxWidth) and hides whatever a long line's natural width pushes past it —
                // Modifier.wrapContentWidth(unbounded = true) below lets that content measure
                // wider than this Box in the first place, so nothing here does that clipping for
                // free the way Modifier.horizontalScroll used to.
                .clipToBounds()
                .scrollable(
                    state = hScrollableState,
                    orientation = Orientation.Horizontal,
                    // Without this, a trackpad/wheel swipe scrolled the line the opposite way from
                    // every other scroll surface in the app (LineGutter/EditorPane's verticalScroll,
                    // the LazyColumn below, LargeHorizontalScrollbar's own drag handle) — see the
                    // class doc's note on hScrollPx and the `-hScrollPx` offset this feeds.
                    reverseDirection = true,
                    // Modifier.scrollable has no axis-locking of its own: a trackpad swipe that's
                    // meant to be purely vertical almost always carries a little incidental x-jitter,
                    // and without this guard every row's horizontal scrollable would happily consume
                    // that jitter, dragging the content sideways while the user scrolls down. Gating
                    // on the LazyColumn's own in-progress flag disables horizontal capture for the
                    // duration of an active vertical scroll gesture, so only a deliberate horizontal
                    // swipe (started while the list is at rest) moves hScrollPx.
                    enabled = !listState.isScrollInProgress,
                ),
        ) {
            if (isActive) {
                ActiveLineField(
                    tab = tab,
                    controller = controller,
                    index = index,
                    content = content,
                    state = state,
                    focusRequester = focusRequester,
                    hScrollPx = hScrollPx,
                    maxLineWidthPx = maxLineWidthPx,
                    viewportWidthPx = viewportWidthPx,
                    lineContentMinWidth = lineContentMinWidth,
                )
            } else {
                PassiveLine(
                    tab = tab,
                    controller = controller,
                    index = index,
                    content = content,
                    pane = pane,
                    state = state,
                    highlightCache = highlightCache,
                    hScrollPx = hScrollPx,
                    maxLineWidthPx = maxLineWidthPx,
                    lineContentMinWidth = lineContentMinWidth,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ActiveLineField(
    tab: TabState,
    controller: LargeDocController,
    index: Int,
    content: String,
    state: EditorState,
    focusRequester: FocusRequester,
    hScrollPx: MutableState<Float>,
    maxLineWidthPx: MutableState<Float>,
    viewportWidthPx: Float,
    lineContentMinWidth: Dp,
) {
    var fieldValue by remember(index, controller.revision) {
        val anchor = tab.largeSelectionAnchor
        val len = content.length
        val caretCol = tab.largeCaret.col.coerceIn(0, len)
        val selection = when {
            anchor == null -> TextRange(caretCol)
            anchor.line == index -> TextRange(anchor.col.coerceIn(0, len), caretCol)
            // Selection reaches this (active) line from an earlier one: the part of it that
            // belongs to this line runs from its own start up to the caret.
            anchor.line < index -> TextRange(0, caretCol)
            // Selection reaches this line from a later one: this line's part of it runs from the
            // caret to its own end.
            else -> TextRange(caretCol, len)
        }
        mutableStateOf(TextFieldValue(content, selection))
    }
    val clipboard = LocalClipboardManager.current
    var layoutResult by remember(index, controller.revision) { mutableStateOf<TextLayoutResult?>(null) }

    LaunchedEffect(index, controller.revision) {
        focusRequester.requestFocus()
    }

    // Bring the caret back into the visible [hScrollPx, hScrollPx + viewportWidthPx) window
    // whenever it moves outside it — typing or arrowing past either edge, Home/End, or landing
    // here via a vertical move from another line. A plain BasicTextField wrapped in
    // Modifier.horizontalScroll gets this for free; our per-row scroll is driven by hand (see the
    // class doc), so nothing does it unless this does.
    LaunchedEffect(fieldValue.selection, layoutResult) {
        val lr = layoutResult ?: return@LaunchedEffect
        val textLen = lr.layoutInput.text.length
        val caretIndex = fieldValue.selection.end.coerceIn(0, textLen)
        val caretX = lr.getHorizontalPosition(caretIndex, true)
        val maxScroll = (maxLineWidthPx.value - viewportWidthPx).coerceAtLeast(0f)
        val current = hScrollPx.value
        val target = when {
            caretX < current -> caretX
            caretX > current + viewportWidthPx -> caretX - viewportWidthPx
            else -> current
        }
        hScrollPx.value = target.coerceIn(0f, maxScroll)
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { new ->
            if (new.text == fieldValue.text) {
                // Pure caret/selection move within this line (arrow keys, click, native drag-select).
                tab.largeCaret = LineCol(index, new.selection.end)
                tab.largeSelectionAnchor = if (new.selection.collapsed) null else LineCol(index, new.selection.start)
                fieldValue = new
                return@BasicTextField
            }

            val anchor = tab.largeSelectionAnchor
            if (anchor != null && anchor.line != index) {
                // A selection that extends onto another line had its portion on *this* line
                // replaced by typing/IME — reconstruct what was inserted and apply it as a
                // replacement of the FULL cross-line selection, not just this line's slice of it.
                val oldSel = fieldValue.selection
                val selMin = minOf(oldSel.start, oldSel.end).coerceIn(0, fieldValue.text.length)
                val selMax = maxOf(oldSel.start, oldSel.end).coerceIn(0, fieldValue.text.length)
                val suffixLen = fieldValue.text.length - selMax
                if (new.text.length >= selMin + suffixLen) {
                    val inserted = new.text.substring(selMin, new.text.length - suffixLen)
                    val (s, e) = orderedSelection(anchor, LineCol(index, tab.largeCaret.col))
                    val caret = controller.edit(s, e, inserted)
                    tab.largeCaret = caret
                    tab.largeSelectionAnchor = null
                    return@BasicTextField
                }
                tab.largeSelectionAnchor = null
            }

            // Whole-line replace covers both a same-line edit and a paste that slipped embedded
            // newlines through despite singleLine mode — controller.edit splits on '\n' either way,
            // and new.text already reflects the field's full resulting content in both cases.
            val caret = controller.edit(LineCol(index, 0), LineCol(index, fieldValue.text.length), new.text)
            tab.largeCaret = caret
            if (!new.text.contains('\n')) fieldValue = new
        },
        singleLine = true,
        textStyle = TextStyle(
            color = EditorTheme.OnSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = state.fontSize.sp,
            lineHeight = (state.fontSize * 1.4).sp,
        ),
        cursorBrush = SolidColor(EditorTheme.Accent),
        visualTransformation = remember(tab.language) { HighlightTransformation(tab.language) },
        onTextLayout = { lr ->
            layoutResult = lr
            if (lr.size.width > maxLineWidthPx.value) maxLineWidthPx.value = lr.size.width.toFloat()
        },
        modifier = Modifier
            // Ancestor-first: lets this field measure at its own natural width regardless of the
            // row's visible slot (wrapContentWidth), floors that at the slot's width so a short
            // line still fills it (widthIn), then shifts the whole thing by the shared scroll
            // offset (offset) — see the class doc.
            .wrapContentWidth(unbounded = true, align = Alignment.Start)
            .widthIn(min = lineContentMinWidth)
            .offset { IntOffset(x = -hScrollPx.value.roundToInt(), y = 0) }
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                handleLargeLineKeyEvent(event, tab, controller, index, fieldValue, clipboard)
            },
    )
}

private fun orderedSelection(a: LineCol, b: LineCol): Pair<LineCol, LineCol> =
    if (a.line < b.line || (a.line == b.line && a.col <= b.col)) a to b else b to a

private val ARROW_KEYS = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

private fun handleLargeLineKeyEvent(
    event: KeyEvent,
    tab: TabState,
    controller: LargeDocController,
    index: Int,
    fieldValue: TextFieldValue,
    clipboard: ClipboardManager,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val col = fieldValue.selection.end
    val shift = event.isShiftPressed
    val anchor = tab.largeSelectionAnchor

    // A plain (non-shift) arrow key with an existing selection collapses to that selection's
    // edge first, matching ordinary text-editor behavior, rather than moving one further step —
    // this only matters when the selection reaches beyond this line, since a same-line selection
    // is already handled by the field's own native arrow-key behavior.
    if (!shift && anchor != null && anchor.line != index && event.key in ARROW_KEYS) {
        val (s, e) = orderedSelection(anchor, LineCol(index, col))
        tab.largeSelectionAnchor = null
        tab.largeCaret = if (event.key == Key.DirectionLeft || event.key == Key.DirectionUp) s else e
        return true
    }

    fun moveCaretTo(target: LineCol) {
        if (shift) {
            if (tab.largeSelectionAnchor == null) tab.largeSelectionAnchor = LineCol(index, col)
        } else {
            tab.largeSelectionAnchor = null
        }
        tab.largeCaret = target
    }

    if ((event.key == Key.C || event.key == Key.X) && event.isMetaPressed && anchor != null && anchor.line != index) {
        val (s, e) = orderedSelection(anchor, LineCol(index, col))
        clipboard.setText(AnnotatedString(controller.textInRange(s, e)))
        if (event.key == Key.X) {
            val caret = controller.edit(s, e, "")
            tab.largeCaret = caret
            tab.largeSelectionAnchor = null
        }
        return true
    }

    if (event.key == Key.V && event.isMetaPressed && anchor != null && anchor.line != index) {
        val text = clipboard.getText()?.text ?: return true
        val (s, e) = orderedSelection(anchor, LineCol(index, col))
        val caret = controller.edit(s, e, text)
        tab.largeCaret = caret
        tab.largeSelectionAnchor = null
        return true
    }

    if (event.key == Key.A && event.isMetaPressed) {
        val lastLine = controller.lineCount - 1
        tab.largeSelectionAnchor = LineCol(0, 0)
        tab.largeCaret = LineCol(lastLine, controller.getLine(lastLine).length)
        return true
    }

    return when (event.key) {
        Key.Enter, Key.NumPadEnter -> {
            val (from, to) = if (anchor != null) orderedSelection(anchor, LineCol(index, col)) else LineCol(index, col) to LineCol(index, col)
            val caret = controller.edit(from, to, "\n")
            tab.largeCaret = caret
            tab.largeSelectionAnchor = null
            true
        }
        Key.Backspace -> {
            if (anchor != null) {
                val (from, to) = orderedSelection(anchor, LineCol(index, col))
                val caret = controller.edit(from, to, "")
                tab.largeCaret = caret
                tab.largeSelectionAnchor = null
                true
            } else if (col == 0 && fieldValue.selection.collapsed && index > 0) {
                val prevLen = controller.getLine(index - 1).length
                val caret = controller.edit(LineCol(index - 1, prevLen), LineCol(index, 0), "")
                tab.largeCaret = caret
                true
            } else {
                false
            }
        }
        Key.Delete -> {
            if (anchor != null) {
                val (from, to) = orderedSelection(anchor, LineCol(index, col))
                val caret = controller.edit(from, to, "")
                tab.largeCaret = caret
                tab.largeSelectionAnchor = null
                true
            } else if (col == fieldValue.text.length && fieldValue.selection.collapsed && index < controller.lineCount - 1) {
                val caret = controller.edit(LineCol(index, col), LineCol(index + 1, 0), "")
                tab.largeCaret = caret
                true
            } else {
                false
            }
        }
        Key.DirectionUp -> {
            if (index > 0) {
                val targetCol = col.coerceAtMost(controller.getLine(index - 1).length)
                moveCaretTo(LineCol(index - 1, targetCol))
                true
            } else {
                false
            }
        }
        Key.DirectionDown -> {
            if (index < controller.lineCount - 1) {
                val targetCol = col.coerceAtMost(controller.getLine(index + 1).length)
                moveCaretTo(LineCol(index + 1, targetCol))
                true
            } else {
                false
            }
        }
        Key.DirectionLeft -> {
            if (col == 0 && fieldValue.selection.collapsed && index > 0) {
                moveCaretTo(LineCol(index - 1, controller.getLine(index - 1).length))
                true
            } else if (shift && index > 0 && col == 0) {
                moveCaretTo(LineCol(index - 1, controller.getLine(index - 1).length))
                true
            } else {
                false
            }
        }
        Key.DirectionRight -> {
            if (col == fieldValue.text.length && fieldValue.selection.collapsed && index < controller.lineCount - 1) {
                moveCaretTo(LineCol(index + 1, 0))
                true
            } else if (shift && index < controller.lineCount - 1 && col == fieldValue.text.length) {
                moveCaretTo(LineCol(index + 1, 0))
                true
            } else {
                false
            }
        }
        else -> false
    }
}

@Composable
private fun PassiveLine(
    tab: TabState,
    controller: LargeDocController,
    index: Int,
    content: String,
    pane: PaneState,
    state: EditorState,
    highlightCache: HighlightStateCache,
    hScrollPx: MutableState<Float>,
    maxLineWidthPx: MutableState<Float>,
    lineContentMinWidth: Dp,
) {
    var layoutResult by remember(index, content) { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = remember(content, tab.language, controller.revision) {
        buildHighlightedLine(content, tab.language, index, highlightCache)
    }
    val selection = selectionRangeForLine(tab, content.length, index)

    Text(
        text = annotated,
        fontFamily = FontFamily.Monospace,
        fontSize = state.fontSize.sp,
        lineHeight = (state.fontSize * 1.4).sp,
        color = EditorTheme.OnSurface,
        softWrap = false,
        onTextLayout = { lr ->
            layoutResult = lr
            if (lr.size.width > maxLineWidthPx.value) maxLineWidthPx.value = lr.size.width.toFloat()
        },
        modifier = Modifier
            // See ActiveLineField: same shared hScrollPx, same min-width floor, so a passive line
            // lines up with the active one (and every other passive line) as the row scrolls
            // horizontally in lockstep — including sliding fully out of view if it's shorter than
            // the current scroll offset, rather than getting stuck.
            .wrapContentWidth(unbounded = true, align = Alignment.Start)
            .widthIn(min = lineContentMinWidth)
            .offset { IntOffset(x = -hScrollPx.value.roundToInt(), y = 0) }
            .drawBehind {
                val lr = layoutResult ?: return@drawBehind
                val sel = selection ?: return@drawBehind
                val startX = lr.getHorizontalPosition(sel.first, true)
                val endX = lr.getHorizontalPosition(sel.second, true)
                drawRect(
                    color = EditorTheme.SelectionBackground,
                    topLeft = Offset(minOf(startX, endX), 0f),
                    size = Size(kotlin.math.abs(endX - startX), size.height),
                )
            }
            .pointerInput(index, content) {
                detectTapGestures { offset ->
                    state.focusedPaneId = pane.id
                    val clickedCol = layoutResult?.getOffsetForPosition(offset) ?: 0
                    tab.largeCaret = LineCol(index, clickedCol.coerceIn(0, content.length))
                    tab.largeSelectionAnchor = null
                }
            },
    )
}

/** The [from, to) column range (exclusive end) of the current selection that falls on [index], or null if none applies there — the active line renders its own selection natively instead. */
private fun selectionRangeForLine(tab: TabState, lineLength: Int, index: Int): Pair<Int, Int>? {
    val anchor = tab.largeSelectionAnchor ?: return null
    if (index == tab.largeCaret.line) return null
    val (start, end) = orderedSelection(anchor, tab.largeCaret)
    if (index < start.line || index > end.line) return null
    val from = if (index == start.line) start.col else 0
    val to = if (index == end.line) end.col else lineLength
    if (from >= to) return null
    return from to to
}

/**
 * A thin, draggable indicator of [LargeEditorPane]'s shared horizontal scroll position.
 * Deliberately not `HorizontalScrollbar`/`rememberScrollbarAdapter` — those read a single
 * `ScrollState`'s `maxValue`, which assumes one scrollable content whose width they measured
 * directly. Here the effective "content width" is the widest of many independently-sized rows
 * (see the class doc), so it's tracked by hand in [maxLineWidthPx] and this is sized/positioned
 * from that directly. Hides itself entirely once there's nothing to scroll.
 */
@Composable
private fun LargeHorizontalScrollbar(
    hScrollPx: MutableState<Float>,
    maxLineWidthPx: MutableState<Float>,
    viewportWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    if (viewportWidthPx <= 0f) return
    val totalWidth = maxOf(maxLineWidthPx.value, viewportWidthPx)
    val maxScroll = (totalWidth - viewportWidthPx).coerceAtLeast(0f)
    if (maxScroll <= 0f) return

    var trackWidthPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val thumbFraction = (viewportWidthPx / totalWidth).coerceIn(0.04f, 1f)
    val thumbWidthPx = trackWidthPx * thumbFraction
    val draggableRange = trackWidthPx - thumbWidthPx
    val thumbOffsetPx = if (draggableRange > 0f) {
        draggableRange * (hScrollPx.value / maxScroll).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = modifier
            .height(12.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat() },
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(x = thumbOffsetPx.roundToInt(), y = 0) }
                .width(with(density) { thumbWidthPx.toDp() })
                .fillMaxHeight()
                // 3.dp of vertical padding on a 10.dp track left only a 4.dp-tall thumb in a color
                // (EditorTheme.Border) that barely differs from EditorTheme.Background — easy to
                // miss entirely against syntax-highlighted text. Thicker track, thinner padding,
                // brighter (still muted) color: matches editorScrollbarStyle()'s reasoning in
                // Scrollbars.kt for the two standard scrollbars.
                .padding(vertical = 2.dp)
                .background(EditorTheme.OnSurfaceMuted)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { deltaPx ->
                        if (draggableRange <= 0f) return@rememberDraggableState
                        val scrollPerPx = maxScroll / draggableRange
                        hScrollPx.value = (hScrollPx.value + deltaPx * scrollPerPx).coerceIn(0f, maxScroll)
                    },
                ),
        )
    }
}

/**
 * Lazily computes and caches, for one large-file tab, which multi-line construct (if any) is open
 * at the *start* of each line — see [SyntaxHighlighter.highlightLine]. Needed because passive
 * lines here are tokenized one at a time (materializing the whole document just to get this right
 * would defeat the point of large-file mode), but a block comment or triple-quoted string that
 * spans lines still needs to color correctly across that boundary.
 *
 * States are filled forward, lazily, the first time each line is asked for — scrolling through a
 * huge file normally (wheel/trackpad, Page Down, arrow keys) does a small amount of extra work per
 * newly-seen line, and revisiting an already-seen line is O(1). An edit only invalidates states
 * from the edited line onward — everything before it is provably unaffected — so typing near line
 * 5,000,000 doesn't re-walk the document from the top on every keystroke (see
 * [LargeDocController.lastEditedLine]).
 *
 * Jumping straight to a far-off, never-visited line — dragging the scrollbar deep into a
 * multi-million-line file, or a fast fling — is the one case this deliberately does *not* walk
 * forward for: doing that synchronously, on the UI thread, is exactly what caused the reported
 * multi-second stall scrolling through a ~1MB file (tens of thousands of lines to tokenize before
 * a single frame could render). Past [SYNC_WALK_LIMIT] lines from the last point actually walked
 * through, [tokensFor] instead assumes nothing is open and tokenizes just the requested line —
 * correct for the overwhelmingly common case, and wrong only for however long an open block
 * comment/triple-quoted string happens to span right where the jump landed, which shows up as a
 * few tokens in the wrong color for a moment and self-corrects the instant the user scrolls through
 * that stretch normally. Same kind of cosmetic, self-correcting tradeoff [highlightLine] already
 * makes for bracket rainbow-depth across line boundaries.
 */
internal class HighlightStateCache(
    private val controller: LargeDocController,
    private val language: SyntaxLanguage,
) {
    private val entryStates = ArrayList<SyntaxHighlighter.LineHighlightState>().apply {
        add(SyntaxHighlighter.LineHighlightState.NONE)
    }
    private var computedThrough = 0
    private var lastSeenRevision = controller.revision

    fun tokensFor(line: Int, content: String): List<Token> {
        reconcileWithEdits()
        if (line - computedThrough > SYNC_WALK_LIMIT) {
            // See the class doc: a jump this far past the last line we've actually walked
            // through would otherwise block the UI thread tokenizing every intervening line just
            // to learn whether a block comment/triple-quoted string is open at `line`. Guess "no"
            // instead, without touching entryStates/computedThrough — ordinary scrolling through
            // this stretch later still walks and caches it for real, exactly as before.
            val (tokens, _) = SyntaxHighlighter.highlightLine(
                content,
                language,
                SyntaxHighlighter.LineHighlightState.NONE,
            )
            return tokens
        }
        ensureComputedThrough(line)
        val (tokens, _) = SyntaxHighlighter.highlightLine(content, language, entryStates[line])
        return tokens
    }

    private fun reconcileWithEdits() {
        val rev = controller.revision
        if (rev == lastSeenRevision) return
        lastSeenRevision = rev
        val editedLine = controller.lastEditedLine.coerceAtLeast(0)
        if (editedLine < computedThrough) {
            while (entryStates.size > editedLine + 1) entryStates.removeAt(entryStates.size - 1)
            computedThrough = editedLine
        }
    }

    private fun ensureComputedThrough(line: Int) {
        while (computedThrough < line) {
            val content = controller.getLine(computedThrough)
            val (_, exitState) = SyntaxHighlighter.highlightLine(content, language, entryStates[computedThrough])
            computedThrough++
            if (computedThrough == entryStates.size) entryStates.add(exitState) else entryStates[computedThrough] = exitState
        }
    }
}

private fun buildHighlightedLine(
    content: String,
    language: SyntaxLanguage,
    index: Int,
    highlightCache: HighlightStateCache,
): AnnotatedString {
    if (language == SyntaxLanguage.NONE || content.isEmpty()) return AnnotatedString(content)
    val tokens = highlightCache.tokensFor(index, content)
    if (tokens.isEmpty()) return AnnotatedString(content)
    return buildAnnotatedString {
        append(content)
        for (token in tokens) {
            if (token.start >= content.length) continue
            val end = minOf(token.end, content.length)
            if (end <= token.start) continue
            addStyle(EditorTheme.styleFor(token.kind, token.depth), token.start, end)
        }
    }
}
