package com.rrajath.grove.ui

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.capture.ShareIntake
import com.rrajath.grove.capture.SharedPayload
import com.rrajath.grove.data.FavoritesRepository
import com.rrajath.grove.data.FavoriteNote
import com.rrajath.grove.data.NoteEntity
import com.rrajath.grove.data.NotebookEntity
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.search.SearchRepository
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.settings.SettingsSerialization
import com.rrajath.grove.settings.sections
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.Vault
import com.rrajath.grove.testing.support.IsolatedPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Layer-1 integration coverage for [AppViewModel]: the DataStore-backed settings
 * flow, the import-settings success / failure paths, the todo-keyword apply that
 * forces a full re-index, and saved-search mutations streaming back through the
 * exposed flow, and `consumeSharedContent` handing the pending share to
 * `ShareIntake.consumeShare` exactly once. The share routing itself is covered by
 * `ShareIntakeConsumeShareTest` and `ShareIntakeTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, sdk = [34])
class AppViewModelIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val app = ApplicationProvider.getApplicationContext<GroveApplication>()
    private val sync = FakeSyncTrigger()
    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val repoScope = CoroutineScope(mainDispatcherRule.dispatcher)
    private val settingsStore = IsolatedPreferencesDataStore()
    private val settingsRepository = SettingsRepository(app, repoScope, settingsStore.store)
    private val searchRepository = SearchRepository(app)
    private val favoritesRepository = FavoritesRepository(app)
    private val pendingShare = MutableStateFlow<SharedPayload?>(null)
    private val vaultFlow = MutableStateFlow<Vault?>(Vault(FakeFileStore(OrgFixtures.all)))

    private val liveVms = mutableListOf<AppViewModel>()

    /** Each automatic-backup flush the VM asks for, by its force flag. */
    private val flushes = mutableListOf<Boolean>()

    /**
     * The real [SettingsRepository] reads its DataStore on a real IO thread that
     * `advanceUntilIdle()` doesn't wait for, so a VM built before the first read lands
     * can sit on empty state on a slow runner. Awaiting the first value primes the
     * shared replay cache, which the VM then reads through the test scheduler.
     */
    private val shareStore = FakeFileStore(mapOf("inbox.org" to "#+TITLE: Inbox\n"))
    private val shareMessages = mutableListOf<String>()

    /** Share routing over [shareStore], with fake ID, title and toast hooks. */
    private fun shareDeps() = ShareIntake.Deps(
        settings = settingsRepository,
        vault = MutableStateFlow(Vault(shareStore)),
        sync = sync,
        context = app,
        newOrgId = { "fixed-id" },
        fetchTitle = { null },
        notify = { shareMessages += it },
    )

    private suspend fun appVm(
        shareIntake: ShareIntake.Deps = shareDeps(),
        findAutoBackup: suspend (String) -> String? = { null },
    ): AppViewModel {
        settingsRepository.settings.first()
        return AppViewModel(
            settingsRepository = settingsRepository,
            searchRepository = searchRepository,
            favoritesRepository = favoritesRepository,
            database = db,
            sync = sync,
            vaultFlow = vaultFlow,
            pendingShare = pendingShare,
            dispatchers = mainDispatcherRule.appDispatchers,
            app = app,
            shareIntake = shareIntake,
            findAutoBackup = findAutoBackup,
            flushAutoBackup = { force -> flushes += force },
            repairAutoBackup = {},
        ).also { liveVms += it }
    }

    @After
    fun tearDown() {
        // An uncancelled viewModelScope leaves eager collectors parked on a dead
        // test scheduler; the VM still touches process-wide stores (favorites,
        // saved searches), so cancel it before the next test class runs.
        liveVms.forEach { it.viewModelScope.cancel() }
        repoScope.cancel()
        db.close()
        settingsStore.close()
    }

    /**
     * Jetpack DataStore does its file I/O on its own `Dispatchers.IO` scope, off
     * the [TestScope]'s virtual clock, so `advanceUntilIdle()` alone can return
     * before a settings write (and the VM code sequenced after it) has landed.
     * Interleave real-time yields with scheduler drains until [condition] holds.
     */
    /**
     * The UI-facing StateFlows use SharingStarted.WhileSubscribed, so they sit on
     * their seed value until something collects. Park a collector on the test's
     * backgroundScope (auto-cancelled at test end) to make them live.
     */
    private fun TestScope.keepHot(vararg flows: Flow<*>) =
        flows.forEach { f -> backgroundScope.launch { f.collect {} } }

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

    @Test
    fun `settings flow starts null then emits the persisted GroveSettings`() = runTest {
        val vm = appVm()
        assertEquals("Eagerly seeded null before DataStore reads", null, vm.settings.value)

        // DataStore's first read runs on real Dispatchers.IO, off the virtual
        // clock — advanceUntilIdle() alone can return before it lands.
        settleUntil { vm.settings.value != null }

        assertNotNull(vm.settings.value)
        assertEquals(settingsRepository.settings.first(), vm.settings.value)
    }

    @Test
    fun `importSettings with valid JSON applies the imported values`() = runTest {
        val vm = appVm()
        advanceUntilIdle()
        val before = settingsRepository.settings.first().shareTargetFile

        val json = SettingsSerialization.export(GroveSettings(shareTargetFile = "imported-target.org"))
        val uri = android.net.Uri.parse("content://test/valid-settings.json")
        Shadows.shadowOf(app.contentResolver).registerInputStream(uri, json.byteInputStream())

        vm.importSettings(uri)
        settleUntil { settingsRepository.settings.first().shareTargetFile == "imported-target.org" }

        assertEquals("imported-target.org", settingsRepository.settings.first().shareTargetFile)
        assertTrue("precondition: value actually changed", before != "imported-target.org")
    }

    @Test
    fun `importSettings with invalid JSON changes nothing`() = runTest {
        val vm = appVm()
        advanceUntilIdle()
        val before = settingsRepository.settings.first()

        val uri = android.net.Uri.parse("content://test/broken-settings.json")
        Shadows.shadowOf(app.contentResolver)
            .registerInputStream(uri, "{ this is not valid json".byteInputStream())

        vm.importSettings(uri)
        advanceUntilIdle()

        assertEquals(before, settingsRepository.settings.first())
    }

    @Test
    fun `restoreAutoBackup reads the folder's backup file into the import dialog`() = runTest {
        val tree = "content://test/tree/backups"
        val doc = "content://test/tree/backups/document/bloom-settings.json"
        val json = SettingsSerialization.export(
            settings = GroveSettings(shareTargetFile = "restored-target.org"),
            captureTemplates = emptyList(),
            favorites = emptyList(),
            savedSearches = emptyList(),
        )
        Shadows.shadowOf(app.contentResolver)
            .registerInputStream(android.net.Uri.parse(doc), json.byteInputStream())
        settingsRepository.setAutoBackupTreeUri(tree)
        val looked = mutableListOf<String>()
        val vm = appVm(findAutoBackup = { looked += it; doc })
        settleUntil { settingsRepository.settings.first().autoBackupTreeUri == tree }

        vm.restoreAutoBackup()
        settleUntil { vm.pendingImport.value != null }

        assertEquals(listOf(tree), looked)
        assertEquals("restored-target.org", vm.pendingImport.value!!.settings.shareTargetFile)
    }

    @Test
    fun `restoreAutoBackup with no backup file changes nothing`() = runTest {
        settingsRepository.setAutoBackupTreeUri("content://test/tree/empty")
        val vm = appVm(findAutoBackup = { null })
        settleUntil { settingsRepository.settings.first().autoBackupTreeUri != null }
        val before = settingsRepository.settings.first()

        vm.restoreAutoBackup()
        advanceUntilIdle()

        assertEquals(null, vm.pendingImport.value)
        assertEquals(before, settingsRepository.settings.first())
    }

    private val pickedTree = "content://test/tree/picked"
    private val pickedDoc = "content://test/tree/picked/document/bloom-settings.json"

    /** [pickedDoc] reads as [json] on every open (the conflict check and the restore each read it). */
    private fun existingBackup(json: String) {
        Shadows.shadowOf(app.contentResolver)
            .registerInputStreamSupplier(android.net.Uri.parse(pickedDoc)) { json.byteInputStream() }
    }

    private fun otherDeviceBackup() = SettingsSerialization.export(
        settings = GroveSettings(shareTargetFile = "other-device.org"),
        captureTemplates = emptyList(),
        favorites = emptyList(),
        savedSearches = emptyList(),
    )

    @Test
    fun `picking a folder with a different backup asks first and writes nothing`() = runTest {
        existingBackup(otherDeviceBackup())
        val vm = appVm(findAutoBackup = { if (it == pickedTree) pickedDoc else null })
        settleUntil { vm.settings.value != null }

        vm.pickAutoBackupFolder(pickedTree)
        settleUntil { vm.autoBackupConflict.value != null }

        assertEquals(pickedTree, vm.autoBackupConflict.value)
        val s = settingsRepository.current()
        assertEquals(null, s.autoBackupTreeUri)
        assertEquals(false, s.autoBackupEnabled)
        assertTrue(flushes.isEmpty())
    }

    @Test
    fun `picking a folder whose backup matches this device adopts it without asking`() = runTest {
        val vm = appVm(findAutoBackup = { if (it == pickedTree) pickedDoc else null })
        settleUntil { vm.settings.value != null }
        existingBackup(
            SettingsSerialization.export(
                settings = settingsRepository.current(),
                captureTemplates = app.templatesRepository.templates.first(),
                favorites = favoritesRepository.favorites.first(),
                savedSearches = searchRepository.savedSearches.first(),
            )
        )

        vm.pickAutoBackupFolder(pickedTree)
        settleUntil { flushes.isNotEmpty() }

        assertEquals(null, vm.autoBackupConflict.value)
        assertEquals(pickedTree, settingsRepository.current().autoBackupTreeUri)
        assertTrue(settingsRepository.current().autoBackupEnabled)
        assertEquals(listOf(false), flushes)
    }

    @Test
    fun `replace adopts the folder and forces a write`() = runTest {
        existingBackup(otherDeviceBackup())
        val vm = appVm(findAutoBackup = { if (it == pickedTree) pickedDoc else null })
        settleUntil { vm.settings.value != null }
        vm.pickAutoBackupFolder(pickedTree)
        settleUntil { vm.autoBackupConflict.value != null }

        vm.replaceExistingAutoBackup()
        settleUntil { flushes.isNotEmpty() }

        assertEquals(null, vm.autoBackupConflict.value)
        assertTrue(settingsRepository.current().autoBackupEnabled)
        assertEquals(listOf(true), flushes)
    }

    @Test
    fun `cancel leaves everything as it was`() = runTest {
        existingBackup(otherDeviceBackup())
        val vm = appVm(findAutoBackup = { if (it == pickedTree) pickedDoc else null })
        settleUntil { vm.settings.value != null }
        val before = settingsRepository.current()
        vm.pickAutoBackupFolder(pickedTree)
        settleUntil { vm.autoBackupConflict.value != null }

        vm.cancelAutoBackupConflict()
        settleUntil { vm.autoBackupConflict.value == null }

        assertEquals(before, settingsRepository.current())
        assertTrue(flushes.isEmpty())
    }

    @Test
    fun `restore from an existing backup imports first and starts backups only once confirmed`() = runTest {
        existingBackup(otherDeviceBackup())
        val vm = appVm(findAutoBackup = { if (it == pickedTree) pickedDoc else null })
        settleUntil { vm.settings.value != null }
        vm.pickAutoBackupFolder(pickedTree)
        settleUntil { vm.autoBackupConflict.value != null }

        vm.restoreExistingAutoBackup()
        settleUntil { vm.pendingImport.value != null }
        assertEquals(pickedTree, settingsRepository.current().autoBackupTreeUri)
        assertEquals(false, settingsRepository.current().autoBackupEnabled)
        assertTrue(flushes.isEmpty())

        vm.confirmImport(vm.pendingImport.value!!.sections().toSet())
        settleUntil { settingsRepository.current().autoBackupEnabled }
        assertEquals("other-device.org", settingsRepository.current().shareTargetFile)
        assertEquals(listOf(true), flushes)
    }

    @Test
    fun `dismissing the restore checklist keeps backups off`() = runTest {
        existingBackup(otherDeviceBackup())
        val vm = appVm(findAutoBackup = { if (it == pickedTree) pickedDoc else null })
        settleUntil { vm.settings.value != null }
        vm.pickAutoBackupFolder(pickedTree)
        settleUntil { vm.autoBackupConflict.value != null }
        vm.restoreExistingAutoBackup()
        settleUntil { vm.pendingImport.value != null }

        vm.cancelImport()
        advanceUntilIdle()

        assertEquals(false, settingsRepository.current().autoBackupEnabled)
        assertTrue(flushes.isEmpty())
    }

    @Test
    fun `turning backup on with a remembered folder holding a different backup asks first`() = runTest {
        existingBackup(otherDeviceBackup())
        settingsRepository.setAutoBackupTreeUri(pickedTree)
        val vm = appVm(findAutoBackup = { if (it == pickedTree) pickedDoc else null })
        settleUntil { vm.settings.value != null }

        vm.setAutoBackupEnabled(true)
        settleUntil { vm.autoBackupConflict.value != null }

        assertEquals(false, settingsRepository.current().autoBackupEnabled)
        assertTrue(flushes.isEmpty())
    }

    @Test
    fun `setTodoKeywords forces a full clear-and-resync`() = runTest {
        val vm = appVm()
        advanceUntilIdle()

        vm.setTodoKeywords("TODO NEXT | DONE")
        settleUntil { sync.clearAndResyncRequests.contains("todo keywords applied") }

        assertTrue(sync.clearAndResyncRequests.contains("todo keywords applied"))
    }

    @Test
    fun `deleteSavedSearch removes it from the exposed flow`() = runTest {
        searchRepository.saveSearch("Temporary", "i.TODO")
        val vm = appVm()
        keepHot(vm.savedSearches)
        settleUntil { vm.savedSearches.value.any { it.name == "Temporary" } }
        val id = vm.savedSearches.value.single { it.name == "Temporary" }.id

        vm.deleteSavedSearch(id)
        settleUntil { vm.savedSearches.value.none { it.id == id } }

        assertTrue(vm.savedSearches.value.none { it.id == id })
    }

    @Test
    fun `addFavorite then removeFavorite round-trips through the favorites flow`() = runTest {
        val vm = appVm()
        keepHot(vm.favorites)
        advanceUntilIdle()

        vm.addFavorite("projects.org", lineIndex = 2, title = "Ship v2 release", customId = null)
        settleUntil { vm.favorites.value.any { it.title == "Ship v2 release" } }
        assertTrue(vm.favorites.value.any { it.title == "Ship v2 release" })

        vm.removeFavorite("projects.org", lineIndex = 2, customId = null)
        settleUntil { vm.favorites.value.none { it.title == "Ship v2 release" } }
        assertTrue(vm.favorites.value.none { it.title == "Ship v2 release" })
    }

    @Test
    fun `favorites replaced by an import are re-pointed by heading id`() = runTest {
        val dao = db.indexDao()
        dao.insertNotebook(NotebookEntity("new/home.org", "r1", 1, 0L, null))
        dao.insertNotes(
            listOf(
                NoteEntity(
                    fileName = "new/home.org", lineIndex = 7, level = 1, title = "Plan",
                    keyword = null, priority = null, tags = "", inheritedTags = "",
                    scheduled = null, deadline = null, closed = null, activeTimestamps = null,
                    orgId = "fav-id", customId = null, createdAt = null, body = "",
                    isDone = false, lastModified = 0L,
                ),
            ),
        )
        val vm = appVm()
        keepHot(vm.favorites)
        advanceUntilIdle()

        // What confirmImport does for a favorites section: the file set is unchanged, only
        // the favorites flow emits, and its path comes from another device's vault.
        favoritesRepository.replaceAll(listOf(FavoriteNote("old/home.org", 3, "Plan", "fav-id")))
        settleUntil { vm.favorites.value.singleOrNull()?.fileName == "new/home.org" }
        assertEquals(7, vm.favorites.value.single().lineIndex)
    }

    @Test
    fun `consumeSharedContent with nothing pending writes nothing`() = runTest {
        val vm = appVm()

        vm.consumeSharedContent()
        advanceUntilIdle()

        assertTrue(sync.reindexCalls.isEmpty())
        assertTrue(shareMessages.isEmpty())
    }

    @Test
    fun `consumeSharedContent routes the pending share once and clears it`() = runTest {
        settingsRepository.setVaultTreeUri("content://vault/tree")
        settleUntil { settingsRepository.settings.first().vaultTreeUri != null }
        val vm = appVm()
        pendingShare.value = SharedPayload(text = "Shared while open")

        vm.consumeSharedContent()
        settleUntil { shareMessages.isNotEmpty() }

        assertEquals(null, pendingShare.value)
        assertTrue(shareStore.read("inbox.org").contains("* Shared while open"))
        assertEquals(listOf("inbox.org"), sync.reindexCalls.map { it.fileName })
        assertEquals(listOf(app.getString(com.rrajath.grove.R.string.share_saved_to, "inbox")), shareMessages)

        // A second call has nothing left to consume.
        vm.consumeSharedContent()
        advanceUntilIdle()
        assertEquals(1, sync.reindexCalls.size)
    }
}
