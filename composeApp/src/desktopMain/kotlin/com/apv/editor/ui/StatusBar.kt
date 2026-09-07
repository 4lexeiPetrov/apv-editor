package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.state.EditorState

@Composable
internal fun StatusBar(state: EditorState) {
    val tab = state.currentTab
    val caretInfo = if (tab != null && tab.isLargeFile) {
        "Ln ${tab.largeCaret.line + 1}, Col ${tab.largeCaret.col + 1}"
    } else if (tab != null) {
        val cursor = tab.textField.selection.start.coerceAtMost(tab.textField.text.length)
        val before = tab.textField.text.substring(0, cursor)
        val line = before.count { it == '\n' } + 1
        val col = cursor - (before.lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }) + 1
        "Ln $line, Col $col"
    } else ""
    val lang = tab?.language?.name?.lowercase()?.replace('_', ' ') ?: "—"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(EditorTheme.SurfaceVariant)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.statusMessage, color = EditorTheme.OnSurfaceMuted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(caretInfo, color = EditorTheme.OnSurfaceMuted, fontSize = 11.sp)
        Spacer(Modifier.width(16.dp))
        Text(lang, color = EditorTheme.OnSurfaceMuted, fontSize = 11.sp)
    }
}
