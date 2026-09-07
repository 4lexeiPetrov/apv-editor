package com.apv.editor.state

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the bug where a file with a modest raw line count but very long lines
 * (a data export with wide rows, a minified bundle) still crashed the plain editor: it soft-wraps
 * long lines to the window's width, so total *visual* row count — not raw line count — is what
 * actually determines whether Compose's Constraints ceiling is crossed. See
 * [LargeFileGuard.estimatedWrappedRows]/[LargeFileGuard.charsPerWrappedRow] (private) and the class
 * doc comment.
 */
class LargeFileGuardTest {

    private val fontSize = 13

    private fun tempFile(content: String): File {
        val f = File.createTempFile("large-file-guard-test", ".txt")
        f.deleteOnExit()
        f.writeText(content)
        return f
    }

    @Test
    fun manyShortLinesWellUnderTheThresholdStayNormal() {
        val lines = (0 until 500).map { "short line $it" }
        val file = tempFile(lines.joinToString("\n"))
        assertEquals(LargeFileGuard.FileSizeClass.NORMAL, LargeFileGuard.classify(file, fontSize))
        assertFalse(LargeFileGuard.isOversized(file, fontSize))
    }

    @Test
    fun aFewVeryLongLinesAreFlaggedLargeDespiteALowRawLineCount() {
        // Mirrors the reported crash: a CSV-like export with only a few hundred rows, but each row
        // wide enough (long URLs, base64-ish tokens, many columns) that word-wrap in the plain
        // editor still produces enough visual rows to cross the Constraints ceiling.
        val wideRow = "x".repeat(2000)
        val lines = (0 until 400).map { wideRow }
        val file = tempFile(lines.joinToString("\n"))
        assertEquals(LargeFileGuard.FileSizeClass.LARGE, LargeFileGuard.classify(file, fontSize))
        assertTrue(LargeFileGuard.isOversized(file, fontSize))
    }

    @Test
    fun aSingleGiantLineWithNoNewlinesIsFlaggedLarge() {
        val file = tempFile("y".repeat(500_000))
        assertEquals(LargeFileGuard.FileSizeClass.LARGE, LargeFileGuard.classify(file, fontSize))
    }

    @Test
    fun rawLineCountAloneDoesNotAccountForWrapping() {
        // Sanity check that the scenario above genuinely depends on the wrap-aware check: the same
        // number of raw lines, all short, must NOT be flagged.
        val shortLines = (0 until 400).map { "short" }
        val file = tempFile(shortLines.joinToString("\n"))
        assertEquals(LargeFileGuard.FileSizeClass.NORMAL, LargeFileGuard.classify(file, fontSize))
    }

    @Test
    fun isOversizedOnTextMatchesTheFileBasedCheck() {
        val wideRow = "z".repeat(2000)
        val text = (0 until 400).joinToString("\n") { wideRow }
        assertTrue(LargeFileGuard.isOversized(text, fontSize))
        assertFalse(LargeFileGuard.isOversized("short\ntext\nhere", fontSize))
    }

    @Test
    fun missingFileClassifiesAsNormalSoTheNormalOpenFlowSurfacesTheRealError() {
        val missing = File("/no/such/path/${System.nanoTime()}.txt")
        assertEquals(LargeFileGuard.FileSizeClass.NORMAL, LargeFileGuard.classify(missing, fontSize))
        assertFalse(LargeFileGuard.isOversized(missing, fontSize))
    }

    @Test
    fun emptyFileIsNormal() {
        val file = tempFile("")
        assertEquals(LargeFileGuard.FileSizeClass.NORMAL, LargeFileGuard.classify(file, fontSize))
    }

    @Test
    fun maxSafeLinesShrinksAsFontSizeGrows() {
        assertTrue(LargeFileGuard.maxSafeLines(24) < LargeFileGuard.maxSafeLines(12))
    }
}
