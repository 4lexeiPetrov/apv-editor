package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
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
import com.apv.editor.syntax.SyntaxLanguage
import org.jetbrains.compose.resources.stringResource

/** How far above the status bar's language label the menu's bottom edge sits. */
private val MenuLift = 26.dp

/** About nine rows; the rest scrolls. */
private val MenuListMaxHeight = 304.dp

/**
 * The language picker that opens above the status bar's language label: a filter field and a
 * compact, scrolling list. Up/Down move, Enter picks, Escape closes. Call it from inside the box
 * that holds the label, since it positions itself against its parent.
 *
 * [detected] is what the file name maps to, marked in the list so a manual override can be undone.
 */
@Composable
internal fun LanguageMenu(
    current: SyntaxLanguage,
    detected: SyntaxLanguage?,
    onPick: (SyntaxLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    // Plain Text first, then the rest by name.
    val all = remember {
        listOf(SyntaxLanguage.NONE) +
            SyntaxLanguage.entries.filter { it != SyntaxLanguage.NONE }.sortedBy { it.displayName.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) all else all.filter { it.displayName.lowercase().contains(needle) }
    }
    var selected by remember { mutableStateOf(all.indexOf(current).coerceAtLeast(0)) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(8.dp)
    val fieldShape = RoundedCornerShape(6.dp)
    val lift = with(LocalDensity.current) { MenuLift.roundToPx() }

    Popup(
        alignment = Alignment.BottomEnd,
        offset = IntOffset(0, -lift),
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
                    filtered.getOrNull(selected)?.let(onPick)
                    true
                }
                else -> false
            }
        },
        onKeyEvent = { false },
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .shadow(16.dp, shape)
                .clip(shape)
                .background(EditorTheme.SurfaceVariant)
                .border(1.dp, EditorTheme.Outline, shape)
                .padding(6.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    selected = 0
                },
                singleLine = true,
                textStyle = TextStyle(color = EditorTheme.OnSurface, fontSize = 12.5.sp),
                cursorBrush = SolidColor(EditorTheme.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .focusRequester(focusRequester)
                    .background(EditorTheme.Surface, fieldShape)
                    .border(1.dp, EditorTheme.Outline, fieldShape)
                    .padding(horizontal = 10.dp),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.language_filter),
                                color = EditorTheme.OnSurfaceMuted,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(Res.string.language_none),
                    color = EditorTheme.OnSurfaceMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = MenuListMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(filtered, key = { _, language -> language.name }) { index, language ->
                        val isSelected = index == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .clip(fieldShape)
                                .background(if (isSelected) EditorTheme.Border else Color.Transparent)
                                .clickable { onPick(language) }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Check mark column: only the tab's current language has one.
                            Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                if (language == current) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = EditorTheme.Accent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = language.displayName,
                                color = EditorTheme.OnSurface,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (language == detected) {
                                Text(
                                    text = stringResource(Res.string.language_detected),
                                    color = EditorTheme.OnSurfaceMuted,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Inside the popup's own composition on purpose: the field has to be attached before focus
        // can be requested (same as the command palette).
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
        // Keep the keyboard selection in view; on open this brings the current language into view.
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
