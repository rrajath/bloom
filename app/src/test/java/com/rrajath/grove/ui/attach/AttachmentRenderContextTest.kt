package com.rrajath.grove.ui.attach

import com.rrajath.grove.attach.AttachConfig
import com.rrajath.grove.attach.AttachmentLink
import com.rrajath.grove.attach.AttachmentStatus
import com.rrajath.grove.attach.AttachmentStatusCache
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.settings.AttachImageWidth
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The read-mode context reads its document and callbacks at use time, so one instance survives reparses. */
class AttachmentRenderContextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `lookups use the document current at call time`() = runTest {
        File(tmp.root, "data/ab/cdef/a.jpg").apply { parentFile?.mkdirs() }.writeText("x")
        var doc: OrgDocument? = OrgParser.parse("* Lunch\n[[attachment:a.jpg]]\n")
        var toasts = 0
        var toast: (String) -> Unit = { }
        val ctx = AttachmentRenderContext(
            orgFile = "n.org",
            docSource = { doc },
            store = JvmFileStore(tmp.root, skipAttachShards = true),
            cache = AttachmentStatusCache(),
            config = AttachConfig.Default,
            widthMode = AttachImageWidth.FIT,
            fixedWidthPx = AttachImageWidth.DEFAULT_PX,
            preferLinesSource = { null },
            openTargetSource = { {} },
            openImageSource = { null },
            toastSource = { toast },
            io = Dispatchers.Unconfined,
        )
        val link = AttachmentLink.classify("attachment:a.jpg", null)!!
        // No :ID: yet: nothing to resolve against.
        assertEquals(AttachmentStatus.Missing, ctx.status(link, "attachment:a.jpg", docLine = 1))

        // The note is saved with an ID; the same context sees the new parse.
        val updated = OrgParser.parse("* Lunch\n:PROPERTIES:\n:ID: abcdef\n:END:\n[[attachment:a.jpg]]\n")
        doc = updated
        assertSame(updated, ctx.doc)
        assertTrue(ctx.status(link, "attachment:a.jpg", docLine = 4) is AttachmentStatus.Present)

        toast = { toasts++ }
        ctx.toast("x")
        assertEquals(1, toasts)
    }
}
