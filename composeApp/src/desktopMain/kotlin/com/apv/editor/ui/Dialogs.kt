package com.apv.editor.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apv.editor.*
import com.apv.editor.state.EditorState
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun UnsavedChangesDialog(
    displayName: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.unsaved_title)) },
        text = { Text(stringResource(Res.string.unsaved_text, displayName)) },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(Res.string.unsaved_btn_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text(stringResource(Res.string.unsaved_btn_dont_save)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.unsaved_btn_cancel)) }
            }
        },
    )
}

@Composable
internal fun OversizedFileDialog(
    fileName: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.oversized_title)) },
        text = { Text(stringResource(Res.string.oversized_text, fileName)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.oversized_btn_ok)) }
        },
    )
}

@Composable
internal fun LayoutMergeDialog(
    pending: EditorState.PendingLayoutChange,
    onSaveAll: () -> Unit,
    onMergeAnyway: () -> Unit,
    onCancel: () -> Unit,
) {
    val untitled = stringResource(Res.string.tab_untitled)
    val names = pending.dirtyTabs.joinToString(", ") { it.file?.name ?: untitled }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.unsaved_title)) },
        text = {
            Text(
                stringResource(
                    Res.string.layout_merge_text,
                    pending.target.displayName,
                    pending.dirtyTabs.size,
                    names,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onSaveAll) { Text(stringResource(Res.string.layout_merge_btn_save_all)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onMergeAnyway) { Text(stringResource(Res.string.layout_merge_btn_merge_anyway)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.unsaved_btn_cancel)) }
            }
        },
    )
}
