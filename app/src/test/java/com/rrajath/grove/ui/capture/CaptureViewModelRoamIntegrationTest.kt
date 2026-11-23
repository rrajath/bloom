package com.rrajath.grove.ui.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.R
import com.rrajath.grove.capture.CaptureContext
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.DefaultTemplates
import com.rrajath.grove.capture.TargetLocation
import com.rrajath.grove.capture.TemplateKind
import com.rrajath.grove.capture.TemplatesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSettingsRepository
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.ui.util.UiText
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Layer-1 integration coverage for [CaptureViewModel]'s Roam node path
 * (saveRoam/autosaveRoam/discardRoamDraft): a brand-new resolved path is
 * written verbatim, an already-existing one gets only its body appended, and
 * discard cleans up whichever of those this session actually did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CaptureViewModelRoamIntegrationTest {

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

    private val context = CaptureContext(now = LocalDateTime.of(2026, 9, 6, 10, 0), id = "TEST-ID")

    private fun capture() = CaptureViewModel(
        templatesRepository = templatesRepository,
        database = db,
        sync = sync,
        vaultFlow = vaultFlow,
        settings = settings,
        dispatchers = mainDispatcherRule.appDispatchers,
    )

    @After
    fun tearDown() {
        db.close()
    }

    // The capture editor never shows the :ID: drawer; saveRoam adds it.
    private val newNodeDraft = "#+title: My New Node\n"

    @Test
    fun `saveRoam writes a brand-new resolved path with an ID drawer on top`() = runTest {
        val vm = capture()

        vm.saveRoam("roam/my-new-node.org", newNodeDraft, context)
        advanceUntilIdle()

        assertEquals(SaveState.Saved("roam/my-new-node.org"), vm.saveState.value)
        assertTrue(store.exists("roam/my-new-node.org"))
        assertEquals(":PROPERTIES:\n:ID: TEST-ID\n:END:\n$newNodeDraft", store.read("roam/my-new-node.org"))
        assertEquals(listOf("capture saved"), sync.reindexCalls.map { it.reason })
    }

    @Test
    fun `a second autosave to the same not-yet-saved path replaces its whole content`() = runTest {
        val vm = capture()

        vm.autosaveRoam("roam/my-new-node.org", "#+title: %?", context)
        advanceUntilIdle()
        vm.autosaveRoam("roam/my-new-node.org", "#+title: My New Node", context)
        advanceUntilIdle()

        val text = store.read("roam/my-new-node.org")
        assertEquals(":PROPERTIES:\n:ID: TEST-ID\n:END:\n#+title: My New Node", text)
    }

    @Test
    fun `a template's own ID is replaced, other drawer properties kept`() = runTest {
        val vm = capture()

        vm.saveRoam("roam/n.org", ":PROPERTIES:\n:ID: stale\n:ROAM_ALIASES: n\n:END:\n#+title: N", context)
        advanceUntilIdle()

        assertEquals(":PROPERTIES:\n:ID: TEST-ID\n:ROAM_ALIASES: n\n:END:\n#+title: N", store.read("roam/n.org"))
    }

    @Test
    fun `capture into an existing file without an ID adds one, and keeps an existing one`() = runTest {
        val vm = capture()
        store.write("roam/a.org", "#+title: A\n")
        store.write("roam/b.org", ":PROPERTIES:\n:ID: KEEP\n:END:\n#+title: B\n")

        vm.saveRoam("roam/a.org", "#+title: A\n\nmore", context)
        advanceUntilIdle()
        vm.saveRoam("roam/b.org", "#+title: B\n\nmore", context)
        advanceUntilIdle()

        assertTrue(store.read("roam/a.org").startsWith(":PROPERTIES:\n:ID: TEST-ID\n:END:\n#+title: A"))
        val b = store.read("roam/b.org")
        assertTrue(b.startsWith(":PROPERTIES:\n:ID: KEEP\n:END:\n#+title: B"))
        assertFalse(b.contains("TEST-ID"))
    }

    @Test
    fun `second capture into an already-existing daily note appends only the body`() = runTest {
        val vm = capture()
        val dailyPath = "roam/2026-09-06.org"
        store.write(dailyPath, "#+title: 2026-09-06\n\n* First entry\nFirst body\n")

        vm.saveRoam(dailyPath, "#+title: irrelevant\n\n* Second entry\nSecond body", context)
        advanceUntilIdle()

        val text = store.read(dailyPath)
        assertEquals(SaveState.Saved(dailyPath), vm.saveState.value)
        // The pre-existing head (title) is untouched — not duplicated or overwritten.
        assertEquals(1, Regex("#\\+title:").findAll(text).count())
        assertTrue(text.contains("* First entry"))
        assertTrue(text.contains("* Second entry"))
        // The typed title text never appears: it belongs to the head, which was stripped.
        assertFalse(text.contains("irrelevant"))
    }

    @Test
    fun `a second autosave into an existing file replaces the first appended draft in place`() = runTest {
        val vm = capture()
        val dailyPath = "roam/2026-09-06.org"
        store.write(dailyPath, "#+title: 2026-09-06\n\n")

        vm.autosaveRoam(dailyPath, "#+title: x\n\n* Draft one", context)
        advanceUntilIdle()
        vm.autosaveRoam(dailyPath, "#+title: x\n\n* Draft two", context)
        advanceUntilIdle()

        val text = store.read(dailyPath)
        assertTrue(text.contains("Draft two"))
        assertFalse("the first draft must have been stripped", text.contains("Draft one"))
    }

    @Test
    fun `discardRoamDraft deletes a freshly-created file outright`() = runTest {
        val vm = capture()

        vm.autosaveRoam("roam/my-new-node.org", newNodeDraft, context)
        advanceUntilIdle()
        assertTrue(store.exists("roam/my-new-node.org"))

        vm.discardRoamDraft("roam/my-new-node.org")
        advanceUntilIdle()

        assertFalse(store.exists("roam/my-new-node.org"))
    }

    @Test
    fun `discardRoamDraft on an appended entry strips just the insertion`() = runTest {
        val vm = capture()
        val dailyPath = "roam/2026-09-06.org"
        store.write(dailyPath, "#+title: 2026-09-06\n\n")

        vm.autosaveRoam(dailyPath, "#+title: x\n\n* Abandoned draft", context)
        advanceUntilIdle()
        assertTrue(store.read(dailyPath).contains("Abandoned draft"))

        vm.discardRoamDraft(dailyPath)
        advanceUntilIdle()

        val text = store.read(dailyPath)
        assertFalse(text.contains("Abandoned draft"))
        assertTrue("the pre-existing file itself must survive", store.exists(dailyPath))
        assertTrue(sync.reindexCalls.any { it.reason == "capture discarded" })
    }

    @Test
    fun `blank draft fails without touching the vault`() = runTest {
        val vm = capture()
        val before = store.snapshot()

        vm.saveRoam("roam/my-new-node.org", "   ", context)
        advanceUntilIdle()

        assertEquals(SaveState.Failed(UiText.res(R.string.capture_nothing_to_save)), vm.saveState.value)
        assertEquals(before, store.snapshot())
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
            val vm = capture()
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
