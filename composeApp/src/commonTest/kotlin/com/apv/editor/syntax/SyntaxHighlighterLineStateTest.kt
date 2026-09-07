package com.apv.editor.syntax

import com.apv.editor.syntax.SyntaxHighlighter.LineHighlightState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SyntaxHighlighter.highlightLine] is what lets the large-file editor's passive (non-active)
 * lines render a block comment or triple-quoted string correctly even though — unlike the normal
 * editor — it only ever has one line's text in hand, not the whole document. These tests drive it
 * directly rather than diffing against [SyntaxHighlighter.highlight] on a full multi-line
 * document: that whole-document path tracks real nested bracket depth across lines, which
 * [SyntaxHighlighter.highlightLine] deliberately does not (see its doc comment) — so the two are
 * only expected to agree line-for-line when a line has no brackets to disagree about.
 */
class SyntaxHighlighterLineStateTest {

    @Test
    fun blockCommentClosingOnTheSameLineStaysNone() {
        val line = "val x = 1 /* comment */ val y = 2"
        val (tokens, exit) = SyntaxHighlighter.highlightLine(line, SyntaxLanguage.KOTLIN, LineHighlightState.NONE)
        assertEquals(LineHighlightState.NONE, exit)
        val comment = tokens.first { it.kind == TokenKind.COMMENT }
        assertEquals("/* comment */", line.substring(comment.start, comment.end))
    }

    @Test
    fun unclosedBlockCommentCarriesStateForward() {
        val line = "val x = 1 /* comment starts here"
        val (tokens, exit) = SyntaxHighlighter.highlightLine(line, SyntaxLanguage.KOTLIN, LineHighlightState.NONE)
        assertEquals(LineHighlightState.BLOCK_COMMENT, exit)
        val comment = tokens.first { it.kind == TokenKind.COMMENT }
        assertEquals(line.indexOf("/*"), comment.start)
        assertEquals(line.length, comment.end)
    }

    @Test
    fun lineFullyInsideAnOpenBlockCommentIsAllComment() {
        val line = "still inside the comment, nothing else matters { } \" not a string"
        val (tokens, exit) = SyntaxHighlighter.highlightLine(line, SyntaxLanguage.KOTLIN, LineHighlightState.BLOCK_COMMENT)
        assertEquals(LineHighlightState.BLOCK_COMMENT, exit)
        assertEquals(listOf(Token(0, line.length, TokenKind.COMMENT)), tokens)
    }

    @Test
    fun blockCommentClosingMidLineTokenizesTheRestNormally() {
        val line = "still inside */ val after = 42"
        val (tokens, exit) = SyntaxHighlighter.highlightLine(line, SyntaxLanguage.KOTLIN, LineHighlightState.BLOCK_COMMENT)
        assertEquals(LineHighlightState.NONE, exit)
        val closeAt = line.indexOf("*/") + 2
        assertEquals(Token(0, closeAt, TokenKind.COMMENT), tokens.first())
        assertTrue(tokens.any { it.kind == TokenKind.KEYWORD && line.substring(it.start, it.end) == "val" })
        assertTrue(tokens.any { it.kind == TokenKind.NUMBER && line.substring(it.start, it.end) == "42" })
    }

    @Test
    fun tripleQuotedStringSpansSeveralLines() {
        val line1 = "val s = \"\"\"start of a"
        val (_, exit1) = SyntaxHighlighter.highlightLine(line1, SyntaxLanguage.KOTLIN, LineHighlightState.NONE)
        assertEquals(LineHighlightState.TRIPLE_STRING, exit1)

        val line2 = "middle line, still a string { not real brackets"
        val (t2, exit2) = SyntaxHighlighter.highlightLine(line2, SyntaxLanguage.KOTLIN, LineHighlightState.TRIPLE_STRING)
        assertEquals(LineHighlightState.TRIPLE_STRING, exit2)
        assertEquals(listOf(Token(0, line2.length, TokenKind.STRING)), t2)

        val line3 = "end of it\"\"\" val after = true"
        val (t3, exit3) = SyntaxHighlighter.highlightLine(line3, SyntaxLanguage.KOTLIN, LineHighlightState.TRIPLE_STRING)
        assertEquals(LineHighlightState.NONE, exit3)
        val closeAt = line3.indexOf("\"\"\"") + 3
        assertEquals(TokenKind.STRING, t3.first().kind)
        assertEquals(closeAt, t3.first().end)
    }

    @Test
    fun languageWithoutBlockCommentsNeverEntersThatState() {
        // Ruby has only a line comment — no block-comment delimiters to (mis)detect.
        val (_, exit) = SyntaxHighlighter.highlightLine("x = 1 # not */ special here", SyntaxLanguage.RUBY, LineHighlightState.NONE)
        assertEquals(LineHighlightState.NONE, exit)
    }

    @Test
    fun matchesWholeDocumentHighlightWhenLinesHaveNoBrackets() {
        // No brackets anywhere, so there's nothing for highlightLine's "no cross-line bracket
        // depth" simplification (see its doc comment) to disagree with — this instead checks the
        // rest of the tokenizer (keywords, numbers, strings, annotations) stays byte-for-byte
        // identical to the whole-document path when nothing multi-line is going on.
        val lines = listOf(
            "val name = \"hello world\"",
            "// just a comment",
            "val count = 42",
            "@Deprecated",
        )
        var state = LineHighlightState.NONE
        for (line in lines) {
            val (tokens, exit) = SyntaxHighlighter.highlightLine(line, SyntaxLanguage.KOTLIN, state)
            assertEquals(SyntaxHighlighter.highlight(line, SyntaxLanguage.KOTLIN), tokens, "line=\"$line\"")
            assertEquals(LineHighlightState.NONE, exit, "line=\"$line\"")
            state = exit
        }
    }

    @Test
    fun emptyLineInsideBlockCommentStaysInComment() {
        val (tokens, exit) = SyntaxHighlighter.highlightLine("", SyntaxLanguage.KOTLIN, LineHighlightState.BLOCK_COMMENT)
        assertEquals(LineHighlightState.BLOCK_COMMENT, exit)
        assertEquals(listOf(Token(0, 0, TokenKind.COMMENT)), tokens)
    }
}
