package com.apv.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apv.editor.*
import com.apv.editor.state.EditorState
import com.apv.editor.state.TabState
import com.apv.editor.syntax.SyntaxDetector
import com.apv.editor.syntax.SyntaxLanguage
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** How long a transient status message ("Saved x", "Opened y") stays before the left side clears again. */
private const val STATUS_MESSAGE_MS = 6000L

/**
 * Set on every status text. Without it Material's default body line height (24sp) applies, which
 * makes an 11.5sp label as tall as the whole bar and leaves no room to centre it.
 */
private val StatusLineHeight = 14.sp

@Composable
internal fun StatusBar(state: EditorState) {
    val tab = state.currentTab
    val message = state.statusMessage

    // The left side is for transient messages only, so it empties itself instead of showing a
    // permanent "Ready".
    LaunchedEffect(message) {
        if (message.isNotEmpty()) {
            delay(STATUS_MESSAGE_MS)
            if (state.statusMessage == message) state.statusMessage = ""
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(EditorTheme.Surface)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // On the start screen the idle text says why the sidebar is missing.
        val idle = if (state.hasNoTabs && state.projectRoot == null) {
            stringResource(Res.string.start_no_folder)
        } else {
            ""
        }
        Text(
            text = message.ifEmpty { idle },
            color = EditorTheme.OnSurfaceMuted,
            fontSize = 11.5.sp,
            lineHeight = StatusLineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tab != null) {
            if (tab.isLargeFile) {
                LargeTabSegments(tab)
            } else {
                NormalTabSegments(tab)
            }
            FileSizeSegment(tab)
            LanguageSegment(tab, state)
        }
    }
}

@Composable
private fun NormalTabSegments(tab: TabState) {
    val field = tab.textField
    val text = field.text
    // Both derive from the text or the whole TextFieldValue, so they are recomputed only when one
    // of those actually changes rather than on every recomposition of the bar.
    val caretInfo = remember(field) {
        val cursor = field.selection.start.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        var line = 1
        for (i in 0 until cursor) if (text[i] == '\n') line++
        "Ln $line, Col ${cursor - lineStart + 1}"
    }
    val lineCount = remember(text) { 1 + text.count { it == '\n' } }
    val selected = field.selection.length

    if (selected > 0) StatusSegment("$selected selected")
    StatusSegment(caretInfo)
    StatusSegment(if (lineCount == 1) "1 line" else "${groupThousands(lineCount.toLong())} lines")
}

@Composable
private fun LargeTabSegments(tab: TabState) {
    val caret = tab.largeCaret
    StatusSegment("Ln ${groupThousands(caret.line + 1L)}, Col ${caret.col + 1}")
    val lineCount = tab.largeDoc?.lineCount ?: 0
    StatusSegment("${groupThousands(lineCount.toLong())} lines")
}

@Composable
private fun FileSizeSegment(tab: TabState) {
    val file = tab.file ?: return
    // Re-read when the tab is (re)loaded or saved, not on every frame: savedText changes on every
    // small-file save/load, and the large document's dirty flag flips back on save.
    val size = remember(file, tab.savedText, tab.largeDoc?.isDirty) {
        try { file.length() } catch (_: Throwable) { -1L }
    }
    if (size >= 0) StatusSegment(formatFileSize(size))
}

@Composable
private fun LanguageSegment(tab: TabState, state: EditorState) {
    var menuOpen by remember { mutableStateOf(false) }
    Spacer(Modifier.width(12.dp))
    Box {
        // A 20dp button centred in the bar. The height is fixed here rather than grown by padding
        // around the text: that made this label taller than the bar and pushed it off the baseline
        // of the other segments.
        Box(
            modifier = Modifier
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (menuOpen) EditorTheme.Border else EditorTheme.Surface)
                .clickable { menuOpen = true }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = tab.language.displayName,
                color = EditorTheme.OnSurface,
                fontSize = 11.5.sp,
                lineHeight = StatusLineHeight,
                maxLines = 1,
            )
        }
        if (menuOpen) {
            LanguageMenu(
                current = tab.language,
                detected = tab.file?.let { SyntaxDetector.detect(it.name) }?.takeIf { it != SyntaxLanguage.NONE },
                onPick = { language ->
                    tab.language = language
                    state.statusMessage = "Syntax: ${language.displayName}"
                    menuOpen = false
                },
                onDismiss = { menuOpen = false },
            )
        }
    }
}

@Composable
private fun StatusSegment(text: String) {
    Spacer(Modifier.width(20.dp))
    Text(text, color = EditorTheme.OnSurfaceMuted, fontSize = 11.5.sp, lineHeight = StatusLineHeight, maxLines = 1)
}

private fun groupThousands(value: Long): String {
    val digits = value.toString()
    val out = StringBuilder()
    for ((i, c) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
        out.append(c)
    }
    return out.toString()
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${oneDecimal(bytes / 1024.0)} KB"
    bytes < 1024L * 1024L * 1024L -> "${oneDecimal(bytes / (1024.0 * 1024.0))} MB"
    else -> "${oneDecimal(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
}

private fun oneDecimal(value: Double): String {
    val tenths = Math.round(value * 10.0)
    return "${tenths / 10}.${tenths % 10}"
}
