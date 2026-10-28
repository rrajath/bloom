package com.rrajath.grove.ui.vault

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.settings.PinKind
import com.rrajath.grove.settings.PinnedItem
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layer-1 integration coverage for [NotebooksViewModel]: the folder tree built
 * from the Room index, and the notebook/folder create / rename / move / delete
 * operations against a real [Vault] over an in-memory [FakeFileStore] plus their
 * index-row and sync side effects.
 *
 * These operations also re-key icon colours / pin state through the real
 * [SettingsRepository]. That DataStore write is *not* asserted here: it runs on
 * its own `Dispatchers.IO` scope off virtual time, and the tests assert the
 * vault + index effects, which land first. The settings re-keying is
 * [SettingsRepository]'s own responsibility and has direct coverage there.
 * Every VM's scope is cancelled in teardown so its `state` collector and its
 * first-open-defaults write don't leak into the next Robolectric class.
 *
 * See internal/test-suite-01-integration-robolectric.md § Coverage matrix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, sdk = [34])
class NotebooksViewModelIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val app = ApplicationProvider.getApplicationContext<TestGroveApplication>()

    /** [OrgFixtures.all] plus two notebooks nested under `work/` for the folder cases. */
    private val nested = OrgFixtures.all + mapOf(
        "work/status.org" to "#+TITLE: Status\n\n* TODO Draft the update\n",
        "work/notes/standup.org" to "#+TITLE: Standup\n\n* Blockers\n",
    )

    private val store = FakeFileStore(nested)
    private val vaultFlow = MutableStateFlow<Vault?>(Vault(store, parseDispatcher = mainDispatcherRule.dispatcher))
    private val sync = FakeSyncTrigger()
    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val repoScope = CoroutineScope(mainDispatcherRule.dispatcher)
    private val settingsRepository = SettingsRepository(app, repoScope)

    private val liveVms = mutableListOf<NotebooksViewModel>()

    private fun notebooksVm(): NotebooksViewModel = NotebooksViewModel(
        vaultFlow = vaultFlow,
        database = db,
        settingsRepository = settingsRepository,
        sync = sync,
        dispatchers = mainDispatcherRule.appDispatchers,
    ).also { liveVms += it }

    private suspend fun indexedFileNames(): Set<String> =
        db.indexDao().notebooks().map { it.fileName }.toSet()

    /** Poll (real time + scheduler drains) until [condition] holds, or fail. */
    private suspend fun TestScope.settleUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (condition()) return
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(20)
        }
        advanceUntilIdle()
        if (!condition()) throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    /** Seed the index and build a VM. */
    private suspend fun TestScope.seededVm(): NotebooksViewModel {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        return notebooksVm().also {
            // state is SharingStarted.WhileSubscribed now — park a collector so it
            // leaves the NoVault seed and builds the real tree.
            backgroundScope.launch { it.state.collect {} }
        }
    }

    @After
    fun tearDown() {
        liveVms.forEach { it.viewModelScope.cancel() }
        repoScope.cancel()
        db.close()
    }

    @Test
    fun `no vault yields the NoVault state`() = runTest {
        vaultFlow.value = null
        val vm = notebooksVm()
        advanceUntilIdle()

        assertEquals(NotebooksUiState.NoVault, vm.state.value)
    }

    @Test
    fun `an indexed vault builds a Loaded tree with every notebook and its folders`() = runTest {
        val vm = seededVm()
        advanceUntilIdle()

        val loaded = vm.state.value as NotebooksUiState.Loaded
        val names = loaded.notebooks.map { it.fileName }.toSet()
        assertTrue(names.contains("inbox.org"))
        assertTrue(names.contains("work/status.org"))
        assertTrue(names.contains("work/notes/standup.org"))
        assertTrue("the nested files should produce folder rows", loaded.hasFolders)
        assertTrue(loaded.rows.any { it is NotebookTreeRow.Folder && it.node.dir == "work" })
    }

    @Test
    fun `createNotebook writes the file and requests a sync`() = runTest {
        val vm = seededVm()

        vm.createNotebook("ideas")
        settleUntil { sync.syncRequests.contains("notebook created") }

        assertTrue(store.exists("ideas.org"))
    }

    @Test
    fun `createNotebook onto an existing name writes nothing and never syncs`() = runTest {
        val vm = seededVm()
        val before = store.snapshot()

        vm.createNotebook("inbox")
        advanceUntilIdle()

        assertEquals("the collision must not create a file", before, store.snapshot())
        assertFalse("no sync for a rejected create", sync.syncRequests.contains("notebook created"))
    }

    @Test
    fun `renameNotebook moves the file and drops the stale index row`() = runTest {
        val vm = seededVm()

        vm.renameNotebook("reading-list.org", "library")
        settleUntil { store.exists("library.org") && !indexedFileNames().contains("reading-list.org") }

        assertFalse(store.exists("reading-list.org"))
    }

    @Test
    fun `renameNotebook onto an existing name is rejected and still syncs`() = runTest {
        val vm = seededVm()

        vm.renameNotebook("reading-list.org", "inbox")
        settleUntil { sync.syncRequests.contains("notebook renamed") }

        assertTrue("the rename must be rejected, not applied", store.exists("reading-list.org"))
    }

    @Test
    fun `deleteNotebook removes the file and its index row and syncs`() = runTest {
        val vm = seededVm()

        vm.deleteNotebook("table.org")
        settleUntil { sync.syncRequests.contains("notebook deleted") }

        assertFalse(store.exists("table.org"))
        assertFalse(indexedFileNames().contains("table.org"))
    }

    @Test
    fun `moveNotebook relocates the file into the target directory`() = runTest {
        val vm = seededVm()

        vm.moveNotebook("inbox.org", "work")
        settleUntil { store.exists("work/inbox.org") && !indexedFileNames().contains("inbox.org") }

        assertFalse(store.exists("inbox.org"))
    }

    @Test
    fun `deleteFolder removes every descendant notebook`() = runTest {
        val vm = seededVm()

        vm.deleteFolder("work")
        settleUntil { !store.exists("work/status.org") && !store.exists("work/notes/standup.org") }

        assertTrue("a file outside the folder is untouched", store.exists("inbox.org"))
    }

    @Test
    fun `renameFolder moves every descendant under the new directory`() = runTest {
        val vm = seededVm()

        vm.renameFolder("work", "office")
        settleUntil { store.exists("office/status.org") && store.exists("office/notes/standup.org") }

        assertFalse(store.exists("work/status.org"))
    }

    @Test
    fun `movePin swaps with the visible neighbour and skips a stale pin`() = runTest {
        val vm = seededVm()
        val inbox = PinnedItem(PinKind.FILE, "inbox.org")
        val stale = PinnedItem(PinKind.FILE, "gone.org")
        val work = PinnedItem(PinKind.FOLDER, "work")
        vm.pinNotebook("inbox.org")
        vm.pinNotebook("gone.org")
        vm.pinFolder("work")
        settleUntil { (vm.state.value as? NotebooksUiState.Loaded)?.livePins == listOf(inbox, work) }

        vm.movePin(work, -1)
        settleUntil { settingsRepository.settings.first().pinnedItems == listOf(work, stale, inbox) }

        // Already at the top: no-op.
        settleUntil { (vm.state.value as? NotebooksUiState.Loaded)?.livePins == listOf(work, inbox) }
        vm.movePin(work, -1)
        settleUntil { settingsRepository.settings.first().pinnedItems == listOf(work, stale, inbox) }
    }
}
