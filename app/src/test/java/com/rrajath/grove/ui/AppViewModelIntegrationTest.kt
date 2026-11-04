package com.rrajath.grove.ui

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.capture.SharedPayload
import com.rrajath.grove.data.FavoritesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.search.SearchRepository
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.settings.SettingsSerialization
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.TestClockService
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.Vault
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
 * exposed flow.
 *
 * `consumeSharedContent` is still deferred — it delegates to
 * `ShareIntake.consumeShare`, which needs a fully wired `GroveApplication`
 * (real vault + sync). `ShareIntake.composeNote` (the routing rule) is covered
 * by `ShareIntakeTest`.
 *
 * See internal/test-suite-01-integration-robolectric.md § Coverage matrix.
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
    private val settingsRepository = SettingsRepository(app, repoScope)
    private val searchRepository = SearchRepository(app)
    private val favoritesRepository = FavoritesRepository(app)
    private val pendingShare = MutableStateFlow<SharedPayload?>(null)
    private val store = FakeFileStore(OrgFixtures.all)
    private val vaultFlow = MutableStateFlow<Vault?>(Vault(store))

    private val liveVms = mutableListOf<AppViewModel>()

    private fun appVm() = AppViewModel(
        settingsRepository = settingsRepository,
        searchRepository = searchRepository,
        favoritesRepository = favoritesRepository,
        database = db,
        sync = sync,
        vaultFlow = vaultFlow,
        pendingShare = pendingShare,
        dispatchers = mainDispatcherRule.appDispatchers,
        app = app,
        clockService = TestClockService.create(vaultFlow, db, store, settingsRepository, repoScope),
    ).also { liveVms += it }

    @After
    fun tearDown() {
        // See internal/LEARNINGS.md 2026-09-07: an uncancelled viewModelScope
        // leaves eager DataStore collectors parked on a dead test scheduler,
        // backing up the process-wide DataStore actor and flaking a later test.
        liveVms.forEach { it.viewModelScope.cancel() }
        repoScope.cancel()
        db.close()
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
}
