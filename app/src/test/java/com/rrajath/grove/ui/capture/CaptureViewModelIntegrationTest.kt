package com.rrajath.grove.ui.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.capture.CaptureContext
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.TargetLocation
import com.rrajath.grove.capture.TemplatesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSettingsRepository
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.ui.editor.AddedHeadingMarks
import com.rrajath.grove.ui.editor.DiskHeadingMarks
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Layer-1 integration coverage for [CaptureViewModel]: the save path writes the
 * entry into the target notebook and requests a sync; the guard rails
 * (blank text, no vault folder) surface as [SaveState.Failed].
 *
 * See internal/test-suite-01-integration-robolectric.md § Coverage matrix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CaptureViewModelIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = FakeFileStore(OrgFixtures.all)
    private val vaultFlow = MutableStateFlow<Vault?>(Vault(store, parseDispatcher = mainDispatcherRule.dispatcher))
    private val sync = FakeSyncTrigger()
    private val settings = FakeSettingsRepository(GroveSettings(vaultTreeUri = "content://vault/tree"))
    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val templatesRepository =
        TemplatesRepository(ApplicationProvider.getApplicationContext<Application>())

    private val quickNote = CaptureTemplate(
        id = "t-quick",
        name = "Quick note",
        targetFile = "inbox.org",
        location = TargetLocation.BottomOfFile,
        template = "* %cursor",
    )

    private val context = CaptureContext(now = LocalDateTime.of(2026, 9, 6, 10, 0))

    private fun capture(
        settingsSource: FakeSettingsRepository = settings,
    ) = CaptureViewModel(
        templatesRepository = templatesRepository,
        database = db,
        sync = sync,
        vaultFlow = vaultFlow,
        settings = settingsSource,
        dispatchers = mainDispatcherRule.appDispatchers,
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save inserts the entry into the target file and requests a sync`() = runTest {
        val vm = capture()

        vm.save(quickNote, "* A captured thought from the share sheet", context)
        advanceUntilIdle()

        assertEquals(SaveState.Saved("inbox.org"), vm.saveState.value)
        assertTrue(
            store.read("inbox.org").contains("* A captured thought from the share sheet"),
        )
        assertEquals(listOf("capture saved"), sync.reindexCalls.map { it.reason })
    }

    @Test
    fun `save into a not-yet-existing notebook creates it`() = runTest {
        val vm = capture()
        val template = quickNote.copy(targetFile = "captures.org")

        vm.save(template, "* Brand new notebook entry", context)
        advanceUntilIdle()

        assertEquals(SaveState.Saved("captures.org"), vm.saveState.value)
        assertTrue(store.exists("captures.org"))
        assertTrue(store.read("captures.org").contains("* Brand new notebook entry"))
    }

    @Test
    fun `blank entry text fails without touching the vault`() = runTest {
        val vm = capture()
        val before = store.snapshot()

        vm.save(quickNote, "   ", context)
        advanceUntilIdle()

        assertEquals(SaveState.Failed("Nothing to save"), vm.saveState.value)
        assertEquals(before, store.snapshot())
        assertTrue(sync.syncRequests.isEmpty())
    }

    @Test
    fun `save with no vault folder configured fails and never fires a sync`() = runTest {
        val vm = capture(FakeSettingsRepository(GroveSettings(vaultTreeUri = null)))
        val before = store.snapshot()

        vm.save(quickNote, "* Should not be saved", context)
        advanceUntilIdle()

        assertEquals(SaveState.Failed("No sync folder configured"), vm.saveState.value)
        assertEquals("the vault must be untouched", before, store.snapshot())
        assertTrue("no sync may be requested on a failed save", sync.syncRequests.isEmpty())
    }

    @Test
    fun `save under a heading found by CUSTOM_ID inserts it as that heading's child`() = runTest {
        val vm = capture()
        val template = quickNote.copy(
            targetFile = "inbox.org",
            location = TargetLocation.UnderHeading(customId = "capture-inbox"),
        )

        vm.save(template, "* Filed under the custom id", context)
        advanceUntilIdle()

        assertEquals(SaveState.Saved("inbox.org"), vm.saveState.value)
        val text = store.read("inbox.org")
        // "Captured" is a level-1 heading, so the entry lands one level deeper.
        assertTrue(text.contains("** Filed under the custom id"))
        assertTrue(
            "entry must sit inside the Captured subtree",
            text.indexOf("Filed under the custom id") > text.indexOf(":CUSTOM_ID: capture-inbox"),
        )
    }

    @Test
    fun `body-only save adds the content to the heading's body without a new heading`() = runTest {
        val vm = capture()
        val template = quickNote.copy(
            targetFile = "inbox.org",
            location = TargetLocation.UnderHeading(customId = "capture-inbox"),
            template = "- [ ] %?",
        )

        vm.save(template, "- [ ] Milk", context, bodyOnly = true)
        advanceUntilIdle()

        assertEquals(SaveState.Saved("inbox.org"), vm.saveState.value)
        val text = store.read("inbox.org")
        assertTrue(text.contains("\n- [ ] Milk\n"))
        assertFalse("must not become a heading", text.contains("* - [ ] Milk"))
    }

    @Test
    fun `body-only save of a blank draft is refused`() = runTest {
        val vm = capture()
        val template = quickNote.copy(
            targetFile = "inbox.org",
            location = TargetLocation.UnderHeading(customId = "capture-inbox"),
        )
        val before = store.snapshot()

        vm.save(template, "  \n", context, bodyOnly = true)
        advanceUntilIdle()

        assertEquals(SaveState.Failed("Nothing to save"), vm.saveState.value)
        assertEquals(before, store.snapshot())
    }

    @Test
    fun `loadTargetInfo returns the target heading path and entry level, or null when missing`() = runTest {
        val vm = capture()
        val found = quickNote.copy(
            targetFile = "inbox.org",
            location = TargetLocation.UnderHeading(customId = "capture-inbox"),
        )
        val missing = found.copy(location = TargetLocation.UnderHeading(id = "NOPE"))

        assertEquals(CaptureViewModel.TargetInfo(listOf("Captured"), 2), vm.loadTargetInfo(found))
        assertNull(vm.loadTargetInfo(missing))
    }

    @Test
    fun `save under a heading found by exact title inserts it as that heading's child`() = runTest {
        val vm = capture()
        val template = quickNote.copy(
            targetFile = "inbox.org",
            location = TargetLocation.UnderHeading(title = "Captured"),
        )

        vm.save(template, "* Filed by title", context)
        advanceUntilIdle()

        assertEquals(SaveState.Saved("inbox.org"), vm.saveState.value)
        assertTrue(store.read("inbox.org").contains("** Filed by title"))
    }

    @Test
    fun `a second autosave replaces the first draft in place rather than duplicating it`() = runTest {
        val vm = capture()

        vm.autosave(quickNote, "* Draft one", context)
        advanceUntilIdle()
        vm.autosave(quickNote, "* Draft two", context)
        advanceUntilIdle()

        val text = store.read("inbox.org")
        assertTrue(text.contains("Draft two"))
        assertFalse("the first draft must have been stripped", text.contains("Draft one"))

        // The final explicit Save replaces the standing draft the same way.
        vm.save(quickNote, "* Final entry", context)
        advanceUntilIdle()
        val saved = store.read("inbox.org")
        assertTrue(saved.contains("Final entry"))
        assertFalse(saved.contains("Draft two"))
    }

    @Test
    fun `addFileId gives the target an ID and the next save still replaces the draft`() = runTest {
        val vm = capture()

        vm.autosave(quickNote, "* Draft one", context)
        advanceUntilIdle()
        assertTrue(vm.addFileId("inbox.org", "F1LE1D"))
        vm.save(quickNote, "* Final entry", context)
        advanceUntilIdle()

        val saved = store.read("inbox.org")
        assertTrue(saved, saved.startsWith(":PROPERTIES:\n:ID: F1LE1D\n:END:\n"))
        assertTrue(saved.contains("Final entry"))
        assertFalse("the draft must have been replaced in place", saved.contains("Draft one"))
    }

    @Test
    fun `an autosave after the explicit save never adds a second copy`() = runTest {
        // Regression: the attach-folder notice keeps the screen up after Save, and
        // the idle autosave fired then, re-inserting the entry with no draft to replace.
        val vm = capture()
        vm.autosave(quickNote, "* Entry", context)
        advanceUntilIdle()
        vm.save(quickNote, "* Entry\n:PROPERTIES:\n:DIR: photos\n:END:", context)
        advanceUntilIdle()
        vm.autosave(quickNote, "* Entry\n:PROPERTIES:\n:DIR: photos\n:END:", context)
        advanceUntilIdle()

        val saved = store.read("inbox.org")
        assertEquals(1, Regex("""(?m)^\*+ Entry$""").findAll(saved).count())
    }

    private val underTrip = quickNote.copy(
        targetFile = "travel.org",
        location = TargetLocation.UnderHeading(path = listOf("Trip")),
    )
    private val tripFile = "* Trip\n:PROPERTIES:\n:DIR: trip\n:END:\nPlans.\n* Other\n"

    @Test
    fun `attachFileContext places the entry where save puts it, replacing the autosaved draft`() = runTest {
        store.write("travel.org", tripFile)
        val vm = capture()
        vm.autosave(underTrip, "* Draft", context)
        advanceUntilIdle()

        val ctx = vm.attachFileContext(underTrip, "* Lunch\nSoba.", context, bodyOnly = false)!!
        assertTrue(ctx.placed)
        assertFalse(ctx.fileText, ctx.fileText.contains("Draft"))
        val placed = com.rrajath.grove.attach.BufferInFile.of("* Lunch\nSoba.", ctx)
        assertEquals("trip", placed.ownerAtLine(1).dirProperty)
    }

    @Test
    fun `attachFileContext for body-only text resolves to the target heading`() = runTest {
        store.write("travel.org", tripFile)
        val ctx = capture().attachFileContext(underTrip, "A note.", context, bodyOnly = true)!!
        val around = com.rrajath.grove.attach.BufferInFile.of("A note.", ctx).ownerAround(0)
            as com.rrajath.grove.attach.AttachOwner.Heading
        assertEquals(listOf("Trip"), around.titlePath)
        assertEquals("trip", around.dirProperty)
    }

    @Test
    fun `attachFileContext is null when the target heading is missing`() = runTest {
        store.write("travel.org", "* Other\n")
        assertEquals(null, capture().attachFileContext(underTrip, "A note.", context, bodyOnly = true))
    }

    @Test
    fun `markAttachHeading gives the target heading an ID and tag, keeps the draft in place, and discard takes them back`() = runTest {
        store.write("travel.org", "* Trip\nPlans.\n* Other\n")
        val vm = capture()
        vm.autosave(underTrip, "Draft note.", context, bodyOnly = true)
        advanceUntilIdle()
        val trip = com.rrajath.grove.attach.AttachOwner.Heading(0, listOf("Trip"), null, null)

        val marks = vm.markAttachHeading("travel.org", trip, "H1D", "receipt")
        assertEquals(DiskHeadingMarks("H1D", "receipt"), marks)
        vm.save(underTrip, "Final note.", context, bodyOnly = true)
        advanceUntilIdle()
        val saved = store.read("travel.org")
        assertTrue(saved, saved.startsWith("* Trip  :receipt:\n:PROPERTIES:\n:ID: H1D\n:END:\n"))
        assertTrue(saved, saved.contains("Final note."))
        assertFalse("the draft must have been replaced in place", saved.contains("Draft note."))

        vm.removeAddedHeadingMarks("travel.org", AddedHeadingMarks(trip, marks!!))
        advanceUntilIdle()
        assertEquals("* Trip\nPlans.\nFinal note.\n* Other\n", store.read("travel.org"))
    }

    @Test
    fun `markAttachHeading with a blank tag only adds the ID, and an already tagged heading isn't tagged again`() = runTest {
        store.write("travel.org", "* Trip  :ATTACH:\nPlans.\n")
        val vm = capture()
        val trip = com.rrajath.grove.attach.AttachOwner.Heading(0, listOf("Trip"), null, null)
        assertEquals(DiskHeadingMarks("H1D", null), vm.markAttachHeading("travel.org", trip, "H1D", "ATTACH"))
        assertEquals(DiskHeadingMarks(null, null), vm.markAttachHeading("travel.org", trip, null, ""))
        assertEquals("* Trip  :ATTACH:\n:PROPERTIES:\n:ID: H1D\n:END:\nPlans.\n", store.read("travel.org"))
    }

    @Test
    fun `withoutHeadingId keeps other properties`() {
        val text = "* A\n:PROPERTIES:\n:ID: X\n:DIR: a\n:END:\nbody"
        assertEquals("* A\n:PROPERTIES:\n:DIR: a\n:END:\nbody", withoutHeadingId(text, "X"))
        assertEquals(null, withoutHeadingId(text, "Y"))
    }

    @Test
    fun `addFileId on a missing file is refused`() = runTest {
        assertFalse(capture().addFileId("nope.org", "F1LE1D"))
    }

    @Test
    fun `discardDraft removes the autosaved entry and requests a sync`() = runTest {
        val vm = capture()

        vm.autosave(quickNote, "* Abandoned draft", context)
        advanceUntilIdle()
        assertTrue(store.read("inbox.org").contains("Abandoned draft"))

        vm.discardDraft(quickNote)
        advanceUntilIdle()

        assertFalse(store.read("inbox.org").contains("Abandoned draft"))
        assertTrue(sync.reindexCalls.any { it.reason == "capture discarded" })
    }
}
