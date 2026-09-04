package com.apv.editor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.apv.editor.*
import com.apv.editor.state.EditorState
import com.apv.editor.state.PaneLayout
import com.apv.editor.state.PaneState
import com.apv.editor.syntax.SyntaxLanguage
import org.jetbrains.compose.resources.stringResource
import java.awt.datatransfer.DataFlavor
import java.io.File

@Composable
internal fun PaneLayoutRenderer(
    state: EditorState,
    onOpenInPane: (PaneState) -> Unit,
) {
    when (state.layout) {
        PaneLayout.Single -> Pane(state.panes[0], state, onOpenInPane, Modifier.fillMaxSize())
        PaneLayout.Columns2 -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val h = state.horizontalSplit
            Row(modifier = Modifier.fillMaxSize()) {
                Pane(state.panes[0], state, onOpenInPane, Modifier.weight(h).fillMaxHeight())
                ResizableVerticalDivider(onDrag = { dx ->
                    if (widthPx > 0f) state.applyHorizontalSplit(state.horizontalSplit + dx / widthPx)
                })
                Pane(state.panes[1], state, onOpenInPane, Modifier.weight(1f - h).fillMaxHeight())
            }
        }
        PaneLayout.Rows2 -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
            val v = state.verticalSplit
            Column(modifier = Modifier.fillMaxSize()) {
                Pane(state.panes[0], state, onOpenInPane, Modifier.weight(v).fillMaxWidth())
                ResizableHorizontalDivider(onDrag = { dy ->
                    if (heightPx > 0f) state.applyVerticalSplit(state.verticalSplit + dy / heightPx)
                })
                Pane(state.panes[1], state, onOpenInPane, Modifier.weight(1f - v).fillMaxWidth())
            }
        }
        PaneLayout.Grid2x2 -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
            val h = state.horizontalSplit
            val v = state.verticalSplit
            val onDragH: (Float) -> Unit = { dx ->
                if (widthPx > 0f) state.applyHorizontalSplit(state.horizontalSplit + dx / widthPx)
            }
            val onDragV: (Float) -> Unit = { dy ->
                if (heightPx > 0f) state.applyVerticalSplit(state.verticalSplit + dy / heightPx)
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(v).fillMaxWidth()) {
                    Pane(state.panes[0], state, onOpenInPane, Modifier.weight(h).fillMaxHeight())
                    ResizableVerticalDivider(onDrag = onDragH)
                    Pane(state.panes[1], state, onOpenInPane, Modifier.weight(1f - h).fillMaxHeight())
                }
                ResizableHorizontalDivider(onDrag = onDragV)
                Row(modifier = Modifier.weight(1f - v).fillMaxWidth()) {
                    Pane(state.panes[2], state, onOpenInPane, Modifier.weight(h).fillMaxHeight())
                    ResizableVerticalDivider(onDrag = onDragH)
                    Pane(state.panes[3], state, onOpenInPane, Modifier.weight(1f - h).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun ResizableVerticalDivider(onDrag: (Float) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .width(6.dp)
            .fillMaxHeight()
            .hoverable(interactionSource)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(if (isHovered) EditorTheme.Accent else EditorTheme.Border),
        )
    }
}

@Composable
private fun ResizableHorizontalDivider(onDrag: (Float) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .hoverable(interactionSource)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isHovered) EditorTheme.Accent else EditorTheme.Border),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun Pane(
    pane: PaneState,
    state: EditorState,
    onOpenInPane: (PaneState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragOver by remember(pane.id) { mutableStateOf(false) }

    val dndTarget = remember(pane, state) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                val t = event.awtTransferable
                if (t.hasFiles() || t.hasTabDrag()) isDragOver = true
            }
            override fun onExited(event: DragAndDropEvent) { isDragOver = false }
            override fun onEnded(event: DragAndDropEvent) {
                isDragOver = false
                state.endTabDrag()
            }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragOver = false
                val t = event.awtTransferable
                if (t.hasTabDrag()) {
                    state.moveDraggedTabTo(pane, pane.tabs.size)
                    return true
                }
                if (!t.hasFiles()) return false
                return runCatching {
                    val files = t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                        ?: return@runCatching false
                    val opened = files.filterIsInstance<File>().filter { it.isFile }
                    if (opened.isEmpty()) return@runCatching false
                    state.focusedPaneId = pane.id
                    opened.forEach { state.openFile(pane, it) }
                    true
                }.getOrDefault(false)
            }
        }
    }

    Column(
        modifier = modifier
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    val t = event.awtTransferable
                    t.hasFiles() || t.hasTabDrag()
                },
                target = dndTarget,
            )
            .then(
                if (isDragOver) Modifier.border(2.dp, EditorTheme.Accent) else Modifier
            ),
    ) {
        PaneTabBar(pane, state)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val tab = pane.currentTab
            if (tab != null) {
                if (tab.language == SyntaxLanguage.MARKDOWN && tab.showPreview) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            EditorPane(tab, pane, state)
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(EditorTheme.Border),
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            MarkdownPreview(tab.textField.text, state.fontSize)
                        }
                    }
                } else {
                    EditorPane(tab, pane, state)
                }
            } else {
                EmptyPanePlaceholder(pane, state, onOpenInPane)
            }
        }
    }
}

@Composable
private fun EmptyPanePlaceholder(
    pane: PaneState,
    state: EditorState,
    onOpenInPane: (PaneState) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorTheme.Background)
            .clickable { state.focusedPaneId = pane.id },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Res.string.pane_empty),
                color = EditorTheme.OnSurfaceMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = {
                state.focusedPaneId = pane.id
                pane.newTab()
            }) {
                Text(stringResource(Res.string.pane_new_file), color = EditorTheme.Accent)
            }
            TextButton(onClick = {
                state.focusedPaneId = pane.id
                onOpenInPane(pane)
            }) {
                Text(stringResource(Res.string.pane_open_file), color = EditorTheme.Accent)
            }
        }
    }
}
