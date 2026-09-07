package com.apv.editor.syntax

enum class TokenKind {
    KEYWORD,
    TYPE,
    STRING,
    NUMBER,
    COMMENT,
    ANNOTATION,
    FUNCTION,
    HEADING,
    LINK,
    PUNCTUATION,
    BRACKET_ERROR,
}

/** [depth] is the nesting level for bracket tokens (rainbow coloring); -1 for everything else. */
data class Token(val start: Int, val end: Int, val kind: TokenKind, val depth: Int = -1)

private data class LangSpec(
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val lineComment: String? = null,
    val blockCommentStart: String? = null,
    val blockCommentEnd: String? = null,
    val stringDelimiters: List<Char> = listOf('"', '\''),
    val tripleQuoted: Boolean = false,
    val annotationPrefix: Char? = null,
)

object SyntaxHighlighter {

    fun highlight(text: String, language: SyntaxLanguage): List<Token> {
        if (text.isEmpty()) return emptyList()
        return when (language) {
            SyntaxLanguage.MARKDOWN -> highlightMarkdown(text)
            SyntaxLanguage.JSON -> highlightCode(text, jsonSpec)
            SyntaxLanguage.YAML -> highlightYaml(text)
            SyntaxLanguage.HTML, SyntaxLanguage.XML -> highlightMarkup(text)
            SyntaxLanguage.NONE -> emptyList()
            else -> highlightCode(text, langSpec(language))
        }
    }

    /**
     * State carried from one line into the next for [highlightLine] — whether a multi-line
     * construct (a block comment or a triple-quoted string) that started on an earlier line is
     * still open going into this one.
     */
    enum class LineHighlightState { NONE, BLOCK_COMMENT, TRIPLE_STRING }

    /**
     * Tokenizes exactly one line, given the state carried in from the line before it (see
     * [LineHighlightState]). For callers that render one line at a time and can't afford to
     * materialize (or rescan) the whole document just to get that line's tokens right —
     * concretely, [com.apv.editor.ui.LargeEditorPane]'s passive (non-active) lines — but still
     * want a block comment or triple-quoted string to color correctly across a line boundary
     * instead of looking like it ends at '\n' the way a naive per-line [highlight] call would.
     *
     * This is a separate, purpose-built scan rather than a reuse of [IncrementalHighlightCache]'s
     * [scanSegments]: that machinery is built around having the *whole* document text in hand
     * (it resumes from a byte offset into one big String), which is exactly what large-file mode
     * exists to avoid. [highlightLine] only ever needs one line's content plus a 3-value enum.
     *
     * Bracket rainbow-depth nesting is deliberately not tracked across the line boundary here
     * (every bracket is reported at depth 0, never [TokenKind.BRACKET_ERROR]) — that would need
     * its own carried state too, and unlike comments/strings it's a purely cosmetic feature to
     * begin with, so it's left as a known gap rather than growing this further.
     *
     * Returns the tokens (offsets relative to [content], not any larger document) and the state
     * to carry into the *next* line.
     */
    fun highlightLine(
        content: String,
        language: SyntaxLanguage,
        entryState: LineHighlightState,
    ): Pair<List<Token>, LineHighlightState> {
        val spec = when (language) {
            SyntaxLanguage.MARKDOWN, SyntaxLanguage.YAML, SyntaxLanguage.HTML, SyntaxLanguage.XML, SyntaxLanguage.NONE ->
                return highlight(content, language) to LineHighlightState.NONE
            SyntaxLanguage.JSON -> jsonSpec
            else -> langSpec(language)
        }
        return highlightCodeLine(content, spec, entryState)
    }

    private fun highlightCodeLine(
        text: String,
        spec: LangSpec,
        entryState: LineHighlightState,
    ): Pair<List<Token>, LineHighlightState> {
        val tokens = mutableListOf<Token>()
        val n = text.length
        var i = 0

        if (entryState == LineHighlightState.BLOCK_COMMENT) {
            val closeMarker = spec.blockCommentEnd
            val closeAt = if (closeMarker != null) text.indexOf(closeMarker) else -1
            if (closeAt == -1) {
                tokens += Token(0, n, TokenKind.COMMENT)
                return tokens to LineHighlightState.BLOCK_COMMENT
            }
            tokens += Token(0, closeAt + closeMarker!!.length, TokenKind.COMMENT)
            i = closeAt + closeMarker.length
        } else if (entryState == LineHighlightState.TRIPLE_STRING) {
            val closeAt = text.indexOf("\"\"\"")
            if (closeAt == -1) {
                tokens += Token(0, n, TokenKind.STRING)
                return tokens to LineHighlightState.TRIPLE_STRING
            }
            tokens += Token(0, closeAt + 3, TokenKind.STRING)
            i = closeAt + 3
        }

        while (i < n) {
            val c = text[i]

            if (spec.lineComment != null && text.startsWith(spec.lineComment, i)) {
                tokens += Token(i, n, TokenKind.COMMENT)
                return tokens to LineHighlightState.NONE
            }

            if (spec.blockCommentStart != null && text.startsWith(spec.blockCommentStart, i)) {
                val closeAt = text.indexOf(spec.blockCommentEnd!!, i + spec.blockCommentStart.length)
                if (closeAt == -1) {
                    tokens += Token(i, n, TokenKind.COMMENT)
                    return tokens to LineHighlightState.BLOCK_COMMENT
                }
                tokens += Token(i, closeAt + spec.blockCommentEnd.length, TokenKind.COMMENT)
                i = closeAt + spec.blockCommentEnd.length
                continue
            }

            if (spec.tripleQuoted && i + 2 < n && c == '"' && text[i + 1] == '"' && text[i + 2] == '"') {
                val closeAt = text.indexOf("\"\"\"", i + 3)
                if (closeAt == -1) {
                    tokens += Token(i, n, TokenKind.STRING)
                    return tokens to LineHighlightState.TRIPLE_STRING
                }
                tokens += Token(i, closeAt + 3, TokenKind.STRING)
                i = closeAt + 3
                continue
            }

            if (c in spec.stringDelimiters) {
                val end = scanString(text, i, c)
                tokens += Token(i, end, TokenKind.STRING)
                i = end
                continue
            }

            if (spec.annotationPrefix != null && c == spec.annotationPrefix && i + 1 < n && text[i + 1].isLetter()) {
                val end = scanWhile(text, i + 1) { it.isLetterOrDigit() || it == '_' || it == '.' }
                tokens += Token(i, end, TokenKind.ANNOTATION)
                i = end
                continue
            }

            if (c.isDigit()) {
                val end = scanWhile(text, i) { it.isLetterOrDigit() || it == '.' || it == '_' }
                tokens += Token(i, end, TokenKind.NUMBER)
                i = end
                continue
            }

            if (c.isLetter() || c == '_' || c == '$') {
                val end = scanWhile(text, i) { it.isLetterOrDigit() || it == '_' || it == '$' }
                val word = text.substring(i, end)
                when {
                    word in spec.keywords -> tokens += Token(i, end, TokenKind.KEYWORD)
                    word in spec.types -> tokens += Token(i, end, TokenKind.TYPE)
                    word.isNotEmpty() && word[0].isUpperCase() && spec.types.isNotEmpty() -> {
                        tokens += Token(i, end, TokenKind.TYPE)
                    }
                    end < n && text[end] == '(' -> tokens += Token(i, end, TokenKind.FUNCTION)
                }
                i = end
                continue
            }

            if (c == '(' || c == '[' || c == '{' || c == ')' || c == ']' || c == '}') {
                tokens += Token(i, i + 1, TokenKind.PUNCTUATION, 0)
                i++
                continue
            }

            i++
        }
        return tokens to LineHighlightState.NONE
    }

    // ── Generic code tokenizer ────────────────────────────────────────────────

    private fun highlightCode(text: String, spec: LangSpec): List<Token> {
        val tokens = mutableListOf<Token>()
        val brackets = ArrayDeque<Char>()
        var i = 0
        val n = text.length

        while (i < n) {
            val c = text[i]

            // Line comment
            if (spec.lineComment != null && text.startsWith(spec.lineComment, i)) {
                val end = text.indexOf('\n', i).let { if (it == -1) n else it }
                tokens += Token(i, end, TokenKind.COMMENT)
                i = end
                continue
            }

            // Block comment
            if (spec.blockCommentStart != null && text.startsWith(spec.blockCommentStart, i)) {
                val closeAt = text.indexOf(spec.blockCommentEnd!!, i + spec.blockCommentStart.length)
                val end = if (closeAt == -1) n else closeAt + spec.blockCommentEnd.length
                tokens += Token(i, end, TokenKind.COMMENT)
                i = end
                continue
            }

            // Triple-quoted string (Kotlin/Python style)
            if (spec.tripleQuoted && i + 2 < n && c == '"' && text[i + 1] == '"' && text[i + 2] == '"') {
                val closeAt = text.indexOf("\"\"\"", i + 3)
                val end = if (closeAt == -1) n else closeAt + 3
                tokens += Token(i, end, TokenKind.STRING)
                i = end
                continue
            }

            // String literal
            if (c in spec.stringDelimiters) {
                val end = scanString(text, i, c)
                tokens += Token(i, end, TokenKind.STRING)
                i = end
                continue
            }

            // Annotation (e.g. @Override in Java, @Composable in Kotlin)
            if (spec.annotationPrefix != null && c == spec.annotationPrefix && i + 1 < n && text[i + 1].isLetter()) {
                val end = scanWhile(text, i + 1) { it.isLetterOrDigit() || it == '_' || it == '.' }
                tokens += Token(i, end, TokenKind.ANNOTATION)
                i = end
                continue
            }

            // Number
            if (c.isDigit()) {
                val end = scanWhile(text, i) { it.isLetterOrDigit() || it == '.' || it == '_' }
                tokens += Token(i, end, TokenKind.NUMBER)
                i = end
                continue
            }

            // Identifier / keyword
            if (c.isLetter() || c == '_' || c == '$') {
                val end = scanWhile(text, i) { it.isLetterOrDigit() || it == '_' || it == '$' }
                val word = text.substring(i, end)
                when {
                    word in spec.keywords -> tokens += Token(i, end, TokenKind.KEYWORD)
                    word in spec.types -> tokens += Token(i, end, TokenKind.TYPE)
                    word.isNotEmpty() && word[0].isUpperCase() && spec.types.isNotEmpty() -> {
                        tokens += Token(i, end, TokenKind.TYPE)
                    }
                    end < n && text[end] == '(' -> tokens += Token(i, end, TokenKind.FUNCTION)
                }
                i = end
                continue
            }

            // Bracket (rainbow depth). Strings/comments are already consumed above,
            // so brackets inside them never reach here.
            when (c) {
                '(', '[', '{' -> {
                    tokens += Token(i, i + 1, TokenKind.PUNCTUATION, brackets.size)
                    brackets.addLast(c)
                    i++
                    continue
                }
                ')', ']', '}' -> {
                    val expected = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (brackets.lastOrNull() == expected) {
                        brackets.removeLast()
                        tokens += Token(i, i + 1, TokenKind.PUNCTUATION, brackets.size)
                    } else {
                        tokens += Token(i, i + 1, TokenKind.BRACKET_ERROR)
                    }
                    i++
                    continue
                }
            }

            i++
        }

        return tokens
    }

    private fun scanString(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\\' && i + 1 < n) { i += 2; continue }
            if (c == '\n') return i
            if (c == quote) return i + 1
            i++
        }
        return n
    }

    private inline fun scanWhile(text: String, start: Int, predicate: (Char) -> Boolean): Int {
        var i = start
        val n = text.length
        while (i < n && predicate(text[i])) i++
        return i
    }

    // ── Incremental (line-resumable) tokenizing ─────────────────────────────────
    //
    // highlight() above is a fresh whole-document scan every call — cheap enough on its own
    // (a few ms even at the largest sizes LargeFileGuard allows) that HighlightTransformation's
    // reference-equality cache is enough for read-only navigation. But it still rescans the
    // whole document on every real edit, which scales with document size rather than edit size.
    // IncrementalHighlightCache below resumes tokenizing from the edited region instead.
    //
    // scanSegments reimplements highlightCode's loop, but records one Segment per line and can
    // start mid-document with a seeded bracket stack instead of always starting at offset 0.
    // A segment boundary is only ever recorded right after a bare '\n' is stepped over as its
    // own character — never in the middle of a multi-line token (block comment / triple-quoted
    // string), since those are consumed as a single loop step that jumps straight to the token's
    // close (or EOF). That's what makes a Segment's start offset a safe resume point: it's never
    // "inside" a multi-line construct, so the only carried state a resume needs is the bracket
    // stack (entryBrackets), not a comment/string-in-progress flag.

    /**
     * One line's worth of tokenizing output from [scanSegments], plus the bracket-stack state
     * at its boundaries so a later incremental scan can decide whether it's safe to resume from
     * (or splice back in) this segment without re-deriving it.
     */
    private data class Segment(
        val start: Int,
        val end: Int,
        val entryBrackets: List<Char>,
        val exitBrackets: List<Char>,
        val tokens: List<Token>,
    ) {
        fun shifted(delta: Int): Segment {
            if (delta == 0) return this
            return Segment(
                start + delta,
                end + delta,
                entryBrackets,
                exitBrackets,
                tokens.map { Token(it.start + delta, it.end + delta, it.kind, it.depth) },
            )
        }
    }

    /**
     * Runs the same scan as [highlightCode], starting at [from] with a bracket stack seeded from
     * [initialBrackets] instead of always starting at 0 with an empty stack, recording one
     * [Segment] per line. Stops as soon as a segment ending at or after [stopSearchAt] has been
     * recorded (pass `Int.MAX_VALUE` to scan to the end of [text]). Always returns at least one
     * segment.
     */
    private fun scanSegments(
        text: String,
        spec: LangSpec,
        from: Int,
        initialBrackets: List<Char>,
        stopSearchAt: Int,
    ): List<Segment> {
        val n = text.length
        if (from >= n) {
            return listOf(Segment(from, from, initialBrackets, initialBrackets, emptyList()))
        }

        val segments = mutableListOf<Segment>()
        val brackets = ArrayDeque<Char>(initialBrackets)
        var entryBrackets = initialBrackets
        val tokens = mutableListOf<Token>()
        var segStart = from
        var i = from

        fun flush(end: Int) {
            val exitBrackets = brackets.toList()
            segments += Segment(segStart, end, entryBrackets, exitBrackets, tokens.toList())
            tokens.clear()
            segStart = end
            entryBrackets = exitBrackets
        }

        while (i < n) {
            val c = text[i]

            // Bare newline: never the first character of a comment/string/annotation token
            // below, so this always falls through to here — the one safe segment boundary.
            if (c == '\n') {
                i++
                flush(i)
                if (i >= stopSearchAt) return segments
                continue
            }

            // Line comment
            if (spec.lineComment != null && text.startsWith(spec.lineComment, i)) {
                val end = text.indexOf('\n', i).let { if (it == -1) n else it }
                tokens += Token(i, end, TokenKind.COMMENT)
                i = end
                continue
            }

            // Block comment
            if (spec.blockCommentStart != null && text.startsWith(spec.blockCommentStart, i)) {
                val closeAt = text.indexOf(spec.blockCommentEnd!!, i + spec.blockCommentStart.length)
                val end = if (closeAt == -1) n else closeAt + spec.blockCommentEnd.length
                tokens += Token(i, end, TokenKind.COMMENT)
                i = end
                continue
            }

            // Triple-quoted string (Kotlin/Python style)
            if (spec.tripleQuoted && i + 2 < n && c == '"' && text[i + 1] == '"' && text[i + 2] == '"') {
                val closeAt = text.indexOf("\"\"\"", i + 3)
                val end = if (closeAt == -1) n else closeAt + 3
                tokens += Token(i, end, TokenKind.STRING)
                i = end
                continue
            }

            // String literal
            if (c in spec.stringDelimiters) {
                val end = scanString(text, i, c)
                tokens += Token(i, end, TokenKind.STRING)
                i = end
                continue
            }

            // Annotation (e.g. @Override in Java, @Composable in Kotlin)
            if (spec.annotationPrefix != null && c == spec.annotationPrefix && i + 1 < n && text[i + 1].isLetter()) {
                val end = scanWhile(text, i + 1) { it.isLetterOrDigit() || it == '_' || it == '.' }
                tokens += Token(i, end, TokenKind.ANNOTATION)
                i = end
                continue
            }

            // Number
            if (c.isDigit()) {
                val end = scanWhile(text, i) { it.isLetterOrDigit() || it == '.' || it == '_' }
                tokens += Token(i, end, TokenKind.NUMBER)
                i = end
                continue
            }

            // Identifier / keyword
            if (c.isLetter() || c == '_' || c == '$') {
                val end = scanWhile(text, i) { it.isLetterOrDigit() || it == '_' || it == '$' }
                val word = text.substring(i, end)
                when {
                    word in spec.keywords -> tokens += Token(i, end, TokenKind.KEYWORD)
                    word in spec.types -> tokens += Token(i, end, TokenKind.TYPE)
                    word.isNotEmpty() && word[0].isUpperCase() && spec.types.isNotEmpty() -> {
                        tokens += Token(i, end, TokenKind.TYPE)
                    }
                    end < n && text[end] == '(' -> tokens += Token(i, end, TokenKind.FUNCTION)
                }
                i = end
                continue
            }

            // Bracket (rainbow depth). Strings/comments are already consumed above,
            // so brackets inside them never reach here.
            when (c) {
                '(', '[', '{' -> {
                    tokens += Token(i, i + 1, TokenKind.PUNCTUATION, brackets.size)
                    brackets.addLast(c)
                    i++
                    continue
                }
                ')', ']', '}' -> {
                    val expected = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (brackets.lastOrNull() == expected) {
                        brackets.removeLast()
                        tokens += Token(i, i + 1, TokenKind.PUNCTUATION, brackets.size)
                    } else {
                        tokens += Token(i, i + 1, TokenKind.BRACKET_ERROR)
                    }
                    i++
                    continue
                }
            }

            i++
        }

        // Only flush if there's unflushed content — a document ending exactly at a '\n' (very
        // common) already flushed a real segment covering that line inside the loop above;
        // flushing again here would append a redundant, indistinguishable-looking empty segment
        // that later resume-point lookups could mistake for a genuine checkpoint.
        if (segStart < n) flush(n)
        return segments
    }

    /** Index of the last segment whose start <= [target], via binary search, or -1 if none. */
    private fun floorSegmentIndex(segments: List<Segment>, target: Int): Int {
        var lo = 0
        var hi = segments.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (segments[mid].start <= target) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    /**
     * Length of the common suffix of [a] and [b], not extending past [boundA]/[boundB] into
     * either string — bounds the comparison so it doesn't re-derive characters already covered
     * by a known common prefix.
     */
    private fun commonSuffixLength(a: String, b: String, boundA: Int, boundB: Int): Int {
        val maxA = a.length - boundA
        val maxB = b.length - boundB
        val max = minOf(maxA, maxB).coerceAtLeast(0)
        var i = 0
        while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }

    /**
     * Language spec for the incremental path, or `null` for languages that stay on the
     * unmodified stateless [highlight] path — profiling showed Markdown/YAML/HTML/XML tokenizing
     * cost is already negligible, so there's nothing worth incrementalizing there.
     */
    private fun langSpecForIncremental(language: SyntaxLanguage): LangSpec? = when (language) {
        SyntaxLanguage.MARKDOWN, SyntaxLanguage.YAML, SyntaxLanguage.HTML, SyntaxLanguage.XML, SyntaxLanguage.NONE -> null
        SyntaxLanguage.JSON -> jsonSpec
        else -> langSpec(language)
    }

    /**
     * Per-tab incremental tokenizing cache. [highlight] resumes scanning from the edited region
     * instead of re-tokenizing the whole document on every keystroke, falling back to a full
     * rescan whenever it can't prove the reuse is safe (see [incrementalRescan]).
     *
     * Not thread-safe; scope one instance per editor tab and call it from the same thread
     * `HighlightTransformation.filter` runs on.
     */
    class IncrementalHighlightCache {
        private var cachedText: String? = null
        private var cachedLanguage: SyntaxLanguage? = null
        private var segments: List<Segment>? = null

        fun highlight(text: String, language: SyntaxLanguage): List<Token> {
            val spec = langSpecForIncremental(language)
            if (spec == null) {
                // Not an incremental-eligible language (or NONE) — nothing to cache; make sure a
                // stale cache from a previous language isn't reused if this tab's language changes
                // back to an eligible one later.
                cachedText = null
                cachedLanguage = null
                segments = null
                return SyntaxHighlighter.highlight(text, language)
            }

            val prevText = cachedText
            val prevSegments = segments
            val result: List<Segment> = when {
                prevText == null || cachedLanguage != language || prevSegments == null ->
                    scanSegments(text, spec, 0, emptyList(), Int.MAX_VALUE)
                prevText == text -> prevSegments
                else -> incrementalRescan(prevText, prevSegments, text, spec)
            }

            cachedText = text
            cachedLanguage = language
            segments = result
            return result.flatMap { it.tokens }
        }

        private fun incrementalRescan(
            oldText: String,
            oldSegments: List<Segment>,
            newText: String,
            spec: LangSpec,
        ): List<Segment> {
            val prefixLen = commonPrefixLength(oldText, newText)
            var resumeIdx = floorSegmentIndex(oldSegments, prefixLen)
            if (resumeIdx < 0) {
                // Shouldn't happen (scanSegments always emits a segment starting at 0), but
                // fall back to a full rescan rather than risk an inconsistent result.
                return scanSegments(newText, spec, 0, emptyList(), Int.MAX_VALUE)
            }
            // An empty (start == end) segment is always an end-of-document placeholder (see
            // scanSegments) — e.g. "the previous document ended while a block comment was still
            // open". Resuming from its position with just its bracket state would silently
            // forget that open comment/string, since that continuation isn't captured anywhere
            // except by actually rescanning through it. Back up to the nearest real segment
            // instead, so the resume rescan re-derives that state honestly against the new text.
            while (resumeIdx > 0 && oldSegments[resumeIdx].start == oldSegments[resumeIdx].end) resumeIdx--
            val resumeSeg = oldSegments[resumeIdx]

            val suffixLen = commonSuffixLength(oldText, newText, resumeSeg.start, resumeSeg.start)
            val changedEndNew = (newText.length - suffixLen).coerceAtLeast(resumeSeg.start)

            val newHead = scanSegments(newText, spec, resumeSeg.start, resumeSeg.entryBrackets, changedEndNew)
            val newHeadEnd = newHead.last().end
            val lengthDelta = newText.length - oldText.length
            val oldCorrespondingOffset = newHeadEnd - lengthDelta

            val spliceIdx = floorSegmentIndex(oldSegments, oldCorrespondingOffset)
            val canSplice = newHeadEnd < newText.length &&
                spliceIdx > resumeIdx &&
                oldSegments[spliceIdx].start == oldCorrespondingOffset &&
                oldSegments[spliceIdx].entryBrackets == newHead.last().exitBrackets

            // Built with a single pre-sized ArrayList + addAll rather than the `+` operator
            // chained twice (`a + b + c`) — each `+` on a List copies both operands into a new
            // list, so a chain of two would copy the untouched prefix (the bulk of the document
            // on a small edit) twice over. That defeats the point of resuming instead of
            // rescanning: the whole reason this path is worth it is that oldSegments.subList(0,
            // resumeIdx) — most of the document, unchanged — should be an O(1) view, not copied
            // at all until this single final assembly.
            return if (canSplice) {
                val tail = oldSegments.subList(spliceIdx, oldSegments.size)
                val out = ArrayList<Segment>(resumeIdx + newHead.size + tail.size)
                out.addAll(oldSegments.subList(0, resumeIdx))
                out.addAll(newHead)
                for (seg in tail) out.add(seg.shifted(lengthDelta))
                out
            } else {
                // Safe fallback, always correct: rescan everything from newHeadEnd to the end of
                // the document. newHead's last segment and newTail's first segment are adjacent,
                // non-overlapping ranges (newTail starts scanning exactly at newHeadEnd) — nothing
                // here is dropped or double-counted.
                val newTail = scanSegments(newText, spec, newHeadEnd, newHead.last().exitBrackets, Int.MAX_VALUE)
                val out = ArrayList<Segment>(resumeIdx + newHead.size + newTail.size)
                out.addAll(oldSegments.subList(0, resumeIdx))
                out.addAll(newHead)
                out.addAll(newTail)
                out
            }
        }
    }

    // ── Markdown ──────────────────────────────────────────────────────────────

    private fun highlightMarkdown(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var lineStart = 0
        val n = text.length

        while (lineStart < n) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) n else it }
            val line = text.substring(lineStart, lineEnd)

            if (line.startsWith("#")) {
                tokens += Token(lineStart, lineEnd, TokenKind.HEADING)
            } else if (line.startsWith(">")) {
                tokens += Token(lineStart, lineEnd, TokenKind.COMMENT)
            } else if (line.startsWith("```")) {
                tokens += Token(lineStart, lineEnd, TokenKind.KEYWORD)
            } else {
                addInlineMarkdown(text, lineStart, lineEnd, tokens)
            }

            lineStart = lineEnd + 1
        }
        return tokens
    }

    private fun addInlineMarkdown(text: String, from: Int, to: Int, out: MutableList<Token>) {
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == '`' -> {
                    val close = text.indexOf('`', i + 1).let { if (it == -1 || it >= to) -1 else it }
                    if (close > 0) {
                        out += Token(i, close + 1, TokenKind.STRING)
                        i = close + 1
                        continue
                    }
                }
                c == '[' -> {
                    val close = text.indexOf(']', i + 1).let { if (it == -1 || it >= to) -1 else it }
                    if (close > 0 && close + 1 < to && text[close + 1] == '(') {
                        val parClose = text.indexOf(')', close + 2).let { if (it == -1 || it >= to) -1 else it }
                        if (parClose > 0) {
                            out += Token(i, parClose + 1, TokenKind.LINK)
                            i = parClose + 1
                            continue
                        }
                    }
                }
                c == '*' || c == '_' -> {
                    val end = scanWhile(text, i + 1) { it != c && it != '\n' }
                    if (end < to && text[end] == c) {
                        out += Token(i, end + 1, TokenKind.KEYWORD)
                        i = end + 1
                        continue
                    }
                }
            }
            i++
        }
    }

    // ── YAML ──────────────────────────────────────────────────────────────────

    private fun highlightYaml(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var lineStart = 0
        val n = text.length
        while (lineStart < n) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) n else it }
            var i = lineStart
            while (i < lineEnd && (text[i] == ' ' || text[i] == '\t' || text[i] == '-')) i++
            if (i < lineEnd && text[i] == '#') {
                tokens += Token(i, lineEnd, TokenKind.COMMENT)
            } else {
                val colon = text.indexOf(':', i).let { if (it == -1 || it >= lineEnd) -1 else it }
                if (colon > i) tokens += Token(i, colon, TokenKind.KEYWORD)
                var j = (if (colon > i) colon + 1 else i)
                while (j < lineEnd) {
                    val c = text[j]
                    if (c == '"' || c == '\'') {
                        val end = scanString(text, j, c)
                        tokens += Token(j, end, TokenKind.STRING)
                        j = end
                    } else j++
                }
            }
            lineStart = lineEnd + 1
        }
        return tokens
    }

    // ── HTML/XML ──────────────────────────────────────────────────────────────

    private fun highlightMarkup(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '<' && i + 3 < n && text.startsWith("<!--", i)) {
                val end = text.indexOf("-->", i + 4).let { if (it == -1) n else it + 3 }
                tokens += Token(i, end, TokenKind.COMMENT)
                i = end
                continue
            }
            if (c == '<') {
                val end = text.indexOf('>', i + 1).let { if (it == -1) n else it + 1 }
                tokens += Token(i, end, TokenKind.KEYWORD)
                // Attribute strings inside
                var j = i + 1
                while (j < end) {
                    val ch = text[j]
                    if (ch == '"' || ch == '\'') {
                        val sEnd = scanString(text, j, ch)
                        tokens += Token(j, sEnd, TokenKind.STRING)
                        j = sEnd
                    } else j++
                }
                i = end
                continue
            }
            i++
        }
        return tokens
    }

    // ── Language specs ────────────────────────────────────────────────────────

    private val jsonSpec = LangSpec(
        keywords = setOf("true", "false", "null"),
    )

    private fun langSpec(lang: SyntaxLanguage): LangSpec = when (lang) {
        SyntaxLanguage.KOTLIN -> kotlinSpec
        SyntaxLanguage.JAVA -> javaSpec
        SyntaxLanguage.JAVASCRIPT -> jsSpec
        SyntaxLanguage.TYPESCRIPT -> tsSpec
        SyntaxLanguage.PYTHON -> pythonSpec
        SyntaxLanguage.RUBY -> rubySpec
        SyntaxLanguage.GO -> goSpec
        SyntaxLanguage.RUST -> rustSpec
        SyntaxLanguage.C_FAMILY -> cSpec
        SyntaxLanguage.CSHARP -> csharpSpec
        SyntaxLanguage.SWIFT -> swiftSpec
        SyntaxLanguage.SHELL -> shellSpec
        SyntaxLanguage.SQL -> sqlSpec
        SyntaxLanguage.CSS -> cssSpec
        SyntaxLanguage.PHP -> phpSpec
        SyntaxLanguage.GROOVY -> groovySpec
        SyntaxLanguage.LUA -> luaSpec
        SyntaxLanguage.SCALA -> scalaSpec
        SyntaxLanguage.CLOJURE -> clojureSpec
        else -> LangSpec()
    }

    private val kotlinKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
        "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import",
        "init", "param", "property", "receiver", "set", "setparam", "where", "actual",
        "abstract", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
        "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline",
        "open", "operator", "out", "override", "private", "protected", "public", "reified",
        "sealed", "suspend", "tailrec", "vararg",
    )
    private val kotlinTypes = setOf(
        "Any", "Boolean", "Byte", "Char", "CharSequence", "Double", "Float", "Function", "Int",
        "Long", "Nothing", "Number", "Short", "String", "Unit", "Array", "List", "Map", "Set",
        "MutableList", "MutableMap", "MutableSet", "Pair", "Triple", "Sequence", "Throwable",
    )
    private val kotlinSpec = LangSpec(
        keywords = kotlinKeywords, types = kotlinTypes,
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
        tripleQuoted = true, annotationPrefix = '@',
    )

    private val javaKeywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
        "null", "var", "yield", "record", "sealed", "permits", "non-sealed",
    )
    private val javaSpec = LangSpec(
        keywords = javaKeywords, types = setOf("String", "Object", "Integer", "Long", "Double", "Boolean"),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
        annotationPrefix = '@',
    )

    private val jsKeywords = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
        "do", "else", "enum", "export", "extends", "false", "finally", "for", "function", "if",
        "import", "in", "instanceof", "let", "new", "null", "of", "return", "super", "switch",
        "this", "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield",
        "async", "await", "static",
    )
    private val jsSpec = LangSpec(
        keywords = jsKeywords,
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
        stringDelimiters = listOf('"', '\'', '`'),
    )

    private val tsSpec = LangSpec(
        keywords = jsKeywords + setOf(
            "abstract", "as", "any", "boolean", "constructor", "declare", "interface", "is",
            "module", "namespace", "never", "number", "object", "private", "protected", "public",
            "readonly", "string", "symbol", "type", "undefined", "unknown", "satisfies",
        ),
        types = setOf("Array", "Promise", "Record", "Partial", "Readonly", "Pick", "Omit"),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
        stringDelimiters = listOf('"', '\'', '`'),
    )

    private val pythonKeywords = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
        "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield", "match", "case",
    )
    private val pythonSpec = LangSpec(
        keywords = pythonKeywords,
        lineComment = "#",
        stringDelimiters = listOf('"', '\''),
        tripleQuoted = true, annotationPrefix = '@',
    )

    private val rubyKeywords = setOf(
        "BEGIN", "END", "alias", "and", "begin", "break", "case", "class", "def", "defined?",
        "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next",
        "nil", "not", "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true",
        "undef", "unless", "until", "when", "while", "yield",
    )
    private val rubySpec = LangSpec(keywords = rubyKeywords, lineComment = "#")

    private val goKeywords = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
        "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
        "return", "select", "struct", "switch", "type", "var", "true", "false", "nil",
    )
    private val goSpec = LangSpec(
        keywords = goKeywords,
        types = setOf("string", "int", "int32", "int64", "float32", "float64", "bool", "byte", "rune", "error"),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
        stringDelimiters = listOf('"', '`'),
    )

    private val rustKeywords = setOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
        "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
        "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
        "trait", "true", "type", "unsafe", "use", "where", "while",
    )
    private val rustSpec = LangSpec(
        keywords = rustKeywords,
        types = setOf("i8", "i16", "i32", "i64", "i128", "u8", "u16", "u32", "u64", "u128", "f32", "f64", "bool", "char", "str", "String", "Vec", "Option", "Result"),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
        annotationPrefix = '#',
    )

    private val cKeywords = setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
        "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register",
        "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch",
        "typedef", "union", "unsigned", "void", "volatile", "while", "true", "false", "nullptr",
        "class", "namespace", "template", "typename", "public", "private", "protected", "virtual",
        "new", "delete", "this", "operator", "friend", "explicit", "override", "final", "using",
    )
    private val cSpec = LangSpec(
        keywords = cKeywords,
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val csharpKeywords = setOf(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
        "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
        "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
        "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
        "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
        "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed",
        "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this",
        "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
        "using", "virtual", "void", "volatile", "while", "var", "async", "await", "record",
    )
    private val csharpSpec = LangSpec(
        keywords = csharpKeywords,
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val swiftKeywords = setOf(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import",
        "init", "inout", "internal", "let", "open", "operator", "private", "protocol", "public",
        "static", "struct", "subscript", "typealias", "var", "break", "case", "continue",
        "default", "defer", "do", "else", "fallthrough", "for", "guard", "if", "in", "repeat",
        "return", "switch", "where", "while", "as", "Any", "catch", "false", "is", "nil",
        "rethrows", "super", "self", "Self", "throw", "throws", "true", "try", "async", "await",
    )
    private val swiftSpec = LangSpec(
        keywords = swiftKeywords,
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
        annotationPrefix = '@',
    )

    private val shellKeywords = setOf(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until", "do", "done",
        "in", "function", "return", "exit", "break", "continue", "local", "export", "readonly",
        "shift", "set", "unset", "trap", "echo", "true", "false",
    )
    private val shellSpec = LangSpec(
        keywords = shellKeywords, lineComment = "#",
        stringDelimiters = listOf('"', '\''),
    )

    private val sqlKeywords = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER",
        "TABLE", "INDEX", "VIEW", "JOIN", "INNER", "OUTER", "LEFT", "RIGHT", "FULL", "ON",
        "GROUP", "BY", "ORDER", "HAVING", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN",
        "BETWEEN", "LIKE", "DISTINCT", "UNION", "ALL", "INTO", "VALUES", "SET", "PRIMARY",
        "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "CASE", "WHEN", "THEN", "ELSE", "END",
        "select", "from", "where", "insert", "update", "delete", "create", "drop", "alter",
        "table", "index", "view", "join", "inner", "outer", "left", "right", "full", "on",
        "group", "by", "order", "having", "as", "and", "or", "not", "null", "is", "in",
    )
    private val sqlSpec = LangSpec(
        keywords = sqlKeywords,
        lineComment = "--", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val cssSpec = LangSpec(
        keywords = setOf("important", "inherit", "initial", "unset", "auto", "none"),
        blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val phpSpec = LangSpec(
        keywords = setOf(
            "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
            "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif",
            "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile",
            "extends", "final", "finally", "for", "foreach", "function", "global", "goto", "if",
            "implements", "include", "instanceof", "insteadof", "interface", "isset", "list",
            "namespace", "new", "or", "print", "private", "protected", "public", "require",
            "return", "static", "switch", "throw", "trait", "try", "unset", "use", "var",
            "while", "xor", "yield", "true", "false", "null",
        ),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    )

    private val groovySpec = LangSpec(
        keywords = javaKeywords + setOf("def", "as", "in", "trait"),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
        stringDelimiters = listOf('"', '\''), tripleQuoted = true,
    )

    private val luaSpec = LangSpec(
        keywords = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
            "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until",
            "while",
        ),
        lineComment = "--",
    )

    private val scalaSpec = LangSpec(
        keywords = setOf(
            "abstract", "case", "catch", "class", "def", "do", "else", "extends", "false",
            "final", "finally", "for", "forSome", "if", "implicit", "import", "lazy", "match",
            "new", "null", "object", "override", "package", "private", "protected", "return",
            "sealed", "super", "this", "throw", "trait", "try", "true", "type", "val", "var",
            "while", "with", "yield", "given", "using", "extension",
        ),
        lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
        annotationPrefix = '@',
    )

    private val clojureSpec = LangSpec(
        keywords = setOf(
            "def", "defn", "defmacro", "defmulti", "defmethod", "fn", "let", "if", "when",
            "cond", "case", "do", "loop", "recur", "for", "while", "when-let", "if-let", "try",
            "catch", "finally", "throw", "ns", "in-ns", "require", "use", "import", "true",
            "false", "nil",
        ),
        lineComment = ";",
    )
}
