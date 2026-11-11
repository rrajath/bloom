package com.rrajath.grove.ui.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.attach.AttachDirChange
import com.rrajath.grove.attach.AttachDirMoves
import com.rrajath.grove.attach.AttachMoveOutcome
import com.rrajath.grove.attach.AttachOwner
import com.rrajath.grove.attach.fileOwner
import com.rrajath.grove.attach.ownerOf
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.DefaultTemplates
import com.rrajath.grove.capture.TargetLocation
import com.rrajath.grove.capture.TemplateKind
import com.rrajath.grove.capture.TemplatesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSettingsRepository
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.ui.vault.NoteRef
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layer-1 integration coverage for [EditorViewModel]: load → edit → save on a
 * real in-memory Room DB (for the tag query) and an in-memory FileStore.
 *
 * See internal/test-suite-01-integration-robolectric.md § Coverage matrix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EditorViewModelIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = FakeFileStore(OrgFixtures.all)
    private val vault = Vault(store, parseDispatcher = mainDispatcherRule.dispatcher)
    private val vaultFlow = MutableStateFlow<Vault?>(vault)
    private val sync = FakeSyncTrigger()
    private val settings = FakeSettingsRepository()
    private val keywords = MutableStateFlow(OrgKeywords.DEFAULT)
    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val templatesRepository =
        TemplatesRepository(ApplicationProvider.getApplicationContext<Application>())

    private fun editor(attachDirMoves: AttachDirMoves = AttachDirMoves.None) = EditorViewModel(
        vaultFlow = vaultFlow,
        sync = sync,
        database = db,
        settings = settings,
        keywords = keywords,
        dispatchers = mainDispatcherRule.appDispatchers,
        templatesRepository = templatesRepository,
        attachDirMoves = attachDirMoves,
    )

    /** Records what the editor asks of [AttachDirMoves]; reports one broken change per detect when [changes]. */
    private class RecordingDirMoves(var changes: Boolean = true) : AttachDirMoves {
        val detected = mutableListOf<Pair<List<AttachOwner>, String>>()
        val applied = mutableListOf<List<AttachDirChange>>()
        override suspend fun detect(orgFile: String, baseline: List<AttachOwner>, after: OrgDocument): List<AttachDirChange> {
            detected += baseline to after.text
            return if (changes) listOf(AttachDirChange.Broken(after.fileOwner(), orgFile, "dir")) else emptyList()
        }
        override suspend fun apply(changes: List<AttachDirChange>): AttachMoveOutcome {
            applied += changes
            return AttachMoveOutcome()
        }
    }

    /** Line index of the first headline whose title starts with [prefix], in [file]. */
    private fun headlineLine(file: String, prefix: String): Int =
        headlineLineIn(OrgFixtures.all.getValue(file), prefix)

    private fun headlineLineIn(text: String, prefix: String): Int {
        val doc = OrgParser.parse(text)
        return doc.headlines.first { it.title.startsWith(prefix) }.lineIndex
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `load resolves a headline into the buffer and clears loading`() = runTest {
        val line = headlineLine("projects.org", "Ship v2")
        val vm = editor()

        vm.load(NoteRef("projects.org", line))
        advanceUntilIdle()

        val loaded = vm.state.value
        assertFalse(loaded.loading)
        assertEquals("projects.org", loaded.fileName)
        assertEquals(line, loaded.lineIndex)
        assertTrue(loaded.buffer.startsWith("* TODO Ship v2 release"))
        assertTrue(loaded.buffer.contains("** DONE Cut the changelog"))
        assertEquals(store.revisionOf("projects.org"), loaded.loadedRevision)
    }

    @Test
    fun `typing marks the buffer dirty then save writes it back and reindexes`() = runTest {
        val line = headlineLine("projects.org", "Ship v2")
        val vm = editor()
        vm.load(NoteRef("projects.org", line))
        advanceUntilIdle()

        val edited = vm.state.value.buffer.trimEnd() + "\n*** TODO Extra subtask\n"
        vm.onBufferChange(edited)
        assertTrue(vm.state.value.dirty)

        vm.save()
        advanceUntilIdle()

        assertFalse(vm.state.value.dirty)
        assertTrue(store.read("projects.org").contains("*** TODO Extra subtask"))
        assertEquals(1, sync.reindexCalls.size)
        assertEquals("projects.org", sync.reindexCalls.single().fileName)
    }

    @Test
    fun `save refuses a file changed underneath until forced`() = runTest {
        val line = headlineLine("projects.org", "Ship v2")
        val vm = editor()
        vm.load(NoteRef("projects.org", line))
        advanceUntilIdle()

        vm.onBufferChange(vm.state.value.buffer.trimEnd() + "\n*** NEXT Follow up\n")
        store.touch("projects.org") // external edit bumps the revision

        vm.save()
        advanceUntilIdle()
        assertTrue("stale file should block the save", vm.state.value.staleFile)
        assertTrue(vm.state.value.dirty)
        assertFalse(store.read("projects.org").contains("*** NEXT Follow up"))

        vm.save(force = true)
        advanceUntilIdle()
        assertFalse(vm.state.value.staleFile)
        assertFalse(vm.state.value.dirty)
        assertTrue(store.read("projects.org").contains("*** NEXT Follow up"))
    }

    @Test
    fun `loadRegion PREFACE loads only the file preface`() = runTest {
        val vm = editor()
        vm.loadRegion("projects.org", noteId = null, region = EditRegion.PREFACE)
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(EditRegion.PREFACE, s.region)
        assertTrue(s.buffer.contains("#+TITLE: Projects"))
        assertFalse(s.buffer.contains("Ship v2 release"))
    }

    /** A file exercising all three heading-less regions: a leading file property
     *  drawer (line 0), a `#+TITLE:` preface, then heading-less intro prose. */
    private val regionsFile = """
        :PROPERTIES:
        :ID: regions-file-id
        :END:
        #+TITLE: Regions fixture

        Intro prose that lives before any heading.

        * TODO A task with a logbook
        :LOGBOOK:
        - Note taken on [2026-09-01 Tue 09:00] logged while thinking
        :END:
        Body of the task.
    """.trimIndent() + "\n"

    @Test
    fun `loadRegion INTRO loads only the heading-less intro prose`() = runTest {
        store.write("regions.org", regionsFile)
        val vm = editor()

        vm.loadRegion("regions.org", noteId = null, region = EditRegion.INTRO)
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(EditRegion.INTRO, s.region)
        assertEquals("Intro prose that lives before any heading.", s.buffer)
    }

    @Test
    fun `addFileId writes a file ID and the Intro still saves without going stale`() = runTest {
        store.write("trip.org", "#+TITLE: Trip\nPlans.\n* Day 1\n")
        val vm = editor()
        vm.loadRegion("trip.org", noteId = null, region = EditRegion.INTRO)
        advanceUntilIdle()

        assertTrue(vm.addFileId("trip.org", "trip-id"))
        vm.onBufferChange("Plans.\n[[attachment:map.pdf]]")
        vm.save()
        advanceUntilIdle()

        assertFalse(vm.state.value.staleFile)
        val saved = store.read("trip.org")
        assertEquals("trip-id", OrgParser.parse(saved).fileId)
        assertTrue(saved, saved.contains("Plans.\n[[attachment:map.pdf]]\n* Day 1"))
    }

    @Test
    fun `attachFileContext places a heading editor's buffer in its file`() = runTest {
        val text = "* Trip\n:PROPERTIES:\n:DIR: trip\n:END:\n** Lunch\nsoba\n* Other\n"
        store.write("trip.org", text)
        val vm = editor()
        vm.load(NoteRef("trip.org", headlineLineIn(text, "Lunch")))
        advanceUntilIdle()

        val ctx = vm.attachFileContext()!!
        assertEquals(text, ctx.fileText)
        assertEquals(4 until 6, ctx.span)
        val owner = com.rrajath.grove.attach.BufferInFile.of(vm.state.value.buffer, ctx).ownerAtLine(0)
        assertEquals("trip", owner.dirProperty)
    }

    @Test
    fun `attachFileContext is null for a region editor`() = runTest {
        store.write("trip.org", "#+TITLE: Trip\nPlans.\n* Day 1\n")
        val vm = editor()
        vm.loadRegion("trip.org", noteId = null, region = EditRegion.INTRO)
        advanceUntilIdle()
        assertEquals(null, vm.attachFileContext())
    }

    @Test
    fun `addFileId leaves a file with a DIR alone`() = runTest {
        val original = ":PROPERTIES:\n:DIR: files\n:END:\nPlans.\n"
        store.write("trip.org", original)
        val vm = editor()
        assertTrue(vm.addFileId("trip.org", "trip-id"))
        assertEquals(original, store.read("trip.org"))
    }

    @Test
    fun `explicit save shows the attach dir notice and OK applies it before continuing`() = runTest {
        store.write("trip.org", "* Day 1\n:PROPERTIES:\n:ID: AA11\n:END:\n")
        val moves = RecordingDirMoves()
        val vm = editor(moves)
        vm.loadRegion("trip.org", noteId = null, region = EditRegion.WHOLE_FILE)
        advanceUntilIdle()

        vm.onBufferChange("* Day 1\n:PROPERTIES:\n:ID: AA11\n:DIR: photos\n:END:\n")
        var left = false
        vm.save(onSaved = { left = true })
        advanceUntilIdle()

        // The baseline is the file as loaded; the notice holds back onSaved.
        assertEquals(listOf("AA11"), moves.detected.single().first.mapNotNull { it.id })
        assertNotNull(vm.attachDirNotice.value)
        assertFalse(left)
        vm.acknowledgeAttachDirNotice()
        // The move runs first; the notice stays (busy) until it's done.
        assertTrue(vm.attachDirNotice.value!!.busy)
        advanceUntilIdle()
        assertTrue(left)
        assertEquals(1, moves.applied.size)
        assertNull(vm.attachDirNotice.value)
    }

    @Test
    fun `idle auto-save never checks attach dirs, leaving without changes still does`() = runTest {
        store.write("trip.org", "* Day 1\n")
        val moves = RecordingDirMoves(changes = false)
        val vm = editor(moves)
        vm.loadRegion("trip.org", noteId = null, region = EditRegion.WHOLE_FILE)
        advanceUntilIdle()

        vm.onBufferChange("* Day 1\n:PROPERTIES:\n:DIR: pho\n:END:\n")
        vm.save(explicit = false)
        advanceUntilIdle()
        assertTrue(moves.detected.isEmpty())

        // Leaving (nothing dirty now) checks what the auto-save wrote.
        var left = false
        vm.save(onSaved = { left = true })
        advanceUntilIdle()
        assertEquals(1, moves.detected.size)
        assertTrue(left)
    }

    @Test
    fun `discard undoes what an idle auto-save wrote since the last save`() = runTest {
        val original = "* Day 1\n:PROPERTIES:\n:DIR: photos\n:END:\n[[attachment:a.jpg]]\n"
        store.write("trip.org", original)
        val vm = editor()
        vm.loadRegion("trip.org", noteId = null, region = EditRegion.WHOLE_FILE)
        advanceUntilIdle()

        vm.onBufferChange(original.replace("photos", "kyoto"))
        vm.save(explicit = false)
        advanceUntilIdle()
        assertTrue(store.read("trip.org").contains(":DIR: kyoto"))

        vm.onBufferChange(original.replace("photos", "kyoto") + "more\n")
        var left = false
        vm.discardChanges { left = true }
        advanceUntilIdle()
        assertTrue(left)
        assertEquals(original, store.read("trip.org"))
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `discard after an explicit save keeps that save`() = runTest {
        store.write("trip.org", "* Day 1\n")
        val vm = editor()
        vm.loadRegion("trip.org", noteId = null, region = EditRegion.WHOLE_FILE)
        advanceUntilIdle()
        vm.onBufferChange("* Day 1\nsaved\n")
        vm.save()
        advanceUntilIdle()
        vm.onBufferChange("* Day 1\nsaved\nauto\n")
        vm.save(explicit = false)
        advanceUntilIdle()

        vm.discardChanges {}
        advanceUntilIdle()
        assertEquals("* Day 1\nsaved\n", store.read("trip.org"))
    }

    @Test
    fun `a renamed clash rewrites the link on disk and in the open buffer`() = runTest {
        val text = "* Day 1\n:PROPERTIES:\n:ID: AA11\n:END:\n[[attachment:a.jpg]]\n"
        store.write("trip.org", text)
        val withDir = text.replace(":ID: AA11\n", ":ID: AA11\n:DIR: photos\n")
        val moves = object : AttachDirMoves {
            override suspend fun detect(orgFile: String, baseline: List<AttachOwner>, after: OrgDocument) =
                listOf(
                    AttachDirChange.Move(
                        after.ownerOf(after.headlines.single()), orgFile, "data/AA/11", "photos",
                        AttachDirChange.Kind.DIR_ADDED, copy = false,
                    ),
                )
            override suspend fun apply(changes: List<AttachDirChange>) =
                AttachMoveOutcome(renames = listOf(changes.single() as AttachDirChange.Move to mapOf("a.jpg" to "a-1.jpg")))
        }
        val vm = editor(moves)
        vm.loadRegion("trip.org", noteId = null, region = EditRegion.WHOLE_FILE)
        advanceUntilIdle()
        vm.onBufferChange(withDir)
        vm.save()
        advanceUntilIdle()
        vm.acknowledgeAttachDirNotice()
        advanceUntilIdle()

        val fixed = withDir.replace("a.jpg", "a-1.jpg")
        assertEquals(fixed, store.read("trip.org"))
        assertEquals(fixed, vm.state.value.buffer)
        assertFalse(vm.state.value.dirty)
        assertNull(vm.attachDirNotice.value)
    }

    @Test
    fun `loadRegion FILE_PROPERTIES loads the leading file drawer with its markers`() = runTest {
        store.write("regions.org", regionsFile)
        val vm = editor()

        vm.loadRegion("regions.org", noteId = null, region = EditRegion.FILE_PROPERTIES)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(EditRegion.FILE_PROPERTIES, s.region)
        assertTrue(s.buffer.startsWith(":PROPERTIES:"))
        assertTrue(s.buffer.contains(":ID: regions-file-id"))
        assertTrue(s.buffer.trimEnd().endsWith(":END:"))
        assertFalse(s.buffer.contains("#+TITLE"))
    }

    @Test
    fun `loadRegion HEADING_LOGBOOK loads just that heading's logbook drawer`() = runTest {
        store.write("regions.org", regionsFile)
        val line = headlineLineIn(regionsFile, "A task with a logbook")
        val vm = editor()

        vm.loadRegion(
            "regions.org",
            noteId = NoteRef("regions.org", line).encode(),
            region = EditRegion.HEADING_LOGBOOK,
        )
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(EditRegion.HEADING_LOGBOOK, s.region)
        assertEquals(line, s.lineIndex)
        assertTrue(s.buffer.startsWith(":LOGBOOK:"))
        assertTrue(s.buffer.contains("logged while thinking"))
        assertFalse(s.buffer.contains("Body of the task."))
    }

    @Test
    fun `loadRegion WHOLE_FILE loads the entire file and save writes it back verbatim`() = runTest {
        store.write("regions.org", regionsFile)
        val vm = editor()

        vm.loadRegion("regions.org", noteId = null, region = EditRegion.WHOLE_FILE)
        advanceUntilIdle()

        val loaded = vm.state.value
        assertFalse(loaded.loading)
        assertEquals(EditRegion.WHOLE_FILE, loaded.region)
        assertEquals(regionsFile, loaded.buffer)

        // An edit that touches every region at once: the drawer, the intro and a
        // heading. No splice is involved, so the file must equal the buffer exactly.
        val edited = regionsFile
            .replace(":ID: regions-file-id", ":ID: renamed-id")
            .replace("Intro prose that lives before any heading.", "Rewritten intro.")
            .replace("* TODO A task with a logbook", "* DONE A task with a logbook")
        vm.onBufferChange(edited)
        assertTrue(vm.state.value.dirty)

        vm.save()
        advanceUntilIdle()

        assertFalse(vm.state.value.dirty)
        assertEquals(edited, store.read("regions.org"))
        assertEquals(1, sync.reindexCalls.size)
    }

    @Test
    fun `loadNewWholeFile seeds a clean buffer that save leaves uncreated until actually edited`() = runTest {
        val vm = editor()
        val seed = ":PROPERTIES:\n:ID: X\n:END:\n#+title: 2026-09-23\n"

        vm.loadNewWholeFile("roam/daily/2026-09-23.org", seed, cursor = 10)
        assertFalse("the seed template alone is not a user edit", vm.state.value.dirty)
        assertEquals(EditRegion.WHOLE_FILE, vm.state.value.region)

        // A save before any real typing (e.g. the user exits within the idle
        // auto-save window) must be a no-op: creating the file with nothing but
        // the seed's preamble would silently discard whatever they typed next.
        vm.save()
        advanceUntilIdle()
        assertFalse("roam/daily/2026-09-23.org shouldn't exist without a real edit", store.snapshot().containsKey("roam/daily/2026-09-23.org"))

        vm.onBufferChange(seed + "Actual note text\n")
        assertTrue(vm.state.value.dirty)

        vm.save()
        advanceUntilIdle()

        assertFalse(vm.state.value.dirty)
        assertTrue(store.snapshot().getValue("roam/daily/2026-09-23.org").endsWith("Actual note text\n"))
    }

    @Test
    fun `changeKeyword to a done state writes DONE to the file and requests a sync`() = runTest {
        val line = headlineLine("projects.org", "Ship v2")
        val vm = editor()
        vm.load(NoteRef("projects.org", line))
        advanceUntilIdle()

        vm.changeKeyword("DONE")
        advanceUntilIdle()

        assertTrue(store.read("projects.org").contains("DONE Ship v2 release"))
        assertTrue(sync.reindexCalls.any { it.reason == "note state set" })
        assertFalse(vm.state.value.dirty)
        assertTrue(vm.state.value.buffer.contains("DONE Ship v2 release"))
    }

    @Test
    fun `onBufferChangedExternally bumps bufferRevision but typed changes do not`() = runTest {
        val line = headlineLine("projects.org", "Ship v2")
        val vm = editor()
        vm.load(NoteRef("projects.org", line))
        advanceUntilIdle()
        val base = vm.state.value.bufferRevision

        vm.onBufferChange(vm.state.value.buffer + "\n*** TODO typed subtask\n")
        assertEquals("typing must not bump bufferRevision", base, vm.state.value.bufferRevision)

        vm.onBufferChangedExternally(vm.state.value.buffer + "\n*** TODO folded-in subtask\n")
        assertEquals(base + 1, vm.state.value.bufferRevision)

        // An externally-reported no-op change does not bump it again.
        vm.onBufferChangedExternally(vm.state.value.buffer)
        assertEquals(base + 1, vm.state.value.bufferRevision)
    }

    @Test
    fun `deleteSubtree removes the heading and its children from the file and reindexes`() = runTest {
        val line = headlineLine("projects.org", "Backlog")
        val vm = editor()
        vm.load(NoteRef("projects.org", line))
        advanceUntilIdle()

        var deleted = false
        vm.deleteSubtree { deleted = true }
        advanceUntilIdle()

        assertTrue(deleted)
        val text = store.read("projects.org")
        assertFalse(text.contains("Backlog"))
        assertFalse("a child of the deleted subtree", text.contains("Dark mode polish"))
        assertTrue("an untouched sibling", text.contains("Ship v2 release"))
        assertEquals(1, sync.reindexCalls.size)
        assertEquals("projects.org", sync.reindexCalls.single().fileName)
    }

    @Test
    fun `a heading typed at the top level and deleted after an intermediate save leaves the file`() = runTest {
        store.write("test.org", "* A\nbody\n* Z\n")
        val vm = editor()
        vm.load(NoteRef("test.org", 0))
        advanceUntilIdle()

        // Grows past the original subtree: after this save `* B` is A's sibling on disk.
        vm.onBufferChange("* A\nbody\n** A.1\n* B")
        vm.save()
        advanceUntilIdle()
        assertEquals("* A\nbody\n** A.1\n* B\n* Z\n", store.read("test.org"))

        vm.onBufferChange("* A\nbody")
        vm.save()
        advanceUntilIdle()
        assertEquals("* A\nbody\n* Z\n", store.read("test.org"))
    }

    @Test
    fun `replacing the first heading line between saves does not duplicate the buffer`() = runTest {
        store.write("test.org", "* A\nbody\n* Z\n")
        val vm = editor()
        vm.load(NoteRef("test.org", 0))
        advanceUntilIdle()

        vm.onBufferChange("just prose")
        vm.save()
        advanceUntilIdle()
        vm.onBufferChange("* A2\nbody")
        vm.save()
        advanceUntilIdle()

        assertEquals("* A2\nbody\n* Z\n", store.read("test.org"))
    }

    @Test
    fun `deleteSubtree after an intermediate save removes every line the editor wrote`() = runTest {
        store.write("test.org", "* A\n* Z\n")
        val vm = editor()
        vm.load(NoteRef("test.org", 0))
        advanceUntilIdle()

        vm.onBufferChange("* \n* B")
        vm.save()
        advanceUntilIdle()
        vm.deleteSubtree()
        advanceUntilIdle()

        assertEquals("* Z\n", store.read("test.org"))
    }

    @Test
    fun `setTags rewrites the heading's tags and marks the buffer dirty`() = runTest {
        val line = headlineLine("projects.org", "Ship v2")
        val vm = editor()
        vm.load(NoteRef("projects.org", line))
        advanceUntilIdle()

        val revisionBefore = vm.state.value.bufferRevision
        vm.setTags(listOf("release", "urgent"))

        val s = vm.state.value
        assertTrue(s.dirty)
        assertTrue(s.buffer.lineSequence().first().contains(":release:urgent:"))
        assertEquals(
            "metadata-sheet edits mirror into the field",
            revisionBefore + 1,
            s.bufferRevision,
        )
    }

    // --- roam-node suggestion gate ---

    private val eligibleRoam = CaptureTemplate(
        id = "roam-typed-title",
        name = "Roam",
        targetFile = "unused.org",
        location = TargetLocation.BottomOfFile,
        template = "unused",
        kind = TemplateKind.ROAM_NODE,
        newFileTemplate = ":PROPERTIES:\n:ID: %(id)\n:END:\n#+title: %?",
    )
    private val fixedTitleRoam = eligibleRoam.copy(id = "roam-fixed-title", newFileTemplate = "#+title: Inbox")
    private val plain = eligibleRoam.copy(id = "plain", kind = TemplateKind.PLAIN)

    @Test
    fun `roamNodeSuggestionTemplates needs both Roam Features and its suggestions toggle`() = runTest {
        settings.update { it.copy(roamFeaturesEnabled = true, roamShowSuggestions = true) }
        templatesRepository.save(listOf(eligibleRoam, fixedTitleRoam, plain))
        try {
            val vm = editor()
            backgroundScope.launch { vm.roamNodeSuggestionTemplates.collect {} }
            // The templates DataStore reads on a real IO thread, so wait for it rather
            // than advanceUntilIdle; only typed-title Roam templates are offered.
            assertEquals(listOf(eligibleRoam), vm.roamNodeSuggestionTemplates.first { it.isNotEmpty() })

            // Roam Features on but "Show suggestions while typing" off: nothing offered
            // (this used to key on roamFeaturesEnabled alone).
            settings.update { it.copy(roamShowSuggestions = false) }
            advanceUntilIdle()
            assertEquals(emptyList<CaptureTemplate>(), vm.roamNodeSuggestionTemplates.value)

            // The sub-toggle alone is inert without the Roam Features master switch.
            settings.update { it.copy(roamFeaturesEnabled = false, roamShowSuggestions = true) }
            advanceUntilIdle()
            assertEquals(emptyList<CaptureTemplate>(), vm.roamNodeSuggestionTemplates.value)

            settings.update { it.copy(roamFeaturesEnabled = true) }
            advanceUntilIdle()
            assertEquals(listOf(eligibleRoam), vm.roamNodeSuggestionTemplates.value)
        } finally {
            // The templates DataStore is process-wide; put the defaults back for later tests.
            templatesRepository.save(DefaultTemplates.all)
        }
    }
}
