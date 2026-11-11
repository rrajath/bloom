package com.rrajath.grove.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Attach simplify plan 4.1: what the attachment folder field accepts, and how it's stored. */
class AttachFolderTest {

    @Test
    fun `accepted values are trimmed and get exactly one trailing slash`() {
        assertEquals("data/", AttachFolder.normalize("data"))
        assertEquals("attach/", AttachFolder.normalize("  attach// "))
        assertEquals(".attach/", AttachFolder.normalize(".attach"))
        assertEquals("media/files/", AttachFolder.normalize("media//files"))
        assertEquals("media/files/", AttachFolder.normalize("media/files/"))
        assertEquals("my files/", AttachFolder.normalize("my files"))
    }

    @Test
    fun `absolute, climbing, home, backslash and empty values are rejected`() {
        val bad = listOf(
            "", "   ", "/", "//", "/attachments", "../attach", "a/../b", "a/..", "~/attach", "~",
            "a\\b", ".", "./attach", "a/ b", "a /b",
        )
        for (v in bad) {
            assertNotNull(v, AttachFolder.error(v))
            assertNull(v, AttachFolder.normalize(v))
        }
    }

    @Test
    fun `sanitize falls back to data`() {
        assertEquals("data/", AttachFolder.sanitize(null))
        assertEquals("data/", AttachFolder.sanitize("../x"))
        assertEquals("data/", AttachFolder.sanitize("/x"))
        assertEquals("x/", AttachFolder.sanitize("x"))
    }
}
