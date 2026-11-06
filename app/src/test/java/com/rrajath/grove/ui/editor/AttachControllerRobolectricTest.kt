package com.rrajath.grove.ui.editor

import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.R
import com.rrajath.grove.attach.AttachOwner
import com.rrajath.grove.attach.AttachScope
import com.rrajath.grove.attach.AttachmentCopyManager
import com.rrajath.grove.attach.ScopedAttachment
import com.rrajath.grove.attach.scopesFor
import com.rrajath.grove.data.IndexedAttachOwner
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives the editor-side attach flow through [rememberAttachController], the way
 * an activity result arrives on device: picker result -> owner re-found ->
 * copy -> links in the buffer. Regression: the result was handled inside a
 * LaunchedEffect keyed on the very state it cleared, so the work was cancelled
 * at its first suspension and nothing was ever copied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class)
class AttachControllerRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultDir by lazy { tmp.newFolder("vault") }
    private val store by lazy { JvmFileStore(vaultDir, skipAttachShards = true) }
    private val manager by lazy {
        AttachmentCopyManager(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            io = Dispatchers.IO,
            store = { store },
            opener = { uri -> File(Uri.parse(uri).path!!).inputStream() },
            fallback = { null },
            notify = {},
        )
    }

    /** Runs the paused main looper (the manager resumes there) until [done] or 5 s pass. */
    private fun settleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within 5s" }
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(10)
        }
    }

    private fun picked(name: String, text: String = "PDF bytes"): Uri =
        Uri.fromFile(File(tmp.newFolder(), name).apply { writeText(text) })

    @Test
    fun `a Files pick copies into the heading's data dir and links it at the cursor`() {
        val text = "* Lunch\nTiny soba place.\n"
        val textState = TextFieldState(text, TextRange(text.indexOf("place.") + 6))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(textState, "travel.org", bufferReady = true, manager) { store }
            }
        }
        composeRule.runOnIdle {
            controller.open()
            controller.pending = PendingAttach("Files", isFile = false, id = null, titlePath = listOf("Lunch"), lineIndex = 0, cursor = textState.selection.start)
            controller.onPickerResult(listOf(picked("receipt.pdf")))
        }
        settleUntil { textState.text.contains("[[attachment:receipt.pdf]]") }

        val doc = OrgParser.parse(textState.text.toString())
        val id = doc.headlines.single().properties["ID"]!!
        val copied = File(vaultDir, "data/${id.take(2)}/${id.drop(2)}/receipt.pdf")
        assertTrue("copied to $copied", copied.isFile)
        assertEquals("PDF bytes", copied.readText())
        assertTrue(textState.text.toString(), textState.text.contains("Tiny soba place.\n[[attachment:receipt.pdf]]"))
        assertEquals("Attached to Lunch", controller.toast?.message)
    }

    @Test
    fun `a result that arrives before the buffer is loaded waits for it`() {
        val textState = TextFieldState("* Lunch\n:PROPERTIES:\n:ID: AB12345\n:END:\nbody\n", TextRange(0))
        var ready by mutableStateOf(false)
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(textState, "travel.org", bufferReady = ready, manager) { store }
            }
        }
        composeRule.runOnIdle {
            controller.pending = PendingAttach("Photos", false, "AB12345", listOf("Lunch"), 0, 0)
            controller.onPickerResult(listOf(picked("a.jpg"), picked("b.jpg")))
        }
        composeRule.waitForIdle()
        assertFalse(File(vaultDir, "data").exists())

        ready = true
        settleUntil { textState.text.contains("[[attachment:b.jpg]]") }
        assertTrue(File(vaultDir, "data/AB/12345/a.jpg").isFile)
        assertTrue(textState.text.toString(), textState.text.contains(":END:\n[[attachment:a.jpg]]\n[[attachment:b.jpg]]\nbody"))
        assertEquals("2 photos attached to Lunch", controller.toast?.message)
    }

    // ---- Scopes, paging and cross-owner links ----

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun drawer(id: String) = ":PROPERTIES:\n:ID: $id\n:END:\n"

    /** `* A` (ID AA0001, cursor here) and `* B` (ID BB0002) in x.org. */
    private val twoHeadings = "* A\n" + drawer("AA0001") + "a body\n* B\n" + drawer("BB0002") + "b body\n"

    private fun put(dir: String, name: String, modified: Long = 1_000_000) {
        val f = File(vaultDir, "$dir/$name")
        f.parentFile!!.mkdirs()
        f.writeText(name)
        f.setLastModified(modified)
    }

    private fun controllerFor(
        text: String,
        cursor: Int,
        indexedOwners: suspend () -> List<IndexedAttachOwner> = { emptyList() },
        fileStore: FileStore = store,
    ): Pair<AttachController, TextFieldState> {
        val textState = TextFieldState(text, TextRange(cursor))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(
                    textState, "x.org", bufferReady = true, manager, indexedOwners = indexedOwners,
                ) { fileStore }
            }
        }
        return controller to textState
    }

    private fun AttachController.openAndLoad() {
        composeRule.runOnIdle { open() }
        settleUntil { sheet?.rows != null }
    }

    @Test
    fun `opens on this heading with three scopes`() {
        val (c, _) = controllerFor(twoHeadings, cursor = 2)
        c.openAndLoad()
        assertEquals(AttachScope.HEADING, c.sheet!!.scope)
        assertEquals(listOf(AttachScope.HEADING, AttachScope.FILE, AttachScope.VAULT), c.sheet!!.scopes)
    }

    @Test
    fun `file owner opens on this file with two scopes`() {
        val (c, _) = controllerFor("intro\n* A\n", cursor = 0)
        c.openAndLoad()
        assertEquals(AttachScope.FILE, c.sheet!!.scope)
        assertEquals(listOf(AttachScope.FILE, AttachScope.VAULT), c.sheet!!.scopes)
    }

    @Test
    fun `this file scope lists rows from other headings`() {
        put("data/AA/0001", "mine.jpg", 1_000_000)
        put("data/BB/0002", "theirs.pdf", 2_000_000)
        val (c, _) = controllerFor(twoHeadings, cursor = 2)
        c.openAndLoad()
        assertEquals(listOf("mine.jpg"), c.sheet!!.visibleRows.map { it.file.name.substringAfterLast('/') })

        composeRule.runOnIdle { c.selectScope(AttachScope.FILE) }
        settleUntil { c.sheet?.rows != null }
        assertEquals(listOf("theirs.pdf", "mine.jpg"), c.sheet!!.visibleRows.map { it.file.name.substringAfterLast('/') })
        assertEquals(listOf("B", "A"), c.sheet!!.visibleRows.map { it.source })
    }

    @Test
    fun `load more shows the next ten`() {
        repeat(23) { put("data/AA/0001", "f%02d.txt".format(it)) }
        val (c, _) = controllerFor(twoHeadings, cursor = 2)
        c.openAndLoad()
        assertEquals(10, c.sheet!!.visibleRows.size)
        assertTrue(c.sheet!!.hasMore)
        composeRule.runOnIdle { c.loadMore() }
        assertEquals(20, c.sheet!!.visibleRows.size)
        composeRule.runOnIdle { c.loadMore() }
        assertEquals(23, c.sheet!!.visibleRows.size)
        assertFalse(c.sheet!!.hasMore)

        composeRule.runOnIdle { c.selectScope(AttachScope.FILE) }
        settleUntil { c.sheet?.rows != null }
        assertEquals(10, c.sheet!!.visibleRows.size)
    }

    @Test
    fun `stale vault result does not replace the selected scope`() {
        put("data/AA/0001", "mine.jpg")
        val gate = CompletableDeferred<List<IndexedAttachOwner>>()
        val (c, _) = controllerFor(twoHeadings, cursor = 2, indexedOwners = { gate.await() })
        c.openAndLoad()
        composeRule.runOnIdle {
            c.selectScope(AttachScope.VAULT)
            c.selectScope(AttachScope.HEADING)
        }
        gate.complete(emptyList())
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); composeRule.waitForIdle(); Thread.sleep(20) }

        assertEquals(AttachScope.HEADING, c.sheet!!.scope)
        assertEquals(listOf("mine.jpg"), c.sheet!!.visibleRows.map { it.file.name.substringAfterLast('/') })
        composeRule.runOnIdle { c.selectScope(AttachScope.VAULT) }
        assertEquals("vault result was cached", 1, c.sheet!!.rows?.size)
    }

    @Test
    fun `closing the sheet mid-scan leaves it closed`() {
        val gate = CompletableDeferred<List<IndexedAttachOwner>>()
        val (c, _) = controllerFor(twoHeadings, cursor = 2, indexedOwners = { gate.await() })
        c.openAndLoad()
        composeRule.runOnIdle {
            c.selectScope(AttachScope.VAULT)
            c.close()
        }
        gate.complete(emptyList())
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); composeRule.waitForIdle(); Thread.sleep(20) }
        assertNull(c.sheet)
    }

    @Test
    fun `inserting another heading's file writes a relative file link and names the source`() {
        put("data/BB/0002", "photo.jpg")
        val (c, textState) = controllerFor(twoHeadings, cursor = twoHeadings.indexOf("a body") + 6)
        c.openAndLoad()
        composeRule.runOnIdle { c.selectScope(AttachScope.FILE) }
        settleUntil { c.sheet?.rows != null }
        composeRule.runOnIdle { c.insertExisting(c.sheet!!.visibleRows.single()) }

        assertTrue(textState.text.toString(), textState.text.contains("a body[[file:data/BB/0002/photo.jpg]]\n"))
        assertNull(c.sheet)
        assertEquals(context.getString(R.string.attach_linked_from, "photo.jpg", "B"), c.toast?.message)
    }

    @Test
    fun `same-named files insert their own dir`() {
        put("data/AA/0001", "photo.jpg", 1_000_000)
        put("data/BB/0002", "photo.jpg", 2_000_000)
        val (c, textState) = controllerFor(twoHeadings, cursor = twoHeadings.indexOf("a body") + 6)
        c.openAndLoad()
        composeRule.runOnIdle { c.selectScope(AttachScope.FILE) }
        settleUntil { c.sheet?.rows != null }
        val rows = c.sheet!!.visibleRows
        assertEquals(listOf("data/BB/0002", "data/AA/0001"), rows.map { it.dir })

        composeRule.runOnIdle { c.insertExisting(rows[0]) }
        assertTrue(textState.text.toString(), textState.text.contains("[[file:data/BB/0002/photo.jpg]]"))
        composeRule.runOnIdle { c.open() }
        settleUntil { c.sheet?.rows != null }
        composeRule.runOnIdle { c.insertExisting(rows[1]) }
        assertTrue(textState.text.toString(), textState.text.contains("[[attachment:photo.jpg]]"))
        assertEquals(context.getString(R.string.attach_linked, "photo.jpg"), c.toast?.message)
    }

    @Test
    fun `bracketed names are refused`() {
        put("data/BB/0002", "pic[1].jpg")
        val (c, textState) = controllerFor(twoHeadings, cursor = 2)
        c.openAndLoad()
        composeRule.runOnIdle { c.selectScope(AttachScope.FILE) }
        settleUntil { c.sheet?.rows != null }
        composeRule.runOnIdle { c.insertExisting(c.sheet!!.visibleRows.single()) }

        assertEquals(twoHeadings, textState.text.toString())
        assertNotNull("sheet stays open", c.sheet)
        assertEquals(context.getString(R.string.attach_link_bracket_refused), c.toast?.message)
    }

    @Test
    fun `a vault scan that finishes after a delete does not bring the file back`() {
        put("data/AA/0001", "gone.jpg")
        put("data/AA/0001", "kept.jpg")
        // Holds a listing after it has read the dir: the Vault scan sees the file, then waits.
        var hold: CompletableDeferred<Unit>? = null
        val slowStore = object : FileStore by store {
            override suspend fun listDirRaw(dir: String): List<FileEntry> {
                val listed = store.listDirRaw(dir)
                hold?.await()
                return listed
            }
        }
        val (c, _) = controllerFor(twoHeadings, cursor = 2, fileStore = slowStore)
        c.openAndLoad()
        val gate = CompletableDeferred<Unit>()
        hold = gate
        composeRule.runOnIdle { c.selectScope(AttachScope.VAULT) }
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); composeRule.waitForIdle(); Thread.sleep(20) }
        hold = null
        composeRule.runOnIdle {
            c.selectScope(AttachScope.HEADING)
            c.delete(c.sheet!!.visibleRows.first { it.file.name.endsWith("gone.jpg") }.file)
        }
        settleUntil { c.sheet?.rows?.size == 1 }
        gate.complete(Unit)
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); composeRule.waitForIdle(); Thread.sleep(20) }

        composeRule.runOnIdle { c.selectScope(AttachScope.VAULT) }
        settleUntil { c.sheet?.rows != null }
        assertEquals(listOf("kept.jpg"), c.sheet!!.visibleRows.map { it.file.name.substringAfterLast('/') })
    }

    @Test
    fun `switching back to a scope that is still loading does not scan it twice`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val gate = CompletableDeferred<List<IndexedAttachOwner>>()
        val (c, _) = controllerFor(twoHeadings, cursor = 2, indexedOwners = { calls.incrementAndGet(); gate.await() })
        c.openAndLoad()
        composeRule.runOnIdle {
            c.selectScope(AttachScope.VAULT)
            c.selectScope(AttachScope.HEADING)
            c.selectScope(AttachScope.VAULT)
        }
        gate.complete(emptyList())
        settleUntil { c.sheet?.rows != null }
        assertEquals(1, calls.get())
    }

    @Test
    fun `names with a double colon are refused`() {
        put("data/BB/0002", "a::b.pdf")
        val (c, textState) = controllerFor(twoHeadings, cursor = 2)
        c.openAndLoad()
        composeRule.runOnIdle { c.selectScope(AttachScope.FILE) }
        settleUntil { c.sheet?.rows != null }
        composeRule.runOnIdle { c.insertExisting(c.sheet!!.visibleRows.single()) }

        assertEquals(twoHeadings, textState.text.toString())
        assertEquals(context.getString(R.string.attach_link_colons_refused), c.toast?.message)
    }

    @Test
    fun `canDelete only in this heading, and for a file owner only file-level rows in this file`() {
        val file = FileEntry("data/AA/0001/a.jpg", 0, 1)
        val heading = AttachOwner.Heading(0, listOf("A"), "AA0001", null)
        fun row(dir: String) = ScopedAttachment(file, dir, "x.org", "A", "")
        val m = AttachSheetModel(heading, "*", "A", "data/AA/0001", scopesFor(heading), AttachScope.HEADING, emptyList(), 10)
        assertTrue(m.canDelete(row("data/AA/0001")))
        assertFalse(m.copy(scope = AttachScope.FILE).canDelete(row("data/AA/0001")))
        assertFalse(m.copy(scope = AttachScope.VAULT).canDelete(row("data/AA/0001")))

        val fileOwner = AttachOwner.File("FF0003", null)
        val f = AttachSheetModel(fileOwner, "#+", "x.org", "data/FF/0003", scopesFor(fileOwner), AttachScope.FILE, emptyList(), 10)
        assertTrue(f.canDelete(row("data/FF/0003")))
        assertFalse(f.canDelete(row("data/AA/0001")))
        assertFalse(f.copy(scope = AttachScope.VAULT).canDelete(row("data/FF/0003")))
    }

    @Test
    fun `a cancelled picker does nothing`() {
        val textState = TextFieldState("* Lunch\n", TextRange(0))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(textState, "travel.org", bufferReady = true, manager) { store }
            }
        }
        composeRule.runOnIdle {
            controller.pending = PendingAttach("Files", false, null, listOf("Lunch"), 0, 0)
            controller.onPickerResult(emptyList())
        }
        composeRule.waitForIdle()
        assertEquals("* Lunch\n", textState.text.toString())
        assertFalse(File(vaultDir, "data").exists())
    }
}
