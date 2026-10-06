package com.apv.editor.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
            // The safe choice is the one filled button; the destructive one is tinted as an error.
            Button(onClick = onSave) { Text(stringResource(Res.string.unsaved_btn_save)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDiscard,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.unsaved_btn_dont_save)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.unsaved_btn_cancel)) }
            }
        },
    )
}

/** Confirms throwing away an unsaved buffer from the start screen: there is no undo for it. */
@Composable
internal fun DiscardDraftDialog(
    displayName: String,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.discard_draft_title)) },
        text = { Text(stringResource(Res.string.discard_draft_text, displayName)) },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(Res.string.discard_draft_btn)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.unsaved_btn_cancel)) }
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
            Button(onClick = onSaveAll) { Text(stringResource(Res.string.layout_merge_btn_save_all)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onMergeAnyway,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.layout_merge_btn_merge_anyway)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.unsaved_btn_cancel)) }
            }
        },
    )
}
