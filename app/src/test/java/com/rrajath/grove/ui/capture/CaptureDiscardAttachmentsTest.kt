package com.rrajath.grove.ui.capture

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
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
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("1 file added in this capture", captureAttachmentsDetail(res, 1, null, idAdded = false, tagAdded = null))
        assertEquals("2 files added in this capture, and the :ID: added to inbox.org", captureAttachmentsDetail(res, 2, "inbox.org", idAdded = true, tagAdded = null))
        assertEquals(
            "1 file added in this capture, and the :ID: and :ATTACH: tag added to Trip",
            captureAttachmentsDetail(res, 1, "Trip", idAdded = true, tagAdded = "ATTACH"),
        )
        assertEquals(
            "1 file added in this capture, and the :receipt: tag added to Trip",
            captureAttachmentsDetail(res, 1, "Trip", idAdded = false, tagAdded = "receipt"),
        )
    }

    @Test
    fun `checkbox detail while the file is still copying`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("The file still copying", captureAttachmentsDetail(res, 0, null, idAdded = false, tagAdded = null))
    }
}
