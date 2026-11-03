package com.rrajath.grove.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentLinkTest {

    @Test fun `attachment link without description to an image is an inline image`() {
        val a = AttachmentLink.classify("attachment:IMG_20261102.jpg", null)!!
        assertEquals(AttachmentBase.ATTACH_DIR, a.base)
        assertEquals("IMG_20261102.jpg", a.path)
        assertTrue(a.isImage)
        assertEquals(AttachmentCategory.IMAGE, a.category)
    }

    @Test fun `image extensions are case-insensitive`() {
        for (ext in listOf("JPG", "Jpeg", "png", "GIF", "webp", "bmp", "HEIC", "heif")) {
            assertTrue(ext, AttachmentLink.classify("attachment:x.$ext", null)!!.isImage)
        }
    }

    @Test fun `description turns an image into a chip`() {
        val a = AttachmentLink.classify("attachment:x.png", "the diagram")!!
        assertFalse(a.isImage)
        assertEquals("the diagram", a.label)
    }

    @Test fun `blank description counts as none`() {
        assertTrue(AttachmentLink.classify("attachment:x.png", "  ")!!.isImage)
    }

    @Test fun `svg and other files are chips labelled with the file name`() {
        val svg = AttachmentLink.classify("attachment:logo.svg", null)!!
        assertFalse(svg.isImage)
        assertEquals("logo.svg", svg.label)
        val pdf = AttachmentLink.classify("attachment:receipt.pdf", null)!!
        assertEquals(AttachmentCategory.DOCUMENT, pdf.category)
        assertEquals("PDF", pdf.badge)
    }

    @Test fun `file links and bare relative paths are relative to the org dir`() {
        val f = AttachmentLink.classify("file:../Photos/kyoto.jpg", null)!!
        assertEquals(AttachmentBase.ORG_DIR, f.base)
        assertEquals("../Photos/kyoto.jpg", f.path)
        assertEquals(AttachmentBase.ORG_DIR, AttachmentLink.classify("./img/a.png", null)!!.base)
        assertEquals(AttachmentBase.ORG_DIR, AttachmentLink.classify("img/a.png", null)!!.base)
        assertEquals(AttachmentBase.ORG_DIR, AttachmentLink.classify("file+sys:doc.pdf", null)!!.base)
        assertEquals("doc.pdf", AttachmentLink.classify("file:doc.pdf::5", null)!!.path)
    }

    @Test fun `org targets external links and fuzzy text are not attachments`() {
        assertNull(AttachmentLink.classify("file:notes.org", null))
        assertNull(AttachmentLink.classify("./other.ORG", null))
        assertNull(AttachmentLink.classify("file:notes.org::*Heading", null))
        assertNull(AttachmentLink.classify("https://example.com/a.png", null))
        assertNull(AttachmentLink.classify("mailto:a@b.c", null))
        assertNull(AttachmentLink.classify("id:8B41", null))
        assertNull(AttachmentLink.classify("#custom", null))
        assertNull(AttachmentLink.classify("*Heading", null))
        assertNull(AttachmentLink.classify("photo.jpg", null))
        assertNull(AttachmentLink.classify("attachment:", null))
        assertNull(AttachmentLink.classify("file:folder/", null))
    }

    @Test fun `subpaths inside the attach dir keep the leaf as file name`() {
        val a = AttachmentLink.classify("attachment:scans/page 1.pdf", null)!!
        assertEquals("page 1.pdf", a.fileName)
        assertEquals("page 1.pdf", a.label)
    }

    @Test fun `badge is the uppercased extension capped at four chars or FILE`() {
        assertEquals("JPEG", badgeFor("a.jpeg"))
        assertEquals("DOCX", badgeFor("a.docx"))
        assertEquals("FILE", badgeFor("Makefile"))
        assertEquals("FILE", badgeFor(".hidden"))
        assertEquals("FILE", badgeFor("trailing."))
        assertEquals("MARK", badgeFor("x.markdown"))
    }

    @Test fun `category table`() {
        assertEquals(AttachmentCategory.AUDIO, categoryOf("M4A"))
        assertEquals(AttachmentCategory.VIDEO, categoryOf("mov"))
        assertEquals(AttachmentCategory.ARCHIVE, categoryOf("zip"))
        assertEquals(AttachmentCategory.IMAGE, categoryOf("tiff"))
        assertEquals(AttachmentCategory.DOCUMENT, categoryOf("epub"))
        assertEquals(AttachmentCategory.GENERIC, categoryOf("bin"))
        assertEquals(AttachmentCategory.GENERIC, categoryOf(""))
    }
}
