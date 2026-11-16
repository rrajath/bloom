package com.rrajath.grove.ui.capture

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.TargetLocation
import com.rrajath.grove.capture.TemplatesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings > Capture Templates list: a template saved from the editor shows up
 * on the list as soon as the editor pops, without leaving and re-entering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TemplatesListRefreshTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val repo = TemplatesRepository(ApplicationProvider.getApplicationContext<Application>())
    private val vaultFlow = MutableStateFlow<Vault?>(null)

    private fun vm() = TemplatesViewModel(repo, db, vaultFlow, mainDispatcherRule.appDispatchers)

    @After
    fun tearDown() {
        db.close()
    }

    /** Pumps the test scheduler while DataStore's own IO threads finish. */
    private fun TestScope.pumpUntil(timeoutMs: Long = 3_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            runCurrent()
            if (done()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun savedTemplateAppearsOnListAfterEditorPops() = runTest(mainDispatcherRule.dispatcher) {
        val list = vm()
        val collectorScope = CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        var job = collectorScope.launch { list.templates.collect {} }
        pumpUntil { list.templates.value.isNotEmpty() }

        // List goes to the back stack while the editor is open (> 5 s stop timeout).
        job.cancel()
        advanceTimeBy(6_000); runCurrent()

        // Editor: Save, then pop right away (its ViewModel is cleared).
        val editorStore = ViewModelStore()
        val editor = vm().also { editorStore.put("editor", it) }
        val new = CaptureTemplate(
            id = "t-new", name = "New one", targetFile = "inbox.org",
            location = TargetLocation.BottomOfFile, template = "* %cursor",
        )
        editor.upsert(new)
        runCurrent()
        // List is visible again as the pop transition starts; editor cleared when it ends.
        job = collectorScope.launch { list.templates.collect {} }
        pumpUntil(300) { false }
        editorStore.clear()
        pumpUntil { list.templates.value.any { it.id == "t-new" } }

        val onDisk = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.templates.first() } }
        assertTrue(
            "new template missing from list: ${list.templates.value.map { it.id }} disk=${onDisk.map { it.id }}",
            list.templates.value.any { it.id == "t-new" },
        )
        job.cancel()
        collectorScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }
}
