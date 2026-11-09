package com.rrajath.grove.ui.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureDiscardAttachmentsTest {

    @Test
    fun `removes a drawer that held only the added ID`() {
        assertEquals("#+title: Inbox\n* A", withoutFileId(":PROPERTIES:\n:ID: F1\n:END:\n#+title: Inbox\n* A", "F1"))
    }

    @Test
    fun `keeps the drawer when other properties are left`() {
        assertEquals(
            ":PROPERTIES:\n:CATEGORY: work\n:END:\n* A",
            withoutFileId(":PROPERTIES:\n:ID: F1\n:CATEGORY: work\n:END:\n* A", "F1"),
        )
    }

    @Test
    fun `leaves a different ID alone`() {
        assertNull(withoutFileId(":PROPERTIES:\n:ID: OTHER\n:END:\n* A", "F1"))
        assertNull(withoutFileId("* A", "F1"))
    }

    @Test
    fun `checkbox detail names the files and the added ID`() {
        assertEquals("1 file added in this capture", captureAttachmentsDetail(1, null))
        assertEquals("2 files added in this capture, and the :ID: added to inbox.org", captureAttachmentsDetail(2, "inbox.org"))
    }
}
