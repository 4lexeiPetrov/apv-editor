package com.apv.editor.syntax

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SyntaxHighlighter.IncrementalHighlightCache] resumes tokenizing from the edited region instead
 * of rescanning the whole document, and its "splice the untouched tail back in" fast path is only
 * correct when a handful of offset/bracket-state invariants line up exactly (see the class's and
 * `incrementalRescan`'s doc comments). That's exactly the kind of logic that's easy to get subtly
 * wrong at the edges — this test found and pinned down a real bug during development, where a
 * document ending mid an unclosed block comment could have that "still open" fact silently
 * dropped after a later edit. Rather than a handful of hand-picked cases, this drives many
 * pseudo-random edit sequences (including ones designed to create and then poke at unclosed
 * block comments and triple-quoted strings) through the cache and checks, after every single
 * edit, that its output exactly matches [SyntaxHighlighter.highlight] run fresh on the same text.
 */
class SyntaxHighlighterIncrementalTest {

    @Test
    fun incrementalMatchesFullRescanAcrossRandomEdits() {
        val languages = SyntaxLanguage.values().toList()
        var totalChecks = 0

        for (lang in languages) {
            for (seed in 0 until 15) {
                val rng = Random(seed * 7919 + lang.ordinal)
                var text = randomSource(rng, 20 + rng.nextInt(30))
                val cache = SyntaxHighlighter.IncrementalHighlightCache()

                totalChecks++
                assertEquals(
                    SyntaxHighlighter.highlight(text, lang),
                    cache.highlight(text, lang),
                    "lang=$lang seed=$seed step=prime",
                )

                repeat(50) { step ->
                    text = applyRandomEdit(rng, text)
                    totalChecks++
                    assertEquals(
                        SyntaxHighlighter.highlight(text, lang),
                        cache.highlight(text, lang),
                        "lang=$lang seed=$seed step=$step textLen=${text.length}",
                    )
                }
            }
        }
        check(totalChecks > 0)
    }

    /**
     * Targeted at the exact bug class found during development: edits (mostly appends) around a
     * document that deliberately, often, ends mid an unclosed block comment or triple-quoted
     * string. A resume point that forgets "we were still inside that construct" is the one way
     * this cache can silently produce wrong output without throwing.
     */
    @Test
    fun incrementalHandlesUnclosedConstructsAtDocumentEnd() {
        val eligibleLangs = listOf(
            SyntaxLanguage.KOTLIN, SyntaxLanguage.JSON, SyntaxLanguage.PYTHON, SyntaxLanguage.JAVA,
            SyntaxLanguage.JAVASCRIPT, SyntaxLanguage.GO, SyntaxLanguage.C_FAMILY, SyntaxLanguage.CSS,
        )
        val tails = listOf(
            "/* unterminated block comment starts here and never closes",
            "before /* comment */ after /* another unterminated one",
            "val s = \"\"\"unterminated triple quote starts here",
            "normal line\nnormal line 2",
            "// line comment then /* also unterminated",
        )
        val appendChoices = listOf(
            "*/",
            "\"\"\"",
            "\nmore content\n",
            "*/\nval after = 1\n/* new unterminated",
            "\"\"\"\nval after2 = \"\"\"another unterminated",
            " x ",
        )

        for (lang in eligibleLangs) {
            for (seed in 0 until 10) {
                val rng = Random(seed * 104729 + lang.ordinal)
                var text = randomSource(rng, 8 + rng.nextInt(12)) + tails.random(rng)
                val cache = SyntaxHighlighter.IncrementalHighlightCache()

                assertEquals(
                    SyntaxHighlighter.highlight(text, lang),
                    cache.highlight(text, lang),
                    "stress lang=$lang seed=$seed step=prime",
                )

                repeat(30) { step ->
                    text = if (rng.nextBoolean()) {
                        text + appendChoices.random(rng)
                    } else {
                        applyRandomEdit(rng, text)
                    }
                    assertEquals(
                        SyntaxHighlighter.highlight(text, lang),
                        cache.highlight(text, lang),
                        "stress lang=$lang seed=$seed step=$step textLen=${text.length}",
                    )
                }
            }
        }
    }

    @Test
    fun edgeCases() {
        val cache = SyntaxHighlighter.IncrementalHighlightCache()

        assertEquals(
            SyntaxHighlighter.highlight("", SyntaxLanguage.KOTLIN),
            cache.highlight("", SyntaxLanguage.KOTLIN),
            "empty document",
        )

        val src = "fun main() {\n    val x = 1\n    // c\n}\n"
        val first = cache.highlight(src, SyntaxLanguage.KOTLIN)
        assertEquals(SyntaxHighlighter.highlight(src, SyntaxLanguage.KOTLIN), first, "first real call")

        // Repeated identical call must return the same result without corrupting cached state.
        assertEquals(first, cache.highlight(src, SyntaxLanguage.KOTLIN), "repeated identical call")

        // Switching language on the same cache instance must not reuse stale segments.
        val jsonSrc = "{\"a\": 1, \"b\": [true, false, null]}"
        assertEquals(
            SyntaxHighlighter.highlight(jsonSrc, SyntaxLanguage.JSON),
            cache.highlight(jsonSrc, SyntaxLanguage.JSON),
            "language switch to JSON",
        )

        // Non-incremental-eligible languages (Markdown here) must delegate straight through.
        val mdSrc = "# Heading\nSome *text* and a [link](http://x)."
        assertEquals(
            SyntaxHighlighter.highlight(mdSrc, SyntaxLanguage.MARKDOWN),
            cache.highlight(mdSrc, SyntaxLanguage.MARKDOWN),
            "language switch to Markdown (non-eligible)",
        )

        // And switching back to an eligible language afterward must not reuse anything stale.
        assertEquals(
            SyntaxHighlighter.highlight(src, SyntaxLanguage.KOTLIN),
            cache.highlight(src, SyntaxLanguage.KOTLIN),
            "language switch back to Kotlin",
        )
    }

    private fun randomSource(rng: Random, lines: Int): String {
        val sb = StringBuilder()
        val words = listOf(
            "fun", "val", "var", "class", "object", "if", "else", "when", "return", "foo", "bar",
            "baz", "compute", "result", "list", "map", "Int", "String", "Boolean", "true", "false",
        )
        for (l in 0 until lines) {
            when (rng.nextInt(10)) {
                0 -> sb.append("    // a line comment ${rng.nextInt(1000)}")
                1 -> sb.append("    /* a block comment that ")
                2 -> sb.append("    end of block */ val x = 1")
                3 -> sb.append("    val s = \"\"\"a triple ${rng.nextInt(9)} quoted string\"\"\"")
                4 -> sb.append("    val t = \"a string with (brackets) [and] {stuff}\"")
                5 -> sb.append("    fun f${rng.nextInt(999)}(a: Int, b: String): Boolean {")
                6 -> sb.append("    }")
                7 -> sb.append("    if (x > 0) { doSomething(y, [z) }") // deliberately unbalanced sometimes
                else -> sb.append(
                    "    ${words.random(rng)} ${words.random(rng)}${rng.nextInt(100)} = " +
                        "${words.random(rng)}(${words.random(rng)})",
                )
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun applyRandomEdit(rng: Random, text: String): String {
        if (text.isEmpty()) return text
        val pos = rng.nextInt(text.length + 1)
        return when (rng.nextInt(5)) {
            0 -> { // insert a char
                val chars = "abcXYZ{}[]()\"'\n/* */ "
                text.substring(0, pos) + chars.random(rng) + text.substring(pos)
            }
            1 -> { // delete a char
                if (pos < text.length) text.substring(0, pos) + text.substring(pos + 1) else text
            }
            2 -> { // insert a multi-line chunk
                val chunk = "\n    // inserted\n    val z = \"\"\"nested\"\"\"\n"
                text.substring(0, pos) + chunk + text.substring(pos)
            }
            3 -> { // delete a chunk
                val delLen = rng.nextInt(20).coerceAtMost(text.length - pos)
                text.substring(0, pos) + text.substring(pos + delLen)
            }
            else -> { // replace a chunk
                val delLen = rng.nextInt(15).coerceAtMost(text.length - pos)
                val repl = listOf("/*", "*/", "\"\"\"", "{", "}", "x", "\n").random(rng)
                text.substring(0, pos) + repl + text.substring(pos + delLen)
            }
        }
    }
}
