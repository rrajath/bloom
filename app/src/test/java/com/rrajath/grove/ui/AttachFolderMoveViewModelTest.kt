package com.rrajath.grove.ui

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.capture.SharedPayload
import com.rrajath.grove.data.FavoritesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.search.SearchRepository
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.JvmFileStore
import com.rrajath.grove.vault.Vault
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [AppViewModel]'s offer to move attachment folders next to notes into a new vault-root folder. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, sdk = [34])
class AttachFolderMoveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<GroveApplication>()
    private val sync = FakeSyncTrigger()
    private val db: GroveDatabase = InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val repoScope = CoroutineScope(mainDispatcherRule.dispatcher)
    private val settingsRepository = SettingsRepository(app, repoScope)
    private val liveVms = mutableListOf<AppViewModel>()

    private suspend fun appVm(): AppViewModel {
        settingsRepository.settings.first()
        return AppViewModel(
            settingsRepository = settingsRepository,
            searchRepository = SearchRepository(app),
            favoritesRepository = FavoritesRepository(app),
            database = db,
            sync = sync,
            vaultFlow = MutableStateFlow<Vault?>(Vault(FakeFileStore(emptyMap()))),
            pendingShare = MutableStateFlow<SharedPayload?>(null),
            dispatchers = mainDispatcherRule.appDispatchers,
            app = app,
            attachStore = { JvmFileStore(tmp.root, skipAttachShards = true) },
        ).also { liveVms += it }
    }

    @After
    fun tearDown() {
        liveVms.forEach { it.viewModelScope.cancel() }
        repoScope.cancel()
        db.close()
    }

    private fun put(path: String, text: String = "x") =
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun exists(path: String) = File(tmp.root, path).exists()

    /** DataStore writes run on a real IO thread; drain the scheduler until [condition] holds. */
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
    fun `switching to a vault-root folder offers to move per-note folders, and Move merges them`() = runTest {
        settingsRepository.setAttachFolder("attach/")
        put("projects/attach/BB/22/b.jpg")
        val vm = appVm()

        vm.setAttachFolder("/attach/")
        settleUntil { vm.attachFolderMove.value != null }
        val offer = vm.attachFolderMove.value!!
        assertEquals("attach/", offer.from)
        assertEquals("/attach/", offer.to)
        assertEquals(listOf("projects/attach"), offer.found.roots)
        assertEquals("/attach/", settingsRepository.settings.first().attachFolder)

        vm.moveAttachFolder()
        settleUntil { vm.attachFolderMove.value == null }
        assertTrue(exists("attach/BB/22/b.jpg"))
        assertFalse(exists("projects/attach"))
    }

    @Test
    fun `Keep leaves the files where they are`() = runTest {
        settingsRepository.setAttachFolder("attach/")
        put("projects/attach/BB/22/b.jpg")
        val vm = appVm()
        vm.setAttachFolder("/attach/")
        settleUntil { vm.attachFolderMove.value != null }

        vm.keepAttachFolder()
        assertNull(vm.attachFolderMove.value)
        assertTrue(exists("projects/attach/BB/22/b.jpg"))
    }

    @Test
    fun `no offer without per-note folders or when staying next to notes`() = runTest {
        settingsRepository.setAttachFolder("attach/")
        val vm = appVm()
        vm.setAttachFolder("/attach/")
        settleUntil { settingsRepository.settings.first().attachFolder == "/attach/" }
        advanceUntilIdle()
        assertNull(vm.attachFolderMove.value)

        put("projects/files/BB/22/b.jpg")
        vm.setAttachFolder("files/")
        settleUntil { settingsRepository.settings.first().attachFolder == "files/" }
        advanceUntilIdle()
        assertNull(vm.attachFolderMove.value)
    }
}
