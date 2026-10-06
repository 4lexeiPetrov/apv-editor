package com.apv.editor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.*
import com.apv.editor.state.EditorState
import com.apv.editor.state.PaneState
import com.apv.editor.state.PreviewMode
import com.apv.editor.state.TabState
import com.apv.editor.syntax.SyntaxLanguage
import org.jetbrains.compose.resources.stringResource
import java.awt.datatransfer.StringSelection

/**
 * 32dp, not the design canvas's 40dp: the macOS traffic lights sit at a fixed system height that
 * stock OpenJDK cannot move, and they only look centred in a bar about this tall. The sidebar
 * header matches it.
 */
private val TabBarHeight = 32.dp

/** Widest a whole tab gets (padding, badge, title and close slot together) before its title is cut. */
private val TabMaxWidth = 190.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PaneTabBar(pane: PaneState, state: EditorState) {
    val unifiedWindowScope = LocalUnifiedWindowScope.current
    val unified = unifiedWindowScope != null
    // Only the top-left pane sits under the traffic lights, and only while the sidebar isn't there
    // instead (it takes that corner when visible).
    val sidebarShown = state.sidebarVisible && state.projectRoot != null
    val isFirstPane = state.panes.firstOrNull() === pane
    val startInset = if (isFirstPane && !sidebarShown) LocalTitleBarInset.current else 0.dp

    val newTab: () -> Unit = {
        state.focusedPaneId = pane.id
        pane.newTab()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TabBarHeight)
            .background(EditorTheme.Surface)
            // Hairline under the bar. The selected tab is drawn over it in the editor's background
            // colour, which is what joins that tab to the editor below.
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(
                    color = EditorTheme.Border,
                    topLeft = Offset(0f, size.height - line),
                    size = Size(size.width, line),
                )
            }
            .combinedClickable(
                onClick = { state.focusedPaneId = pane.id },
                // Under a unified title bar the empty part of this bar drags the window, and a
                // double-click there is the system's zoom gesture, so the old "double-click for a
                // new tab" only stays on platforms with a separate native title bar. The "+"
                // button covers both.
                onDoubleClick = if (unified) null else newTab,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (startInset > 0.dp) Spacer(Modifier.width(startInset))

        TabStrip(pane = pane, state = state, onNewTab = newTab, modifier = Modifier.weight(1f).fillMaxHeight())

        val current = pane.currentTab
        if (current != null && !current.isLargeFile && current.language == SyntaxLanguage.MARKDOWN) {
            PreviewModeSwitch(current)
        }
        if (pane.tabs.size > 1) {
            AllTabsButton(pane, state)
        }
    }
}

/** A full-height icon button of the tab bar ("+" and the all-tabs chevron): no background, muted icon. */
@Composable
private fun BarButton(
    icon: ImageVector,
    description: String,
    width: Dp,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Box(
        modifier = Modifier.width(width).fillMaxHeight().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) EditorTheme.OnSurface else EditorTheme.OnSurfaceMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The tabs, the "+" button right after them, and the empty remainder of the bar. The remainder is
 * a drop target for dragged tabs (drop = move to the end) and, under a unified title bar, also the
 * handle that drags the window.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun TabStrip(
    pane: PaneState,
    state: EditorState,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unifiedWindowScope = LocalUnifiedWindowScope.current
    var isDropHover by remember(pane.id) { mutableStateOf(false) }
    val dropTarget = remember(pane, state) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                if (event.awtTransferable.hasTabDrag()) isDropHover = true
            }
            override fun onExited(event: DragAndDropEvent) { isDropHover = false }
            override fun onEnded(event: DragAndDropEvent) { isDropHover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDropHover = false
                if (event.awtTransferable.hasTabDrag()) {
                    state.moveDraggedTabTo(pane, pane.tabs.size)
                    return true
                }
                return false
            }
        }
    }

    Box(
        modifier = modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { it.awtTransferable.hasTabDrag() },
            target = dropTarget,
        ),
    ) {
        if (unifiedWindowScope != null) {
            with(unifiedWindowScope) {
                WindowDraggableArea(modifier = Modifier.matchParentSize())
            }
        }
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // weight(fill = false): the tabs take only the width they need, but are capped at what
            // is left after the "+" button, so with many tabs they scroll instead of pushing the
            // button (and the controls to the right of this strip) off the bar.
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pane.tabs.forEachIndexed { index, tab ->
                    key(tab.id) {
                        TabItem(
                            pane = pane,
                            tab = tab,
                            index = index,
                            state = state,
                            selected = index == pane.selectedIndex,
                            onSelect = { pane.selectedIndex = index },
                            onClose = { state.closeTab(pane, index) },
                        )
                    }
                }
            }
            // Drop-at-the-end marker; takes no room unless a tab is being dragged over the strip.
            if (isDropHover) {
                Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(EditorTheme.Accent))
            }
            HintTooltip("${stringResource(Res.string.tab_new)}  ${Platform.shortcutLabel("N")}") {
                BarButton(
                    icon = Icons.Filled.Add,
                    description = stringResource(Res.string.tab_new),
                    width = 36.dp,
                    onClick = onNewTab,
                )
            }
        }
    }
}

/**
 * One tab, as on the design canvas: square, as tall as the bar, and with no fill unless selected.
 * The selected tab takes the editor's background and a 2dp accent line along its top edge.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun TabItem(
    pane: PaneState,
    tab: TabState,
    index: Int,
    state: EditorState,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isDragSource = state.tabDrag?.tab === tab
    var isDropHover by remember(pane.id, tab.id) { mutableStateOf(false) }

    val dropTarget = remember(pane, tab, index, state) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                if (event.awtTransferable.hasTabDrag()) isDropHover = true
            }
            override fun onExited(event: DragAndDropEvent) { isDropHover = false }
            override fun onEnded(event: DragAndDropEvent) { isDropHover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDropHover = false
                if (event.awtTransferable.hasTabDrag()) {
                    state.moveDraggedTabTo(pane, index)
                    return true
                }
                return false
            }
        }
    }

    val showIndicator = isDropHover && state.tabDrag?.tab !== tab

    // With more tabs than fit, the strip scrolls. Selecting a tab from anywhere (the sidebar, the
    // all-tabs menu, opening a file) scrolls the strip so that tab is actually on screen.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) runCatching { bringIntoView.bringIntoView() }
    }

    Row(
        modifier = Modifier
            .fillMaxHeight()
            // Bounds the width even inside the horizontally scrolling strip (where the incoming
            // constraint is unbounded), so the title below can shrink and ellipsize.
            .widthIn(max = TabMaxWidth)
            .bringIntoViewRequester(bringIntoView)
            .alpha(if (isDragSource) 0.5f else 1f)
            .background(if (selected) EditorTheme.Background else Color.Transparent)
            .drawBehind {
                if (selected) {
                    drawRect(color = EditorTheme.Accent, size = Size(size.width, 2.dp.toPx()))
                }
                // Where a dragged tab would land: a line on this tab's leading edge.
                if (showIndicator) {
                    drawRect(color = EditorTheme.Accent, size = Size(2.dp.toPx(), size.height))
                }
            }
            .hoverable(interactionSource)
            .dragAndDropSource(
                drawDragDecoration = {},
                block = {
                    detectDragGestures(
                        onDragStart = {
                            state.beginTabDrag(tab, pane)
                            startTransfer(
                                DragAndDropTransferData(
                                    transferable = DragAndDropTransferable(StringSelection("tab")),
                                    supportedActions = listOf(DragAndDropTransferAction.Move),
                                )
                            )
                        },
                        onDragEnd = { state.endTabDrag() },
                        onDragCancel = { state.endTabDrag() },
                        onDrag = { _, _ -> },
                    )
                },
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = { it.awtTransferable.hasTabDrag() },
                target = dropTarget,
            )
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab.file != null) {
            FileBadge(tab.language)
            Spacer(Modifier.width(8.dp))
        }
        // A file tab's tooltip is its full path; an unsaved buffer's is a short preview of
        // its content, which is the only place that content is shown outside the editor.
        HintTooltip(tab.file?.absolutePath ?: tab.contentPreview, Modifier.weight(1f, fill = false)) {
            Text(
                text = tab.title,
                color = when {
                    selected -> Color.White
                    isHovered -> EditorTheme.OnSurface
                    else -> EditorTheme.OnSurfaceMuted
                },
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 16dp slot at the end: the modified dot, or the close cross on the selected or hovered
        // tab. Hovering a modified tab swaps its dot for the cross so it can still be closed.
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                isHovered || (selected && !tab.isModified) -> Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.tab_close),
                        tint = if (isHovered) EditorTheme.OnSurface else EditorTheme.OnSurfaceMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
                tab.isModified -> Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(EditorTheme.OnSurfaceMuted, shape = CircleShape),
                )
            }
        }
    }
}

/**
 * Edit / Split / Preview switch shown at the right of the tab bar while a Markdown tab is current.
 * One outlined group; the active segment is filled and carries an accent check mark, so the choice
 * does not depend on colour alone.
 */
@Composable
private fun PreviewModeSwitch(tab: TabState) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .padding(start = 8.dp, end = 12.dp)
            .height(24.dp)
            .clip(shape)
            .border(1.dp, EditorTheme.Outline, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewMode.entries.forEachIndexed { index, mode ->
            val isSelected = tab.previewMode == mode
            if (index > 0) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(EditorTheme.Outline))
            }
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (isSelected) EditorTheme.Border else Color.Transparent)
                    .clickable { tab.previewMode = mode }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = EditorTheme.Accent,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = mode.name,
                    color = if (isSelected) EditorTheme.OnSurface else EditorTheme.OnSurfaceMuted,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Chevron at the far right that lists every tab of the pane, for when the strip has scrolled. */
@Composable
private fun AllTabsButton(pane: PaneState, state: EditorState) {
    var open by remember(pane.id) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxHeight()) {
        HintTooltip(stringResource(Res.string.tab_all)) {
            BarButton(
                icon = Icons.Filled.KeyboardArrowDown,
                description = stringResource(Res.string.tab_all),
                width = 40.dp,
                onClick = { open = true },
                selected = open,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            pane.tabs.forEachIndexed { index, tab ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = (if (tab.isModified) "● " else "") + tab.title,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (index == pane.selectedIndex) EditorTheme.Accent else EditorTheme.OnSurface,
                        )
                    },
                    onClick = {
                        pane.selectedIndex = index
                        state.focusedPaneId = pane.id
                        open = false
                    },
                )
            }
        }
    }
}
