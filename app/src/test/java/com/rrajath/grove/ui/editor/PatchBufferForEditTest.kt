package com.rrajath.grove.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatchBufferForEditTest {

    private val old = listOf("* A", "body", "* B", "** C", "c", "* D", "")

    private fun withInsert(file: List<String>, after: Int, vararg lines: String) =
        file.take(after + 1) + lines.toList() + file.drop(after + 1)

    @Test
    fun `an insertion inside the buffer grows it`() {
        val new = withInsert(old, 3, ":PROPERTIES:", ":ID: X", ":END:")
        val patch = patchBufferForEdit("* B\n** C\nc\n", old, new, listOf(3 to 3))!!
        assertEquals("* B\n** C\n:PROPERTIES:\n:ID: X\n:END:\nc\n", patch.buffer)
        assertEquals(2, patch.start)
        assertEquals(6, patch.count)
    }

    @Test
    fun `an insertion after the buffer's last heading line belongs to it`() {
        val new = withInsert(old, 3, ":PROPERTIES:", ":ID: X", ":END:")
        val patch = patchBufferForEdit("* B\n** C", old, new, listOf(3 to 3))!!
        assertEquals("* B\n** C\n:PROPERTIES:\n:ID: X\n:END:", patch.buffer)
    }

    @Test
    fun `an insertion before the buffer shifts it`() {
        val new = withInsert(old, 0, ":PROPERTIES:", ":ID: X", ":END:")
        val patch = patchBufferForEdit("* D\n", old, new, listOf(0 to 3))!!
        assertEquals("* D\n", patch.buffer)
        assertEquals(8, patch.start)
        assertEquals(1, patch.count)
    }

    @Test
    fun `in-place changes are picked up`() {
        val new = old.toMutableList().also { it[4] = "c2" }
        assertEquals("** C\nc2", patchBufferForEdit("** C\nc", old, new, emptyList())!!.buffer)
    }

    @Test
    fun `a buffer that isn't in the file can't be patched`() {
        assertNull(patchBufferForEdit("* Z\n", old, old, emptyList()))
    }
}
