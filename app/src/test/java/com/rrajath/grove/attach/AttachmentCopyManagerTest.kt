package com.rrajath.grove.attach

import com.rrajath.grove.vault.JvmFileStore
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentCopyManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val store by lazy { JvmFileStore(tmp.root, skipAttachShards = true) }
    private val fallbacks = mutableListOf<AttachDone>()
    private val notes = mutableListOf<String>()
    private var filesAdded = 0

    private val manager by lazy {
        AttachmentCopyManager(
            scope = testScope,
            io = dispatcher,
            store = { store },
            opener = { uri -> if (uri == "missing") null else ByteArrayInputStream(ByteArray(4)) },
            fallback = { done -> fallbacks += done; "fallback" },
            notify = { notes += it },
            onFilesAdded = { filesAdded++ },
        )
    }

    private fun request(vararg names: String, kind: AttachKind = AttachKind.Files, uri: String = "u") = AttachRequest(
        orgFile = "travel.org",
        owner = AttachOwner.Heading(2, listOf("Kyoto", "Lunch"), "8B41D2F0", null),
        newId = null,
        dir = "data/8B/41D2F0",
        kind = kind,
        sources = names.map { AttachSource(uri, it, 4) },
        ownerLabel = "Lunch",
    )

    @Test
    fun `a registered editor receives the finished attach`() = testScope.runTest {
        val got = mutableListOf<AttachDone>()
        manager.register("travel.org") { got += it }
        assertTrue(manager.start(request("a.pdf")))
        advanceUntilIdle()
        assertEquals(ImportResult.Success(listOf("a.pdf"), emptySet()), got.single().result)
        assertTrue(fallbacks.isEmpty())
        assertNull(manager.progress.value)
    }

    @Test
    fun `with no editor listening the file fallback writes the links and its toast is shown`() = testScope.runTest {
        val handle = manager.register("travel.org") { error("closed editor must not be called") }
        handle.close()
        manager.start(request("a.pdf"))
        advanceUntilIdle()
        assertEquals(1, fallbacks.size)
        assertEquals(listOf("fallback"), notes)
        assertEquals(1, filesAdded)
    }

    @Test
    fun `a failure with no editor shows the failure toast and skips the fallback`() = testScope.runTest {
        manager.start(request("a.pdf", uri = "missing"))
        advanceUntilIdle()
        assertTrue(fallbacks.isEmpty())
        assertEquals(listOf("Couldn't attach a.pdf: file unavailable"), notes)
        assertEquals(0, filesAdded)
    }

    @Test
    fun `only one copy runs at a time`() = testScope.runTest {
        assertTrue(manager.start(request("a.pdf")))
        assertFalse(manager.start(request("b.pdf")))
        advanceUntilIdle()
        assertTrue(manager.start(request("b.pdf")))
    }

    @Test
    fun `cancel delivers nothing and leaves no files`() = testScope.runTest {
        val got = mutableListOf<AttachDone>()
        manager.register("travel.org") { got += it }
        manager.start(request("a.pdf"))
        manager.cancel()
        advanceUntilIdle()
        assertTrue(got.isEmpty())
        assertFalse(File(tmp.root, "data").exists())
    }

    @Test
    fun `success toasts follow 1q`() {
        val one = ImportResult.Success(listOf("a.pdf"), emptySet())
        assertEquals("Attached to Lunch", successMessage(request("a.pdf"), one))
        assertEquals(
            "Attached to this file",
            successMessage(request("a.pdf").copy(ownerLabel = ownerToastLabel(AttachOwner.File(null, null))), one),
        )
        assertEquals(
            "Saved as photo-1.jpg (name already in use)",
            successMessage(request("photo.jpg"), ImportResult.Success(listOf("photo-1.jpg"), setOf("photo-1.jpg"))),
        )
        val three = ImportResult.Success(listOf("a.jpg", "b-1.jpg", "c.jpg"), setOf("b-1.jpg"))
        assertEquals("3 photos attached to Lunch", successMessage(request("a", "b", "c", kind = AttachKind.Photos), three))
        assertEquals("3 files attached to Lunch", successMessage(request("a", "b", "c"), three))
        assertEquals(
            "Heading changed. Attached to Lunch",
            successMessage(request("a.pdf").copy(ownerChanged = true), one),
        )
    }

    @Test
    fun `file fallback appends links at the end of the owner's own body, before children`() {
        val text = """
            * Kyoto
            ** Lunch
            Tiny soba place.

            *** Dessert
            Matcha.
        """.trimIndent()
        // The editor buffer was the Lunch subtree, so the title path is just ["Lunch"].
        val owner = AttachOwner.Heading(0, listOf("Lunch"), null, null)
        val edit = appendAttachmentLinksToFile(text, owner, "NEWID", listOf("a.jpg", "b.pdf"))!!
        assertEquals(
            """
            * Kyoto
            ** Lunch
            :PROPERTIES:
            :ID: NEWID
            :END:
            Tiny soba place.
            [[attachment:a.jpg]]
            [[attachment:b.pdf]]

            *** Dessert
            Matcha.
            """.trimIndent(),
            edit.text,
        )
    }

    @Test
    fun `file fallback finds the owner by ID and doesn't add a second one`() {
        val text = "* A\n:PROPERTIES:\n:ID: X1\n:END:\nbody\n* B\n"
        val owner = AttachOwner.Heading(9, listOf("renamed"), "X1", null)
        val edit = appendAttachmentLinksToFile(text, owner, "IGNORED", listOf("a.jpg"))!!
        assertEquals("* A\n:PROPERTIES:\n:ID: X1\n:END:\nbody\n[[attachment:a.jpg]]\n* B\n", edit.text)
    }

    @Test
    fun `file fallback returns null when the heading is gone`() {
        val owner = AttachOwner.Heading(0, listOf("Gone"), null, null)
        assertNull(appendAttachmentLinksToFile("* Other\n", owner, "N", listOf("a.jpg")))
    }

    @Test
    fun `buffer insert uses the cursor when it is inside the owner's section`() {
        val text = "* Lunch\n:PROPERTIES:\n:ID: X1\n:END:\nsoba\nmore\n** Child\n"
        val owner = AttachOwner.Heading(0, listOf("Lunch"), "X1", null)
        val cursor = text.indexOf("soba") + 4
        val edit = insertAttachmentLinksInBuffer(text, cursor, owner, null, listOf("a.jpg"))!!
        assertEquals("* Lunch\n:PROPERTIES:\n:ID: X1\n:END:\nsoba\n[[attachment:a.jpg]]\nmore\n** Child\n", edit.text)
    }

    @Test
    fun `buffer insert moves to the owner's body end when the cursor wandered elsewhere`() {
        val text = "* Lunch\n:PROPERTIES:\n:ID: X1\n:END:\nsoba\n** Child\nkid text\n"
        val owner = AttachOwner.Heading(0, listOf("Lunch"), "X1", null)
        val cursor = text.indexOf("kid text")
        val edit = insertAttachmentLinksInBuffer(text, cursor, owner, null, listOf("a.jpg"))!!
        assertEquals("* Lunch\n:PROPERTIES:\n:ID: X1\n:END:\nsoba\n[[attachment:a.jpg]]\n** Child\nkid text\n", edit.text)
        assertEquals(edit.text.indexOf("[[attachment:a.jpg]]") + "[[attachment:a.jpg]]".length, edit.cursor)
    }

    @Test
    fun `buffer insert adds the new ID to a file owner above the title`() {
        val text = "#+TITLE: Travel\nBookings.\n* Kyoto\n"
        val edit = insertAttachmentLinksInBuffer(text, text.indexOf("Bookings.") + 9, AttachOwner.File(null, null), "F1", listOf("a.pdf"))!!
        assertTrue(edit.text, edit.text.startsWith(":PROPERTIES:\n:ID:"))
        assertTrue(edit.text, edit.text.contains("Bookings.\n[[attachment:a.pdf]]\n* Kyoto"))
        assertEquals(0..2, edit.idDrawerLines)
    }
}
