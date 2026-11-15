package com.rrajath.grove.ui.vault

import android.app.Application
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.sync.ConflictResolution
import com.rrajath.grove.R
import com.rrajath.grove.sync.ConflictResolveResult
import com.rrajath.grove.sync.conflicts.ConflictSchemes
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.testing.support.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layer-1 integration coverage for [ConflictViewModel]: it pairs the conflict
 * copy name cached on the notebook row with the two file texts the sync layer
 * hands back, and it reacts to a resolution that no longer had anything to write
 * by reloading rather than reporting a false success.
 *
 * The resolution *mechanics* (what KEEP_CURRENT / KEEP_CONFLICT_COPY / KEEP_BOTH
 * write to disk) are pinned by `SyncConflictsTest`; here [FakeSyncTrigger] stands
 * in for that layer so the VM's own branching is what's under test.
 *
 * See internal/test-suite-01-integration-robolectric.md § Coverage matrix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConflictViewModelIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = FakeFileStore(OrgFixtures.withConflict)
    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)

    /** Index the vault and mark `notes.org` as shadowed by its sync-conflict sibling. */
    private suspend fun seedConflictRow() {
        TestVaultSeeder.index(db, store)
        db.indexDao().setConflict(OrgFixtures.CONFLICT_FILE, OrgFixtures.CONFLICT_SIBLING)
    }

    private fun conflictVm(
        sync: FakeSyncTrigger = FakeSyncTrigger(
            onConflictTexts = { name ->
                if (name == OrgFixtures.CONFLICT_FILE) {
                    OrgFixtures.CONFLICT_LOCAL to OrgFixtures.CONFLICT_REMOTE
                } else null
            },
        ),
    ) = ConflictViewModel(database = db, sync = sync)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `load pairs the copy name with both file texts and a formatted label`() = runTest {
        seedConflictRow()
        val vm = conflictVm()

        vm.load(OrgFixtures.CONFLICT_FILE)
        advanceUntilIdle()

        val state = vm.state.value as ConflictUiState.Loaded
        assertEquals(OrgFixtures.CONFLICT_FILE, state.fileName)
        assertEquals(OrgFixtures.CONFLICT_LOCAL, state.currentText)
        assertEquals(OrgFixtures.CONFLICT_REMOTE, state.copyText)
        assertEquals(ConflictSchemes.parse(OrgFixtures.CONFLICT_SIBLING)!!.label, state.copyLabel)
        assertEquals("2026-09-03 12:00", state.copyLabel)
    }

    @Test
    fun `load with no conflict row on the notebook resolves to NoConflict`() = runTest {
        TestVaultSeeder.index(db, store) // indexed, but setConflict never called
        val vm = conflictVm()

        vm.load(OrgFixtures.CONFLICT_FILE)
        advanceUntilIdle()

        assertEquals(ConflictUiState.NoConflict, vm.state.value)
    }

    @Test
    fun `load resolves to NoConflict when the sync layer has no texts to show`() = runTest {
        seedConflictRow()
        // The row still points at a copy, but sync says the copy is already gone.
        val vm = conflictVm(FakeSyncTrigger(onConflictTexts = { null }))

        vm.load(OrgFixtures.CONFLICT_FILE)
        advanceUntilIdle()

        assertEquals(ConflictUiState.NoConflict, vm.state.value)
    }

    @Test
    fun `resolve reports Resolved when the sync layer wrote something`() = runTest {
        seedConflictRow()
        val resolutions = mutableListOf<ConflictResolution>()
        val vm = conflictVm(
            FakeSyncTrigger(
                onConflictTexts = { OrgFixtures.CONFLICT_LOCAL to OrgFixtures.CONFLICT_REMOTE },
                onResolveConflict = { _, resolution -> resolutions += resolution; ConflictResolveResult.Resolved },
            ),
        )
        vm.load(OrgFixtures.CONFLICT_FILE)
        advanceUntilIdle()

        vm.resolve(OrgFixtures.CONFLICT_FILE, ConflictResolution.KEEP_BOTH)
        advanceUntilIdle()

        assertEquals(ConflictUiState.Resolved, vm.state.value)
        assertEquals(listOf(ConflictResolution.KEEP_BOTH), resolutions)
    }

    @Test
    fun `resolve reloads instead of claiming success when nothing was written`() = runTest {
        seedConflictRow()
        val vm = conflictVm(
            FakeSyncTrigger(
                // The copy vanished from under the picker: resolveConflict is a no-op
                // and conflictTexts now returns null, so the reload lands on NoConflict.
                onConflictTexts = { null },
                onResolveConflict = { _, _ -> ConflictResolveResult.NotApplied },
            ),
        )
        vm.load(OrgFixtures.CONFLICT_FILE)
        advanceUntilIdle()

        vm.resolve(OrgFixtures.CONFLICT_FILE, ConflictResolution.KEEP_CURRENT)
        advanceUntilIdle()

        assertEquals(ConflictUiState.NoConflict, vm.state.value)
        assertTrue(vm.state.value !is ConflictUiState.Resolved)
    }

    @Test
    fun `load names the tool that made the copy`() = runTest {
        seedConflictRow()
        val vm = conflictVm()

        vm.load(OrgFixtures.CONFLICT_FILE)
        advanceUntilIdle()

        val state = vm.state.value as ConflictUiState.Loaded
        assertEquals("Syncthing", state.schemeName)
        assertEquals(null, state.copyKeptBy)
    }

    @Test
    fun `load says up front when the provider will keep the copy`() = runTest {
        seedConflictRow()
        val vm = conflictVm(
            FakeSyncTrigger(
                onConflictTexts = { OrgFixtures.CONFLICT_LOCAL to OrgFixtures.CONFLICT_REMOTE },
                onConflictCopyKeptBy = { R.string.storage_provider_seafile },
            ),
        )

        vm.load(OrgFixtures.CONFLICT_FILE)
        advanceUntilIdle()

        assertEquals(R.string.storage_provider_seafile, (vm.state.value as ConflictUiState.Loaded).copyKeptBy)
    }

    @Test
    fun `resolve asks for cleanup when the provider kept the copy`() = runTest {
        seedConflictRow()
        val vm = conflictVm(
            FakeSyncTrigger(
                onConflictTexts = { OrgFixtures.CONFLICT_LOCAL to OrgFixtures.CONFLICT_REMOTE },
                onResolveConflict = { _, _ ->
                    ConflictResolveResult.CopyRemains(OrgFixtures.CONFLICT_SIBLING, R.string.storage_provider_seafile)
                },
            ),
        )
        vm.load(OrgFixtures.CONFLICT_FILE)
        advanceUntilIdle()

        vm.resolve(OrgFixtures.CONFLICT_FILE, ConflictResolution.KEEP_CONFLICT_COPY)
        advanceUntilIdle()

        assertEquals(
            ConflictUiState.CleanupNeeded(OrgFixtures.CONFLICT_SIBLING, R.string.storage_provider_seafile),
            vm.state.value,
        )
    }
}
