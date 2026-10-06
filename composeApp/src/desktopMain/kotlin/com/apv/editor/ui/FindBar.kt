package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.*
import com.apv.editor.state.EditorState
import com.apv.editor.state.TabState
import com.apv.editor.text.TextSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Everything a search depends on. A new value means the match list has to be recomputed. */
private data class FindRequest(
    val query: String,
    val caseSensitive: Boolean,
    val regex: Boolean,
    val text: String,
)

/**
 * Slim find bar shown between a pane's tab bar and its editor while [TabState.findOpen] is set.
 * Small-file tabs only: it searches the in-memory buffer and [EditorPane] draws the matches.
 */
@Composable
internal fun FindBar(tab: TabState, state: EditorState) {
    val focusRequester = remember(tab.id) { FocusRequester() }

    // Recompute matches off the UI thread, a short pause after the query, an option or the text
    // last changed. collectLatest drops a search that is still running when a newer request arrives.
    LaunchedEffect(tab.id) {
        var lastQueryKey: Triple<String, Boolean, Boolean>? = null
        snapshotFlow {
            FindRequest(tab.findQuery, tab.findCaseSensitive, tab.findRegex, tab.textField.text)
        }.collectLatest { request ->
            delay(150)
            val matches = withContext(Dispatchers.Default) {
                TextSearch.findAll(request.text, request.query, request.caseSensitive, request.regex)
            }
            val queryKey = Triple(request.query, request.caseSensitive, request.regex)
            val queryChanged = queryKey != lastQueryKey
            lastQueryKey = queryKey
            tab.findMatches = matches
            if (queryChanged) {
                // Start from the first match at or after the caret and bring it into view. Edits to
                // the text alone keep the current position instead of yanking the view around.
                val cursor = tab.textField.selection.start
                val next = matches.indexOfFirst { it.first >= cursor }
                tab.findIndex = if (next < 0) 0 else next
                if (matches.isNotEmpty()) tab.findRevealTick++
            } else {
                tab.findIndex = tab.findIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
            }
        }
    }

    LaunchedEffect(tab.id, tab.findFocusTick) {
        runCatching { focusRequester.requestFocus() }
    }

    val query = tab.findQuery
    val matchCount = tab.findMatches.size
    val invalid = !TextSearch.isValid(query, tab.findRegex)
    val fieldShape = RoundedCornerShape(6.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(EditorTheme.Surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = { tab.findQuery = it },
            singleLine = true,
            textStyle = TextStyle(
                color = EditorTheme.OnSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            cursorBrush = SolidColor(EditorTheme.Accent),
            modifier = Modifier
                .width(280.dp)
                .height(24.dp)
                .clip(fieldShape)
                .background(EditorTheme.Background)
                .border(1.dp, if (invalid) EditorTheme.BracketError else EditorTheme.Outline, fieldShape)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            state.stepFind(tab, if (event.isShiftPressed) -1 else 1)
                            true
                        }
                        Key.Escape -> {
                            state.closeFind(tab)
                            true
                        }
                        else -> false
                    }
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.find_placeholder),
                            color = EditorTheme.OnSurfaceMuted,
                            fontSize = 12.sp,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        FindToggle(label = "Aa", selected = tab.findCaseSensitive) {
            tab.findCaseSensitive = !tab.findCaseSensitive
        }
        Spacer(Modifier.width(4.dp))
        FindToggle(label = ".*", selected = tab.findRegex) {
            tab.findRegex = !tab.findRegex
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = when {
                query.isEmpty() -> ""
                matchCount == 0 -> stringResource(Res.string.find_no_results)
                else -> stringResource(
                    Res.string.find_count,
                    (tab.findIndex + 1).coerceAtMost(matchCount),
                    matchCount,
                )
            },
            color = EditorTheme.OnSurfaceMuted,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        ChromeIconButton(
            icon = Icons.Filled.KeyboardArrowUp,
            description = stringResource(Res.string.find_previous),
            onClick = { state.stepFind(tab, -1) },
            iconSize = 16.dp,
        )
        ChromeIconButton(
            icon = Icons.Filled.KeyboardArrowDown,
            description = stringResource(Res.string.find_next),
            onClick = { state.stepFind(tab, 1) },
            iconSize = 16.dp,
        )
        Spacer(Modifier.weight(1f))
        ChromeIconButton(
            icon = Icons.Filled.Close,
            description = stringResource(Res.string.find_close),
            onClick = { state.closeFind(tab) },
        )
    }
}

@Composable
private fun FindToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 26.dp, height = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) EditorTheme.Border else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) EditorTheme.Accent else EditorTheme.OnSurfaceMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}
