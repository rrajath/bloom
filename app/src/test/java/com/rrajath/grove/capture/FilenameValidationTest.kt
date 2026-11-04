package com.rrajath.grove.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class FilenameValidationTest {

    private fun error(name: String) = FilenameValidation.errorFor(name)

    @Test
    fun `plain org filename is valid`() {
        assertNull(error("inbox.org"))
        assertNull(error("reading-list.org"))
        assertNull(error("2025 journal.org"))
    }

    @Test
    fun `blank or whitespace-only is invalid`() {
        assertNotNull(error(""))
        assertNotNull(error("   "))
    }

    @Test
    fun `dot and dot-dot are invalid`() {
        assertNotNull(error("."))
        assertNotNull(error(".."))
    }

    @Test
    fun `just the extension with no stem is invalid`() {
        assertNotNull(error(".org"))
    }

    @Test
    fun `missing org extension is invalid`() {
        assertNotNull(error("inbox"))
        assertNotNull(error("inbox.txt"))
    }

    @Test
    fun `forward-slash subfolder paths are valid`() {
        assertNull(error("sub/inbox.org"))
        assertNull(error("a/b/c/deep.org"))
        assertNull(error("  projects/2025/notes.org  "))
    }

    @Test
    fun `backslash is still invalid`() {
        assertNotNull(error("sub\\inbox.org"))
    }

    @Test
    fun `malformed paths are invalid`() {
        assertNotNull(error("/inbox.org"))
        assertNotNull(error("sub/inbox.org/"))
        assertNotNull(error("sub//inbox.org"))
        assertNotNull(error("sub/../inbox.org"))
        assertNotNull(error("./inbox.org"))
        assertNotNull(error("sub/inbox"))
        assertNotNull(error("sub/.org"))
    }

    @Test
    fun `reserved filesystem characters are invalid`() {
        for (ch in listOf(':', '*', '?', '"', '<', '>', '|')) {
            assertNotNull("char '$ch' should be rejected", error("in${ch}box.org"))
        }
    }

    @Test
    fun `surrounding whitespace is trimmed before validation`() {
        assertNull(error("  inbox.org  "))
    }

    @Test
    fun `error messages are distinct per failure kind`() {
        assertEquals("Enter a filename", error(""))
        assertEquals("Filename must end in .org", error("inbox"))
    }

    // --- errorForNewNotebook: .org optional, but bad paths still rejected ---

    private fun newNb(name: String) = FilenameValidation.errorForNewNotebook(name)

    @Test
    fun `rename notebook rejects slashes and delegates the rest`() {
        val msg = "Names can't contain /. Use Move to change folders."
        assertEquals(msg, FilenameValidation.errorForRenameNotebook("Folder/image-test"))
        assertEquals(msg, FilenameValidation.errorForRenameNotebook("/x"))
        assertNotNull(FilenameValidation.errorForRenameNotebook("a\\b"))
        assertNull(FilenameValidation.errorForRenameNotebook("image-test"))
        assertNull(FilenameValidation.errorForRenameNotebook("image-test.org"))
        assertEquals("Enter a name", FilenameValidation.errorForRenameNotebook(" "))
    }

    @Test
    fun `new notebook name accepts a bare name and appends org implicitly`() {
        assertNull(newNb("Work"))
        assertNull(newNb("2025 journal"))
        assertNull(newNb("inbox.org"))
    }

    @Test
    fun `new notebook name accepts nested folder paths`() {
        assertNull(newNb("work/ideas"))
        assertNull(newNb("work/ideas.org"))
        assertNull(newNb("a/b/c/deep.org"))
        assertNull(newNb("  clients/2025/notes  "))
    }

    @Test
    fun `new notebook name rejects a trailing slash`() {
        assertEquals("Not a valid path", newNb("foo/"))
        assertEquals("Not a valid path", newNb("foo/bar/"))
    }

    @Test
    fun `new notebook name rejects blank, malformed paths and reserved chars`() {
        assertEquals("Enter a name", newNb(""))
        assertEquals("Enter a name", newNb("   "))
        assertEquals("Enter a name", newNb(".org"))
        assertNotNull(newNb("/inbox"))
        assertNotNull(newNb("sub//inbox"))
        assertNotNull(newNb("sub/../inbox"))
        assertNotNull(newNb("./inbox"))
        assertNotNull(newNb(".."))
        assertNotNull(newNb("in:box"))
        assertNotNull(newNb("sub\\inbox"))
    }

    // --- errorForDirectory: blank is the vault root, valid ---

    private fun dir(path: String) = FilenameValidation.errorForDirectory(path)

    @Test
    fun `blank directory means vault root and is valid`() {
        assertNull(dir(""))
        assertNull(dir("   "))
    }

    @Test
    fun `directory accepts a bare name and nested paths`() {
        assertNull(dir("notes"))
        assertNull(dir("notes/roam"))
        assertNull(dir("  clients/2025  "))
    }

    @Test
    fun `directory rejects a leading or trailing slash`() {
        assertEquals("Not a valid path", dir("/notes"))
        assertEquals("Not a valid path", dir("notes/"))
    }

    @Test
    fun `directory rejects malformed paths and reserved chars`() {
        assertNotNull(dir("sub//inbox"))
        assertNotNull(dir("sub/../inbox"))
        assertNotNull(dir("./inbox"))
        assertNotNull(dir(".."))
        assertNotNull(dir("in:box"))
        assertNotNull(dir("sub\\inbox"))
    }
}
