package com.apv.editor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.*
import com.apv.editor.state.EditorState
import com.apv.editor.state.PaneState
import com.apv.editor.state.TabState
import org.jetbrains.compose.resources.stringResource
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PaneTabBar(pane: PaneState, state: EditorState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(EditorTheme.Surface)
            .combinedClickable(
                onClick = { state.focusedPaneId = pane.id },
                onDoubleClick = {
                    state.focusedPaneId = pane.id
                    pane.newTab()
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pane.tabs.forEachIndexed { index, tab ->
                key(tab.id) {
                    TabChip(
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
        EndDropZone(pane = pane, state = state)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun TabChip(
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

    val bg = when {
        selected -> EditorTheme.Background
        isHovered -> EditorTheme.SurfaceVariant
        else -> EditorTheme.Surface
    }
    val showIndicator = isDropHover && state.tabDrag?.tab !== tab

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(32.dp)
                .background(if (showIndicator) EditorTheme.Accent else Color.Transparent),
        )
        Box(
            modifier = Modifier
                .height(32.dp)
                .alpha(if (isDragSource) 0.5f else 1f)
                .background(bg)
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
                .clickable(onClick = onSelect),
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(EditorTheme.Accent),
                )
            }
            Row(
                modifier = Modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.title,
                    color = if (selected) EditorTheme.OnSurface else EditorTheme.OnSurfaceMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                )
                Box(
                    modifier = Modifier.size(20.dp).padding(end = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isHovered -> IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(Res.string.tab_close),
                                tint = EditorTheme.OnSurface,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        tab.isModified -> Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(EditorTheme.OnSurfaceMuted, shape = CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun RowScope.EndDropZone(pane: PaneState, state: EditorState) {
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
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { it.awtTransferable.hasTabDrag() },
                target = dropTarget,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(if (isDropHover) EditorTheme.Accent else Color.Transparent),
        )
    }
}
