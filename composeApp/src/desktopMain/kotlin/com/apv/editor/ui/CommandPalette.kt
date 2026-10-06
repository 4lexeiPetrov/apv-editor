package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.apv.editor.*
import org.jetbrains.compose.resources.stringResource

/**
 * One action the user can run by name. The command palette lists these; Main.kt builds the list
 * from the same functions its menu items call, so a palette entry and its menu item cannot drift.
 */
class EditorCommand(
    val title: String,
    /** Shortcut as display text (see [Platform.shortcutLabel]); empty when the command has none. */
    val shortcut: String = "",
    /** Checked when the palette opens and again right before running. */
    val isEnabled: () -> Boolean = { true },
    val run: () -> Unit,
)

@Composable
internal fun CommandPalette(commands: List<EditorCommand>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val filtered = remember(query, commands) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            commands
        } else {
            // Every word typed has to appear somewhere in the title, in any order.
            val words = needle.split(' ').filter { it.isNotEmpty() }
            commands.filter { command ->
                val title = command.title.lowercase()
                words.all { title.contains(it) }
            }
        }
    }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(12.dp)
    val topOffset = with(LocalDensity.current) { 56.dp.roundToPx() }

    val runCommand: (EditorCommand) -> Unit = { command ->
        if (command.isEnabled()) {
            onDismiss()
            command.run()
        }
    }

    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, topOffset),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else when (event.key) {
                Key.Escape -> {
                    onDismiss()
                    true
                }
                Key.DirectionDown -> {
                    if (filtered.isNotEmpty()) selected = (selected + 1).coerceAtMost(filtered.lastIndex)
                    true
                }
                Key.DirectionUp -> {
                    selected = (selected - 1).coerceAtLeast(0)
                    true
                }
                Key.Enter, Key.NumPadEnter -> {
                    filtered.getOrNull(selected)?.let(runCommand)
                    true
                }
                else -> false
            }
        },
        onKeyEvent = { false },
    ) {
        Column(
            modifier = Modifier
                .width(540.dp)
                .clip(shape)
                .background(EditorTheme.SurfaceVariant)
                .border(1.dp, EditorTheme.Outline, shape),
        ) {
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    selected = 0
                },
                singleLine = true,
                textStyle = TextStyle(color = EditorTheme.OnSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(EditorTheme.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.palette_placeholder),
                                color = EditorTheme.OnSurfaceMuted,
                                fontSize = 14.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(EditorTheme.Border))
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(Res.string.palette_empty),
                    color = EditorTheme.OnSurfaceMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 336.dp).padding(6.dp),
                ) {
                    itemsIndexed(filtered) { index, command ->
                        val enabled = command.isEnabled()
                        val isSelected = index == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) EditorTheme.Border else Color.Transparent)
                                .clickable(enabled = enabled) { runCommand(command) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = command.title,
                                color = when {
                                    !enabled -> EditorTheme.GutterText
                                    isSelected -> EditorTheme.OnSurface
                                    else -> EditorTheme.OnSurfaceMuted
                                },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (command.shortcut.isNotEmpty()) {
                                Text(
                                    text = command.shortcut,
                                    color = EditorTheme.GutterText,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Inside the popup's own composition on purpose: the text field above has to be attached
        // before focus can be requested, and that happens with this content, not with the caller's.
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
        // Keep the keyboard selection in view without re-scrolling when it already is.
        LaunchedEffect(selected, filtered) {
            if (filtered.isNotEmpty()) {
                val visible = listState.layoutInfo.visibleItemsInfo
                val first = visible.firstOrNull()?.index ?: 0
                val last = visible.lastOrNull()?.index ?: 0
                if (selected < first) {
                    listState.scrollToItem(selected)
                } else if (selected >= last && visible.isNotEmpty()) {
                    listState.scrollToItem((selected - visible.size + 2).coerceAtLeast(0))
                }
            }
        }
    }
}
