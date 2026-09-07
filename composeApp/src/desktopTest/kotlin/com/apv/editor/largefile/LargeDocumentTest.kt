package com.apv.editor.largefile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LargeDocumentTest {

    private fun tempFile(content: String): File {
        val f = File.createTempFile("large-doc-test", ".txt")
        f.deleteOnExit()
        f.writeBytes(content.toByteArray(Charsets.UTF_8))
        return f
    }

    // --- line semantics match String.split("\n") exactly ---

    @Test
    fun emptyFileIsOneEmptyLine() {
        val doc = LargeDocument.open(tempFile(""))
        assertEquals(1, doc.lineCount)
        assertEquals("", doc.getLine(0))
        doc.close()
    }

    @Test
    fun noTrailingNewlineGivesExactLineCount() {
        val doc = LargeDocument.open(tempFile("a\nb\nc"))
        assertEquals(3, doc.lineCount)
        assertEquals(listOf("a", "b", "c"), (0 until doc.lineCount).map { doc.getLine(it) })
        doc.close()
    }

    @Test
    fun trailingNewlineAddsOneEmptyLine() {
        val content = "a\nb\nc\n"
        val doc = LargeDocument.open(tempFile(content))
        assertEquals(content.split("\n").size, doc.lineCount)
        assertEquals(content.split("\n"), (0 until doc.lineCount).map { doc.getLine(it) })
        doc.close()
    }

    // --- in-place edits ---

    @Test
    fun setLineReplacesContentAndMarksDirty() {
        val doc = LargeDocument.open(tempFile("a\nb\nc"))
        assertFalse(doc.isDirty)
        doc.setLine(1, "B")
        assertTrue(doc.isDirty)
        assertEquals(listOf("a", "B", "c"), (0 until doc.lineCount).map { doc.getLine(it) })
        doc.close()
    }

    @Test
    fun repeatedEditsToSameLineStayCorrect() {
        val doc = LargeDocument.open(tempFile("hello\nworld"))
        doc.setLine(0, "h")
        doc.setLine(0, "he")
        doc.setLine(0, "hel")
        assertEquals("hel", doc.getLine(0))
        assertEquals("world", doc.getLine(1))
        assertEquals(2, doc.lineCount)
        doc.close()
    }

    // --- splitting (Enter) and merging (Backspace) via replaceRange, mirroring LargeEditorPane ---

    @Test
    fun replaceRangeWithNewlineSplitsALine() {
        val doc = LargeDocument.open(tempFile("abcd\nx"))
        val caret = doc.replaceRange(LineCol(0, 2), LineCol(0, 2), "\n")
        assertEquals(LineCol(1, 0), caret)
        assertEquals(listOf("ab", "cd", "x"), (0 until doc.lineCount).map { doc.getLine(it) })
        doc.close()
    }

    @Test
    fun replaceRangeAcrossLineBoundaryMergesLines() {
        val doc = LargeDocument.open(tempFile("ab\ncd\nef"))
        // Backspace at column 0 of line 1: merge line 1 into line 0.
        val caret = doc.replaceRange(LineCol(0, 2), LineCol(1, 0), "")
        assertEquals(LineCol(0, 2), caret)
        assertEquals(listOf("abcd", "ef"), (0 until doc.lineCount).map { doc.getLine(it) })
        doc.close()
    }

    @Test
    fun multiLinePasteExpandsLineCount() {
        val doc = LargeDocument.open(tempFile("start\nend"))
        val caret = doc.replaceRange(LineCol(0, 5), LineCol(0, 5), "\none\ntwo\nthree")
        assertEquals(5, doc.lineCount)
        assertEquals(listOf("start", "one", "two", "three", "end"), (0 until doc.lineCount).map { doc.getLine(it) })
        assertEquals(LineCol(3, "three".length), caret)
        doc.close()
    }

    // --- writeTo round-trips, byte for byte on untouched content ---

    @Test
    fun writeToUnmodifiedDocumentReproducesOriginalBytes() {
        val content = "line one\nline two\nline three"
        val src = tempFile(content)
        val doc = LargeDocument.open(src)
        val out = File.createTempFile("large-doc-test-out", ".txt")
        out.deleteOnExit()
        doc.writeTo(out)
        assertEquals(content, out.readText())
        assertFalse(doc.isDirty)
        doc.close()
    }

    @Test
    fun writeToPreservesTrailingNewlineConvention() {
        val content = "line one\nline two\n"
        val src = tempFile(content)
        val doc = LargeDocument.open(src)
        val out = File.createTempFile("large-doc-test-out", ".txt")
        out.deleteOnExit()
        doc.writeTo(out)
        assertEquals(content, out.readText())
        doc.close()
    }

    @Test
    fun writeToAfterEditsReflectsChangesAndKeepsUntouchedLinesExact() {
        val content = "keep me\nchange me\nkeep me too"
        val src = tempFile(content)
        val doc = LargeDocument.open(src)
        doc.setLine(1, "changed")
        val out = File.createTempFile("large-doc-test-out", ".txt")
        out.deleteOnExit()
        doc.writeTo(out)
        assertEquals("keep me\nchanged\nkeep me too", out.readText())
        doc.close()
    }

    @Test
    fun writeToThenFurtherEditsWorkOnTheNewFile() {
        val src = tempFile("a\nb\nc")
        val doc = LargeDocument.open(src)
        doc.setLine(0, "A")
        val out1 = File.createTempFile("large-doc-test-out1", ".txt")
        out1.deleteOnExit()
        doc.writeTo(out1)
        assertEquals("A\nb\nc", out1.readText())

        // The document should now be usable against the freshly written file — further edits and
        // a second save must still work correctly after the internal "collapse back to Original" step.
        doc.setLine(2, "C")
        val out2 = File.createTempFile("large-doc-test-out2", ".txt")
        out2.deleteOnExit()
        doc.writeTo(out2)
        assertEquals("A\nb\nC", out2.readText())
        doc.close()
    }

    @Test
    fun manySmallEditsAcrossTheDocumentStayCorrect() {
        val lines = (0 until 200).map { "line-$it" }
        val src = tempFile(lines.joinToString("\n"))
        val doc = LargeDocument.open(src)
        assertEquals(200, doc.lineCount)

        for (i in 0 until 200 step 7) {
            doc.setLine(i, "EDITED-$i")
        }
        for (i in 0 until 200) {
            val expected = if (i % 7 == 0) "EDITED-$i" else "line-$i"
            assertEquals(expected, doc.getLine(i), "mismatch at line $i")
        }

        val out = File.createTempFile("large-doc-test-out", ".txt")
        out.deleteOnExit()
        doc.writeTo(out)
        val writtenLines = out.readText().split("\n")
        assertEquals(200, writtenLines.size)
        for (i in 0 until 200) {
            val expected = if (i % 7 == 0) "EDITED-$i" else "line-$i"
            assertEquals(expected, writtenLines[i], "mismatch on disk at line $i")
        }
        doc.close()
    }
}
