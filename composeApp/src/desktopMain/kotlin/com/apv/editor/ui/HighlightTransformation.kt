package com.apv.editor.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.apv.editor.syntax.SyntaxHighlighter
import com.apv.editor.syntax.SyntaxLanguage

/**
 * Applies syntax-highlighting spans to the editor's raw text.
 *
 * `BasicTextField` calls [filter] on every [androidx.compose.ui.text.input.TextFieldValue] change
 * it's given — that includes pure cursor/selection moves (arrow keys, clicks, drag-select), not
 * just edits. Re-running the tokenizer over the whole document on every one of those calls was
 * wasted work: nothing about the tokens changes when only the selection does.
 *
 * This caches the last [TransformedText] result keyed by the raw text's identity. Selection-only
 * changes reuse a `TextFieldValue` whose backing `text` String is the same instance (Kotlin
 * Strings are immutable; only edits produce a new one), so an `===` check is enough to skip
 * re-tokenizing entirely on those calls. Real edits go through [SyntaxHighlighter.
 * IncrementalHighlightCache], which resumes tokenizing from the edited region instead of
 * rescanning the whole document — see that class's doc comment for what it does and doesn't save.
 * Building the final [AnnotatedString] below still touches every token in the document; that part
 * is inherent to `VisualTransformation`'s whole-field contract and isn't something either cache
 * avoids.
 */
class HighlightTransformation(private val language: SyntaxLanguage) : VisualTransformation {

    private var cachedRaw: String? = null
    private var cachedResult: TransformedText? = null
    private val highlightCache = SyntaxHighlighter.IncrementalHighlightCache()

    override fun filter(text: AnnotatedString): TransformedText {
        if (language == SyntaxLanguage.NONE || text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val raw = text.text
        cachedResult?.let { if (raw === cachedRaw) return it }

        val tokens = highlightCache.highlight(raw, language)
        val result = if (tokens.isEmpty()) {
            TransformedText(text, OffsetMapping.Identity)
        } else {
            val builder = AnnotatedString.Builder(raw)
            for (token in tokens) {
                if (token.start >= raw.length) continue
                val end = minOf(token.end, raw.length)
                if (end <= token.start) continue
                builder.addStyle(EditorTheme.styleFor(token.kind, token.depth), token.start, end)
            }
            TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }

        cachedRaw = raw
        cachedResult = result
        return result
    }
}
