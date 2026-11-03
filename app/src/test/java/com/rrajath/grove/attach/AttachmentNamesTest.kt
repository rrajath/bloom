package com.rrajath.grove.attach

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentNamesTest {

    @Test fun `sanitizer drops brackets slashes and control chars`() {
        assertEquals("a b.pdf", sanitizeAttachmentName("[a] b.pdf"))
        assertEquals("dirfile.txt", sanitizeAttachmentName("dir/file.txt"))
        assertEquals("ab.png", sanitizeAttachmentName("a\\b.png"))
        assertEquals("tab.jpg", sanitizeAttachmentName("ta\tb\u0000.jpg"))
    }

    @Test fun `sanitizer keeps spaces and unicode and trims`() {
        assertEquals("京都 旅行.jpg", sanitizeAttachmentName("  京都 旅行.jpg "))
    }

    @Test fun `empty or dot-only names become attachment`() {
        assertEquals("attachment", sanitizeAttachmentName(""))
        assertEquals("attachment", sanitizeAttachmentName("[]/"))
        assertEquals("attachment", sanitizeAttachmentName(".."))
    }

    @Test fun `unique name returns wanted when free`() {
        assertEquals("a.pdf", uniqueAttachmentName(listOf("b.pdf"), "a.pdf"))
    }

    @Test fun `unique name counts up case-insensitively`() {
        assertEquals("receipt-1.pdf", uniqueAttachmentName(listOf("Receipt.PDF"), "receipt.pdf"))
        assertEquals("receipt-3.pdf", uniqueAttachmentName(listOf("receipt.pdf", "receipt-1.pdf", "RECEIPT-2.pdf"), "receipt.pdf"))
    }

    @Test fun `unique name without extension and with multiple dots`() {
        assertEquals("README-1", uniqueAttachmentName(listOf("readme"), "README"))
        assertEquals("a.tar-1.gz", uniqueAttachmentName(listOf("a.tar.gz"), "a.tar.gz"))
        assertEquals(".env-1", uniqueAttachmentName(listOf(".env"), ".env"))
    }
}
