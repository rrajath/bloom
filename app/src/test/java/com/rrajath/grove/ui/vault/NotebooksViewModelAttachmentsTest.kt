package com.rrajath.grove.ui.vault

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.attach.AttachmentLifecycle
import com.rrajath.grove.attach.IdHolders
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.JvmFileStore
import com.rrajath.grove.vault.Vault
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [NotebooksViewModel] with the attachment lifecycle wired in: notebook move
 * (1l), notebook delete (1n) and folder rename/delete over a real directory
 * tree, with shared-ID checks answered by the Room index
 * ([com.rrajath.grove.data.IndexDao.filesHoldingOrgIds]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, sdk = [34])
class NotebooksViewModelAttachmentsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<TestGroveApplication>()
    private val store by lazy { JvmFileStore(tmp.root, skipAttachShards = true) }
    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val repoScope = CoroutineScope(mainDispatcherRule.dispatcher)
    private val settingsRepository = SettingsRepository(app, repoScope)
    private val sync = FakeSyncTrigger()
    private val liveVms = mutableListOf<NotebooksViewModel>()

    private val holders = IdHolders { ids ->
        db.indexDao().filesHoldingOrgIds(ids.toList()).groupBy({ it.orgId }, { it.fileName }).mapValues { it.value.toSet() }
    }

    private fun put(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun exists(path: String) = File(tmp.root, path).exists()

    private fun heading(title: String, id: String) = "* $title\n:PROPERTIES:\n:ID: $id\n:END:\n"

    private suspend fun TestScope.vm(): NotebooksViewModel {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val lifecycle = AttachmentLifecycle(store, holders)
        return NotebooksViewModel(
            vaultFlow = MutableStateFlow(Vault(store, parseDispatcher = mainDispatcherRule.dispatcher)),
            database = db,
            settingsRepository = settingsRepository,
            sync = sync,
            dispatchers = mainDispatcherRule.appDispatchers,
            attachments = { lifecycle },
        ).also { liveVms += it }
    }

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

    @After
    fun tearDown() {
        liveVms.forEach { it.viewModelScope.cancel() }
        repoScope.cancel()
        db.close()
    }

    @Test
    fun `move with attachments relocates owned dirs and copies the one a sibling shares`() = runTest {
        put("Notes/travel.org", heading("Kyoto", "AA11") + heading("Shared", "BB22"))
        put("Notes/other.org", heading("Also shared", "BB22"))
        put("Notes/data/AA/11/a.jpg")
        put("Notes/data/BB/22/b.jpg")
        put("Archive/inbox.org", "")
        val vm = vm()

        val inv = vm.notebookAttachments("Notes/travel.org")
        assertEquals(2, inv.folders)
        assertEquals(1, inv.sharedFolders)

        vm.moveNotebook("Notes/travel.org", "Archive", moveAttachments = true, inventory = inv)
        settleUntil { exists("Archive/data/BB/22/b.jpg") && !exists("Notes/data/AA") }

        assertTrue(exists("Archive/travel.org"))
        assertTrue(exists("Archive/data/AA/11/a.jpg"))
        assertFalse(exists("Notes/data/AA"))
        assertTrue("shared dir is copied", exists("Archive/data/BB/22/b.jpg"))
        assertTrue("shared dir stays for the sibling", exists("Notes/data/BB/22/b.jpg"))
    }

    @Test
    fun `move notebook only leaves the attachments behind`() = runTest {
        put("Notes/travel.org", heading("Kyoto", "AA11"))
        put("Notes/data/AA/11/a.jpg")
        put("Archive/inbox.org", "")
        val vm = vm()
        val inv = vm.notebookAttachments("Notes/travel.org")

        vm.moveNotebook("Notes/travel.org", "Archive", moveAttachments = false, inventory = inv)
        settleUntil { exists("Archive/travel.org") }

        assertTrue(exists("Archive/travel.org"))
        assertTrue(exists("Notes/data/AA/11/a.jpg"))
        assertFalse(exists("Archive/data"))
    }

    @Test
    fun `delete with attachments removes owned dirs and keeps shared ones`() = runTest {
        put("Notes/travel.org", heading("Kyoto", "AA11") + heading("Shared", "BB22"))
        put("Notes/other.org", heading("Also shared", "BB22"))
        put("Notes/data/AA/11/a.jpg")
        put("Notes/data/BB/22/b.jpg")
        val vm = vm()
        val inv = vm.notebookAttachments("Notes/travel.org")

        vm.deleteNotebook("Notes/travel.org", deleteAttachments = true, inventory = inv)
        settleUntil { sync.syncRequests.contains("notebook deleted") }

        assertFalse(exists("Notes/travel.org"))
        assertFalse(exists("Notes/data/AA"))
        assertTrue(exists("Notes/data/BB/22/b.jpg"))
    }

    @Test
    fun `delete without attachments keeps every dir`() = runTest {
        put("Notes/travel.org", heading("Kyoto", "AA11"))
        put("Notes/data/AA/11/a.jpg")
        put("Notes/keep.org", "")
        val vm = vm()
        val inv = vm.notebookAttachments("Notes/travel.org")

        vm.deleteNotebook("Notes/travel.org", deleteAttachments = false, inventory = inv)
        settleUntil { sync.syncRequests.contains("notebook deleted") }

        assertTrue(exists("Notes/data/AA/11/a.jpg"))
    }

    @Test
    fun `folder rename carries data along and folder delete removes it`() = runTest {
        put("Work/status.org", heading("S", "AA11"))
        put("Work/data/AA/11/a.jpg")
        val vm = vm()
        assertTrue(vm.folderHasAttachments("Work"))

        vm.renameFolder("Work", "Jobs")
        settleUntil { exists("Jobs/data/AA/11/a.jpg") && !exists("Work") }
        assertTrue(exists("Jobs/status.org"))
        assertTrue(exists("Jobs/data/AA/11/a.jpg"))
        assertFalse(exists("Work"))

        TestVaultSeeder.index(db, store)
        vm.deleteFolder("Jobs")
        settleUntil { !exists("Jobs") }
        assertFalse(exists("Jobs"))
    }
}
