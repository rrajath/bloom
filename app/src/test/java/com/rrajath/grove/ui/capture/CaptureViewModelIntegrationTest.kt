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
    fun `loadTargetOutline returns the target heading path, or null when missing`() = runTest {
        val vm = capture()
        val found = quickNote.copy(
            targetFile = "inbox.org",
            location = TargetLocation.UnderHeading(customId = "capture-inbox"),
        )
        val missing = found.copy(location = TargetLocation.UnderHeading(id = "NOPE"))

        assertEquals(listOf("Captured"), vm.loadTargetOutline(found))
        assertNull(vm.loadTargetOutline(missing))
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
