package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.*
import com.apv.editor.state.EditorState
import com.apv.editor.state.PaneState
import com.apv.editor.state.TabState
import org.jetbrains.compose.resources.stringResource

@Composable
fun App(
    state: EditorState,
    onSaveTab: (TabState) -> Boolean,
    onOpenInPane: (PaneState) -> Unit,
    commands: List<EditorCommand> = emptyList(),
    onOpenFolder: () -> Unit = {},
) {
    MaterialTheme(colorScheme = EditorTheme.colorScheme, shapes = EditorTheme.shapes) {
        ProvideTextStyle(value = LocalTextStyle.current.copy(fontFamily = FontFamily.SansSerif)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = EditorTheme.Background,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (state.sidebarVisible && state.projectRoot != null) {
                                Sidebar(state, Modifier.width(state.sidebarWidth.dp))
                                SidebarDivider(state)
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                PaneLayoutRenderer(state, onOpenInPane, onOpenFolder)
                            }
                        }
                        state.diskChangeNotice?.let { tab ->
                            DiskChangeNotice(
                                fileName = tab.file?.name ?: tab.title,
                                onReload = { state.reloadNoticedTab() },
                                onDismiss = { state.dismissDiskChangeNotice() },
                                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(EditorTheme.Border))
                    StatusBar(state)
                }
            }
            if (state.paletteOpen) {
                CommandPalette(
                    commands = commands,
                    onDismiss = {
                        state.paletteOpen = false
                        state.currentTab?.let { it.editorFocusTick++ }
                    },
                )
            }
        }
        state.pendingClose?.let { tab ->
            UnsavedChangesDialog(
                displayName = tab.file?.name ?: tab.title,
                onSave = {
                    if (onSaveTab(tab)) state.closePending()
                },
                onDiscard = { state.closePending() },
                onCancel = { state.cancelClose() },
            )
        }
        state.pendingLayoutChange?.let { pending ->
            LayoutMergeDialog(
                pending = pending,
                onSaveAll = { state.confirmLayoutChangeSaving(onSaveTab) },
                onMergeAnyway = { state.confirmLayoutChangeMerging() },
                onCancel = { state.cancelLayoutChange() },
            )
        }
        state.pendingDraftDiscard?.let { draft ->
            DiscardDraftDialog(
                displayName = draft.title,
                onDiscard = { state.confirmDraftDiscard() },
                onCancel = { state.cancelDraftDiscard() },
            )
        }
        state.pendingOversizedFile?.let { file ->
            OversizedFileDialog(
                fileName = file.name,
                onDismiss = { state.dismissOversizedFile() },
            )
        }
    }
}

/**
 * Snackbar-style notice for a file that changed on disk while its tab has unsaved edits. Stays
 * until answered: silently picking either version would lose someone's changes.
 */
@Composable
private fun DiskChangeNotice(
    fileName: String,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(EditorTheme.Border)
            .border(1.dp, EditorTheme.Outline, shape)
            .padding(start = 16.dp, end = 8.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.disk_changed, fileName),
            color = EditorTheme.OnSurface,
            fontSize = 13.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onReload) {
            Text(stringResource(Res.string.disk_reload), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        ChromeIconButton(
            icon = Icons.Filled.Close,
            description = stringResource(Res.string.disk_keep),
            onClick = onDismiss,
        )
    }
}
