package com.rrajath.grove.ui.editor

import androidx.compose.ui.text.TextRange
import com.rrajath.grove.ui.editor.EditRegion.HEADING_PROPERTIES
import com.rrajath.grove.ui.editor.EditRegion.WHOLE_FILE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LineNumberGutterTest {

    @Test
    fun `line starts cover every line, including an empty last one`() {
        assertArrayEquals(intArrayOf(0), lineStartOffsets(""))
        assertArrayEquals(intArrayOf(0, 2, 3), lineStartOffsets("a\n\nbc"))
        assertArrayEquals(intArrayOf(0, 2), lineStartOffsets("a\n"))
    }

    @Test
    fun `an offset maps to the line holding it`() {
        val starts = lineStartOffsets("ab\ncd\nef")
        assertEquals(0, lineIndexForOffset(starts, 0))
        assertEquals(0, lineIndexForOffset(starts, 2)) // the newline belongs to its line
        assertEquals(1, lineIndexForOffset(starts, 3))
        assertEquals(2, lineIndexForOffset(starts, 8))
    }

    @Test
    fun `a line range selects whole lines with the trailing newline, up to the text end`() {
        val text = "ab\ncd\nef"
        val starts = lineStartOffsets(text)
        assertEquals(TextRange(3, 6), lineRangeSelection(1, 1, starts, text.length))
        assertEquals(TextRange(0, 6), lineRangeSelection(0, 1, starts, text.length))
        assertEquals(TextRange(6, 8), lineRangeSelection(2, 2, starts, text.length))
    }

    @Test
    fun `the buffer's first file line follows what the editor loaded`() {
        assertEquals(0, EditorUiState(region = WHOLE_FILE, regionRange = 0..20).bufferFirstFileLine)
        assertEquals(0, EditorUiState(region = WHOLE_FILE, regionRange = IntRange.EMPTY).bufferFirstFileLine)
        assertEquals(7, EditorUiState(region = HEADING_PROPERTIES, lineIndex = 5, regionRange = 7..9).bufferFirstFileLine)
        assertEquals(12, EditorUiState(lineIndex = 12).bufferFirstFileLine)
        // After a save the owned span is authoritative.
        assertEquals(14, EditorUiState(lineIndex = 12, ownedSpan = 14..20).bufferFirstFileLine)
    }
}
