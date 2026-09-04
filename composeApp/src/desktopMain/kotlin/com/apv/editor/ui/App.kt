package com.apv.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
) {
    MaterialTheme(colorScheme = EditorTheme.colorScheme) {
        ProvideTextStyle(value = LocalTextStyle.current.copy(fontFamily = FontFamily.SansSerif)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = EditorTheme.Background,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        PaneLayoutRenderer(state, onOpenInPane)
                    }
                    StatusBar(state)
                }
            }
        }
        state.pendingClose?.let { tab ->
            UnsavedChangesDialog(
                displayName = tab.file?.name ?: stringResource(Res.string.unsaved_this_tab),
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
        state.pendingOversizedFile?.let { file ->
            OversizedFileDialog(
                fileName = file.name,
                onDismiss = { state.dismissOversizedFile() },
            )
        }
    }
}
