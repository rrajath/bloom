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
import com.rrajath.grove.attach.AttachConfig
import com.rrajath.grove.attach.AttachFileContext
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
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.attach.fileOwner
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
    private var attachConfig = AttachConfig.Default
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
                controller = rememberAttachController(
                    textState, "travel.org", bufferReady = true, manager, attachConfig = { attachConfig },
                ) { store }
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
    fun `a pick goes into the configured attach dir`() {
        attachConfig = AttachConfig(idDir = "attach/")
        val text = "* Lunch\nTiny soba place.\n"
        val textState = TextFieldState(text, TextRange(text.length))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(
                    textState, "notes/travel.org", bufferReady = true, manager, attachConfig = { attachConfig },
                ) { store }
            }
        }
        composeRule.runOnIdle {
            controller.open()
            controller.pending = PendingAttach("Files", isFile = false, id = null, titlePath = listOf("Lunch"), lineIndex = 0, cursor = textState.selection.start)
            controller.onPickerResult(listOf(picked("receipt.pdf")))
        }
        settleUntil { textState.text.contains("[[attachment:receipt.pdf]]") }

        val id = OrgParser.parse(textState.text.toString()).headlines.single().properties["ID"]!!
        assertTrue(File(vaultDir, "notes/attach/${id.take(2)}/${id.drop(2)}/receipt.pdf").isFile)
        assertFalse(File(vaultDir, "notes/data").exists())
    }

    @Test
    fun `a pick under a parent DIR goes into the parent's folder without a new ID`() {
        val text = "* Trip\n:PROPERTIES:\n:DIR: trip\n:END:\n** Lunch\n:PROPERTIES:\n:ID: AB12345\n:END:\nsoba\n"
        val textState = TextFieldState(text, TextRange(text.length))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(
                    textState, "notes/travel.org", bufferReady = true, manager, attachConfig = { attachConfig },
                ) { store }
            }
        }
        composeRule.runOnIdle {
            controller.open()
            controller.pending = PendingAttach("Files", false, "AB12345", listOf("Trip", "Lunch"), 4, textState.selection.start)
            controller.onPickerResult(listOf(picked("receipt.pdf")))
        }
        settleUntil { textState.text.contains("[[attachment:receipt.pdf]]") }

        assertTrue(File(vaultDir, "notes/trip/receipt.pdf").isFile)
        assertFalse(File(vaultDir, "notes/data").exists())
        assertTrue(textState.text.toString(), textState.text.contains("soba\n[[attachment:receipt.pdf]]"))
        assertEquals(1, Regex(":ID:").findAll(textState.text).count())
    }

    /**
     * The single-heading editor on [heading] of [fileText] (notes/travel.org):
     * opens the sheet at the end of the subtree buffer, picks a file, and returns
     * the sheet's owner, the final buffer, and the owners reported as attached.
     */
    /** The sheet's "Folder from" line as [attachFromSubtreeEditor] last opened it. */
    private var lastFolderFrom: String? = null

    private fun attachFromSubtreeEditor(fileText: String, heading: String): Triple<AttachOwner, String, List<AttachOwner>> {
        val fileDoc = OrgParser.parse(fileText)
        val h = fileDoc.headlines.first { it.title == heading }
        val buffer = OrgMutations.subtreeText(fileDoc, h)
        val context = AttachFileContext(fileText, h.lineIndex until fileDoc.subtreeEndLine(h))
        val textState = TextFieldState(buffer, TextRange(buffer.trimEnd('\n').length))
        val attached = mutableListOf<AttachOwner>()
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(
                    textState, "notes/travel.org", bufferReady = true, manager,
                    onOwnerAttached = { attached += it }, fileContext = { context }, attachConfig = { attachConfig },
                ) { store }
            }
        }
        composeRule.runOnIdle { controller.open() }
        settleUntil { controller.sheet?.dir != null }
        val owner = controller.sheet!!.owner as AttachOwner.Heading
        lastFolderFrom = controller.sheet!!.folderFrom
        composeRule.runOnIdle {
            controller.pending = PendingAttach("Files", false, owner.id, owner.titlePath, owner.lineIndex, textState.selection.start)
            controller.onPickerResult(listOf(picked("receipt.pdf")))
        }
        settleUntil { textState.text.contains("[[attachment:receipt.pdf]]") }
        return Triple(owner, textState.text.toString(), attached)
    }

    @Test
    fun `a single-heading editor attaches into its parent's DIR`() {
        File(vaultDir, "notes/trip").mkdirs()
        val (owner, buffer, attached) = attachFromSubtreeEditor(
            "* Trip\n:PROPERTIES:\n:DIR: trip\n:END:\n** Lunch\n:PROPERTIES:\n:ID: AB12345\n:END:\nsoba\n", "Lunch",
        )
        assertEquals("trip", owner.dirProperty)
        assertEquals(listOf("Lunch"), (owner as AttachOwner.Heading).titlePath)
        assertTrue(File(vaultDir, "notes/trip/receipt.pdf").isFile)
        assertFalse(File(vaultDir, "notes/data").exists())
        assertTrue(buffer, buffer.contains("soba\n[[attachment:receipt.pdf]]"))
        assertTrue("inherited dir isn't recorded as the heading's own", attached.isEmpty())
    }

    @Test
    fun `a single-heading editor attaches into its grandparent's DIR without adding an ID`() {
        File(vaultDir, "notes/trip").mkdirs()
        val (_, buffer, _) = attachFromSubtreeEditor(
            "* Trip\n:PROPERTIES:\n:DIR: trip\n:END:\n** Kyoto\n*** Lunch\nsoba\n", "Lunch",
        )
        assertTrue(File(vaultDir, "notes/trip/receipt.pdf").isFile)
        assertFalse(buffer, buffer.contains(":ID:"))
    }

    @Test
    fun `a single-heading editor attaches into the file drawer's DIR`() {
        File(vaultDir, "notes/files").mkdirs()
        val (owner, buffer, _) = attachFromSubtreeEditor(
            ":PROPERTIES:\n:DIR: files\n:END:\n#+TITLE: Travel\n\n* Trip\n** Lunch\nsoba\n", "Lunch",
        )
        assertEquals(AttachOwner.File(null, "files"), (owner as AttachOwner.Heading).dirFrom)
        assertEquals("Folder from file settings", lastFolderFrom)
        assertTrue(File(vaultDir, "notes/files/receipt.pdf").isFile)
        assertFalse(buffer, buffer.contains(":ID:"))
    }

    /**
     * Quick capture with body-only text bound for `* Trip` in notes/travel.org
     * ([fileText] on disk): opens the sheet, picks a file and returns the
     * controller, the field and the heading IDs written on disk.
     */
    private fun attachFromBodyOnlyCapture(fileText: String): Triple<AttachController, TextFieldState, List<Pair<AttachOwner.Heading, String>>> {
        File(vaultDir, "notes").mkdirs()
        File(vaultDir, "notes/travel.org").writeText(fileText)
        val textState = TextFieldState("A note.", TextRange(7))
        val headingIds = mutableListOf<Pair<AttachOwner.Heading, String>>()
        val fileDoc = OrgParser.parse(fileText)
        val trip = fileDoc.headlines.first { it.title == "Trip" }
        val placedText = (fileDoc.lines.take(trip.bodyStart) + "A note." + fileDoc.lines.drop(trip.bodyStart)).joinToString("\n")
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(
                    textState, "notes/travel.org", bufferReady = true, manager,
                    addDiskFileId = { _, _ -> error("file owner not expected") },
                    fileContext = { AttachFileContext(placedText, trip.bodyStart..trip.bodyStart, placed = true) },
                    addDiskHeadingId = { _, owner, id -> headingIds += owner to id; true },
                    attachConfig = { attachConfig },
                ) { store }
            }
        }
        composeRule.runOnIdle { controller.open() }
        settleUntil { controller.sheet != null }
        val owner = controller.sheet!!.owner as AttachOwner.Heading
        composeRule.runOnIdle {
            controller.pending = PendingAttach("Files", false, owner.id, owner.titlePath, owner.lineIndex, 7, onDisk = true)
            controller.onPickerResult(listOf(picked("receipt.pdf")))
        }
        settleUntil { textState.text.contains("receipt.pdf") }
        return Triple(controller, textState, headingIds)
    }

    @Test
    fun `body-only capture text attaches to its target heading with a new ID on disk`() {
        val (c, textState, headingIds) = attachFromBodyOnlyCapture("* Trip\nPlans.\n* Other\n")
        val (heading, id) = headingIds.single()
        assertEquals(listOf("Trip"), heading.titlePath)
        assertTrue(File(vaultDir, "notes/data/${id.take(2)}/${id.drop(2)}/receipt.pdf").isFile)
        assertEquals("A note.\n[[attachment:receipt.pdf]]", textState.text.toString())
        assertEquals(id, c.addedDiskHeadingId?.id)
        assertEquals(null, c.addedDiskFileId)
    }

    @Test
    fun `body-only capture text under a DIR heading goes to that folder without an ID`() {
        File(vaultDir, "notes/trip").mkdirs()
        val (c, textState, headingIds) = attachFromBodyOnlyCapture("* Trip\n:PROPERTIES:\n:DIR: trip\n:END:\nPlans.\n")
        assertTrue(headingIds.isEmpty())
        assertTrue(File(vaultDir, "notes/trip/receipt.pdf").isFile)
        assertEquals("A note.\n[[attachment:receipt.pdf]]", textState.text.toString())
        assertEquals(null, c.addedDiskHeadingId)
    }

    @Test
    fun `the sheet names the ancestor an inherited folder comes from`() {
        File(vaultDir, "notes/trip").mkdirs()
        val (owner, _, _) = attachFromSubtreeEditor(
            "* Trip\n:PROPERTIES:\n:DIR: trip\n:END:\n** Kyoto\n*** Lunch\nsoba\n", "Lunch",
        )
        assertEquals("trip", owner.dirProperty)
        assertEquals("Folder from * Trip", lastFolderFrom)
    }

    @Test
    fun `a result that arrives before the buffer is loaded waits for it`() {
        val textState = TextFieldState("* Lunch\n:PROPERTIES:\n:ID: AB12345\n:END:\nbody\n", TextRange(0))
        var ready by mutableStateOf(false)
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(
                    textState, "travel.org", bufferReady = ready, manager, attachConfig = { attachConfig },
                ) { store }
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
                    attachConfig = { attachConfig },
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
    fun `canDelete only the owner's own files, in every scope`() {
        val file = FileEntry("data/AA/0001/a.jpg", 0, 1)
        val heading = AttachOwner.Heading(0, listOf("A"), "AA0001", null)
        fun row(dir: String) = ScopedAttachment(file, dir, "x.org", "A", "")
        val m = AttachSheetModel(heading, "*", "A", "data/AA/0001", scopesFor(heading), AttachScope.HEADING, emptyList(), 10)
        for (scope in AttachScope.entries) {
            assertTrue("own file, $scope", m.copy(scope = scope).canDelete(row("data/AA/0001")))
            assertFalse("other owner's file, $scope", m.copy(scope = scope).canDelete(row("data/BB/0002")))
        }

        val fileOwner = AttachOwner.File("FF0003", null)
        val f = AttachSheetModel(fileOwner, "#+", "x.org", "data/FF/0003", scopesFor(fileOwner), AttachScope.FILE, emptyList(), 10)
        assertTrue(f.canDelete(row("data/FF/0003")))
        assertFalse(f.canDelete(row("data/AA/0001")))
        assertTrue(f.copy(scope = AttachScope.VAULT).canDelete(row("data/FF/0003")))

        // No attach dir yet: nothing is the owner's own.
        assertFalse(m.copy(dir = null, scope = AttachScope.VAULT).canDelete(row("data/AA/0001")))
    }

    @Test
    fun `a cancelled picker does nothing`() {
        val textState = TextFieldState("* Lunch\n", TextRange(0))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(
                    textState, "travel.org", bufferReady = true, manager, attachConfig = { attachConfig },
                ) { store }
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

    /** Quick capture: a snippet bound for `inbox/capture.org`, which [addFileId] gives an ID on disk. */
    private fun captureController(text: String, cursor: Int): Pair<AttachController, TextFieldState> {
        val textState = TextFieldState(text, TextRange(cursor))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(
                    textState, "inbox/capture.org", bufferReady = true, manager,
                    addDiskFileId = { file, id ->
                        store.write(file, OrgMutations.upsertFileProperty(OrgParser.parse(store.read(file)), "ID", id))
                        true
                    },
                    attachConfig = { attachConfig },
                ) { store }
            }
        }
        return controller to textState
    }

    /** Intro editor: the buffer is `notes/trip.org`'s Intro, without its property drawer. */
    private fun introController(text: String, cursor: Int): Pair<AttachController, TextFieldState> {
        val textState = TextFieldState(text, TextRange(cursor))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(
                    textState, "notes/trip.org", bufferReady = true, manager,
                    addDiskFileId = { file, id ->
                        store.write(file, OrgMutations.upsertFileProperty(OrgParser.parse(store.read(file)), "ID", id))
                        true
                    },
                    introOfFile = true,
                    attachConfig = { attachConfig },
                ) { store }
            }
        }
        return controller to textState
    }

    private fun attachToIntro(c: AttachController, textState: TextFieldState, cursor: Int) {
        composeRule.runOnIdle {
            c.pending = PendingAttach("Files", isFile = true, id = null, titlePath = emptyList(), lineIndex = -1, cursor = cursor, onDisk = true)
            c.onPickerResult(listOf(picked("map.pdf")))
        }
        settleUntil { textState.text.contains("map.pdf]]") }
    }

    @Test
    fun `intro without ID or DIR adds a file ID on disk and links with attachment links`() {
        File(vaultDir, "notes").mkdirs()
        val target = File(vaultDir, "notes/trip.org").apply { writeText("#+title: Trip\nPlans.\n* Day 1\n") }
        val (c, textState) = introController("Plans.", cursor = 6)
        c.openAndLoad()
        assertTrue(c.sheet!!.onDisk)
        assertTrue(c.sheet!!.owner is AttachOwner.File)
        assertNull(c.toast)

        attachToIntro(c, textState, 6)
        val id = OrgParser.parse(target.readText()).fileOwner().id!!
        assertTrue("copied", File(vaultDir, "notes/data/${id.take(2)}/${id.drop(2)}/map.pdf").isFile)
        assertEquals("Plans.\n[[attachment:map.pdf]]", textState.text.toString())
    }

    @Test
    fun `intro attach uses the file's DIR over its ID`() {
        File(vaultDir, "notes").mkdirs()
        val original = ":PROPERTIES:\n:ID: abc-123\n:DIR: trip-files\n:END:\nPlans.\n"
        val target = File(vaultDir, "notes/trip.org").apply { writeText(original) }
        val (c, textState) = introController("Plans.", cursor = 6)
        c.openAndLoad()
        assertEquals("trip-files", c.sheet!!.owner.dirProperty)

        attachToIntro(c, textState, 6)
        assertTrue("copied", File(vaultDir, "notes/trip-files/map.pdf").isFile)
        assertEquals(original, target.readText())
        assertEquals("Plans.\n[[attachment:map.pdf]]", textState.text.toString())
    }

    @Test
    fun `capture with a heading at the cursor attaches to that heading in the snippet`() {
        File(vaultDir, "inbox").mkdirs()
        File(vaultDir, "inbox/capture.org").writeText("#+title: Inbox\n")
        val (c, _) = captureController("* TODO Call Sam\nnotes\n", cursor = 18)
        c.openAndLoad()
        assertFalse(c.sheet!!.onDisk)
        assertEquals("Call Sam", (c.sheet!!.owner as AttachOwner.Heading).title)
    }

    @Test
    fun `capture without a heading falls back to the target file and links with file links`() {
        File(vaultDir, "inbox").mkdirs()
        val target = File(vaultDir, "inbox/capture.org").apply { writeText("#+title: Inbox\n") }
        val (c, textState) = captureController("just a thought", cursor = 14)
        c.openAndLoad()
        assertTrue(c.sheet!!.onDisk)
        assertTrue(c.sheet!!.owner is AttachOwner.File)
        assertEquals("capture.org", c.sheet!!.title)

        composeRule.runOnIdle {
            c.pending = PendingAttach("Files", isFile = true, id = null, titlePath = emptyList(), lineIndex = -1, cursor = 14, onDisk = true)
            c.onPickerResult(listOf(picked("receipt.pdf")))
        }
        settleUntil { textState.text.contains("receipt.pdf]]") }

        val id = OrgParser.parse(target.readText()).fileOwner().id!!
        val rel = "data/${id.take(2)}/${id.drop(2)}/receipt.pdf"
        assertTrue("copied", File(vaultDir, "inbox/$rel").isFile)
        assertEquals("just a thought\n[[file:$rel]]", textState.text.toString())
        assertEquals("Attached to capture.org", c.toast?.message)

        // Discard with "Also delete attachments": the session's files go.
        assertEquals(listOf("inbox/$rel"), c.addedFiles)
        assertEquals(id, c.addedDiskFileId)
        composeRule.runOnIdle { c.discardSessionAttachments() }
        settleUntil { !File(vaultDir, "inbox/$rel").exists() }
        assertFalse(File(vaultDir, "inbox/data").exists())
        assertTrue(c.addedFiles.isEmpty())
    }

    @Test
    fun `capture without a heading and no target file opens no sheet`() {
        val (c, _) = captureController("just a thought", cursor = 14)
        composeRule.runOnIdle { c.open() }
        repeat(5) { shadowOf(Looper.getMainLooper()).idle(); composeRule.waitForIdle(); Thread.sleep(20) }
        assertNull(c.sheet)
    }

    @Test
    fun `new Roam node capture attaches under the ID the saved file will get, not a fresh one`() {
        val text = "#+title: Kyoto\nTrip notes"
        val textState = TextFieldState(text, TextRange(text.length))
        lateinit var c: AttachController
        composeRule.setContent {
            GroveTheme {
                c = rememberAttachController(
                    textState, "roam/kyoto.org", bufferReady = true, manager, pendingFileId = "AB12CD34",
                    attachConfig = { attachConfig },
                ) { store }
            }
        }
        c.openAndLoad()
        assertEquals("AB12CD34", c.sheet!!.owner.id)

        composeRule.runOnIdle {
            c.pending = PendingAttach("Files", isFile = true, id = null, titlePath = emptyList(), lineIndex = -1, cursor = text.length)
            c.onPickerResult(listOf(picked("map.pdf")))
        }
        settleUntil { textState.text.contains("[[attachment:map.pdf]]") }

        assertTrue(File(vaultDir, "roam/data/AB/12CD34/map.pdf").isFile)
        // No second :ID: in the buffer: the capture adds AB12CD34 itself on save.
        assertEquals("#+title: Kyoto\nTrip notes\n[[attachment:map.pdf]]", textState.text.toString())
    }
}
