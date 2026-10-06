package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.state.EditorState
import com.apv.editor.syntax.SyntaxDetector
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One visible row of the tree: a file or folder at [depth] levels below the project root. */
private data class TreeNode(
    val file: File,
    val depth: Int,
    val isDirectory: Boolean,
    val isExpanded: Boolean,
)

/** Folders with more entries than this are cut off, so one huge directory can't stall the tree. */
private const val MAX_CHILDREN_PER_FOLDER = 2000

/** Editor font size at which the tree is drawn at its designed size (12sp rows, 24dp high). */
private const val TREE_BASE_FONT_SIZE = 14f

const val SIDEBAR_MIN_WIDTH = 160f
const val SIDEBAR_MAX_WIDTH = 480f

/**
 * Lists [root]'s visible rows top to bottom: each folder's children (folders first, then by name),
 * descending only into the folders in [expanded]. Does disk I/O; call it off the UI thread.
 */
private fun flattenTree(root: File, expanded: Set<String>): List<TreeNode> {
    val out = ArrayList<TreeNode>()
    fun walk(dir: File, depth: Int) {
        val children = try { dir.listFiles() } catch (_: Throwable) { null } ?: return
        val sorted = children
            .filter { it.name != ".DS_Store" }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .take(MAX_CHILDREN_PER_FOLDER)
        for (child in sorted) {
            val isDirectory = child.isDirectory
            val isExpanded = isDirectory && child.absolutePath in expanded
            out.add(TreeNode(child, depth, isDirectory, isExpanded))
            if (isExpanded) walk(child, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

/**
 * Project file tree on the left of the window. Hidden unless a folder is open and
 * [EditorState.sidebarVisible] is set, so single-file use of the editor is unchanged.
 */
@Composable
internal fun Sidebar(state: EditorState, modifier: Modifier = Modifier) {
    val root = state.projectRoot ?: return
    val expanded = state.expandedDirs.toList()
    val refreshTick = state.sidebarRefreshTick
    var nodes by remember(root) { mutableStateOf<List<TreeNode>>(emptyList()) }

    // Folder listing is disk I/O, so it runs on the IO dispatcher and the rows swap in when ready.
    LaunchedEffect(root, expanded, refreshTick) {
        nodes = withContext(Dispatchers.IO) { flattenTree(root, expanded.toSet()) }
    }

    val currentPath = state.currentTab?.file?.absolutePath
    // The tree follows the editor's font size (View > Increase/Decrease Font): 1.0 at the default 14.
    val treeScale = (state.fontSize / TREE_BASE_FONT_SIZE).coerceIn(0.75f, 2.5f)
    val unifiedWindowScope = LocalUnifiedWindowScope.current

    Column(modifier = modifier.fillMaxHeight().background(EditorTheme.Surface)) {
        // Under a unified title bar the traffic lights sit over this corner: leave them a bar-high
        // strip that also drags the window, and inset the folder name past them.
        Box(
            modifier = Modifier.fillMaxWidth().height(32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (unifiedWindowScope != null) {
                with(unifiedWindowScope) {
                    WindowDraggableArea(modifier = Modifier.matchParentSize())
                }
            }
            Text(
                text = root.name,
                color = EditorTheme.OnSurface,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = LocalTitleBarInset.current + 12.dp, end = 8.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(nodes, key = { it.file.absolutePath }) { node ->
                TreeRow(
                    node = node,
                    scale = treeScale,
                    selected = !node.isDirectory && node.file.absolutePath == currentPath,
                    onClick = {
                        if (node.isDirectory) state.toggleDir(node.file) else state.openFile(node.file)
                    },
                )
            }
        }
    }
}

@Composable
private fun TreeRow(node: TreeNode, scale: Float, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        selected -> EditorTheme.Border
        isHovered -> EditorTheme.SurfaceVariant
        else -> Color.Transparent
    }
    // Open folders take the accent colour, like the open-folder highlight in the reference theme.
    val color = when {
        node.isExpanded -> EditorTheme.Accent
        selected -> EditorTheme.OnSurface
        else -> EditorTheme.OnSurfaceMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height((24 * scale).dp)
            .background(background)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(start = (10 + node.depth * 14 * scale).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isDirectory) {
            Icon(
                imageVector = if (node.isExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size((14 * scale).dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size((14 * scale).dp),
            )
        } else {
            Spacer(Modifier.width((14 * scale + 4).dp))
            FileBadge(SyntaxDetector.detect(node.file.name), scale = scale)
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = node.file.name,
            color = color,
            fontSize = (12 * scale).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The draggable edge between the sidebar and the editor panes. */
@Composable
internal fun SidebarDivider(state: EditorState) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val density = LocalDensity.current.density
    Box(
        modifier = Modifier
            .width(5.dp)
            .fillMaxHeight()
            .background(EditorTheme.Surface)
            .hoverable(interactionSource)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    state.sidebarWidth = (state.sidebarWidth + dragAmount.x / density)
                        .coerceIn(SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH)
                }
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(if (isHovered) EditorTheme.Accent else EditorTheme.Border),
        )
    }
}
