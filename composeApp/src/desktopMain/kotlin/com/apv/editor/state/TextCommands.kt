package com.apv.editor.state

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The text transformations behind the command palette's "Format: ..." commands. Each takes the text
 * to transform and returns the result, or null when it can't be applied (e.g. invalid JSON), in
 * which case the buffer is left untouched.
 */
object TextCommands {
    private val prettyJson = Json { prettyPrint = true }

    fun prettyPrintJson(text: String): String? = try {
        prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
    } catch (_: Throwable) {
        null
    }

    fun minifyJson(text: String): String? = try {
        Json.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
    } catch (_: Throwable) {
        null
    }

    /** Sorts lines case-insensitively. A trailing newline stays at the end instead of becoming an empty first line. */
    fun sortLines(text: String): String {
        val trailingNewline = text.endsWith("\n")
        val body = if (trailingNewline) text.dropLast(1) else text
        val sorted = body.split('\n').sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString("\n")
        return if (trailingNewline) sorted + "\n" else sorted
    }

    fun trimTrailingWhitespace(text: String): String =
        text.split('\n').joinToString("\n") { it.trimEnd(' ', '\t', '\r') }

    fun upperCase(text: String): String = text.uppercase()

    fun lowerCase(text: String): String = text.lowercase()
}

/**
 * Applies [transform] to the selection, or to the whole buffer when nothing is selected, as one
 * undoable edit (it goes through [TabState.updateText]). Returns false when the tab is a large-file
 * tab, or when [transform] returned null or changed nothing.
 */
fun TabState.applyTextCommand(transform: (String) -> String?): Boolean {
    if (isLargeFile) return false
    val current = textField
    val selection = current.selection
    val wholeBuffer = selection.collapsed
    val start = if (wholeBuffer) 0 else selection.min
    val end = if (wholeBuffer) current.text.length else selection.max
    val source = current.text.substring(start, end)
    val result = transform(source) ?: return false
    if (result == source) return false
    val newText = current.text.substring(0, start) + result + current.text.substring(end)
    val newSelection = if (wholeBuffer) {
        TextRange(selection.start.coerceIn(0, newText.length))
    } else {
        TextRange(start, start + result.length)
    }
    updateText(TextFieldValue(newText, newSelection))
    return true
}
