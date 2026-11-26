package com.rrajath.grove.capture

import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.R
import com.rrajath.grove.org.NotePlacement
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSettingsRepository
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * [ShareIntake.consumeShare] end to end over fakes: settings gate, target file
 * creation, title lookup, heading routing, ID allocation, reindex and the
 * message shown to the user. The pure note rules ([ShareIntake.composeNote],
 * [ShareIntake.place]) have their own coverage in [ShareIntakeTest].
 * Robolectric only for string resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, sdk = [34])
class ShareIntakeConsumeShareTest {

    private val context = ApplicationProvider.getApplicationContext<TestGroveApplication>()
    private val store = FakeFileStore(mapOf("notes.org" to "#+TITLE: Notes\n* Inbox\n** Old\n* Later\n"))
    private val sync = FakeSyncTrigger()
    private val messages = mutableListOf<String>()
    private val fetchedUrls = mutableListOf<String>()

    private fun deps(
        settings: GroveSettings,
        title: String? = "Fetched title",
    ) = ShareIntake.Deps(
        settings = FakeSettingsRepository(settings),
        vault = MutableStateFlow(Vault(store)),
        sync = sync,
        context = context,
        newOrgId = { "fixed-id" },
        fetchTitle = { url -> fetchedUrls += url; title },
        notify = { messages += it },
        now = { LocalDateTime.of(2026, 11, 24, 9, 30) },
    )

    private val configured = GroveSettings(
        vaultTreeUri = "content://vault/tree",
        shareTargetFile = "notes",
        addCreatedToNewNotes = false,
    )

    @Test
    fun `without a vault nothing is written and the user is told to pick a folder`() = runTest {
        ShareIntake.consumeShare(deps(GroveSettings(vaultTreeUri = null)), SharedPayload(text = "Hi"))

        assertEquals(
            listOf(context.getString(R.string.share_no_vault, context.getString(R.string.app_name))),
            messages,
        )
        assertTrue(sync.reindexCalls.isEmpty())
        assertEquals("#+TITLE: Notes\n* Inbox\n** Old\n* Later\n", store.read("notes.org"))
    }

    @Test
    fun `short text lands as a heading at the bottom of the target file and reindexes it`() = runTest {
        ShareIntake.consumeShare(deps(configured), SharedPayload(text = "Quick thought"))

        val text = store.read("notes.org")
        assertTrue(text, text.trimEnd().endsWith("* Quick thought"))
        assertEquals(listOf(FakeSyncTrigger.ReindexCall("notes.org", text, "shared note")), sync.reindexCalls)
        assertEquals(listOf(context.getString(R.string.share_saved_to, "notes")), messages)
    }

    @Test
    fun `a missing target file is created first`() = runTest {
        ShareIntake.consumeShare(
            deps(configured.copy(shareTargetFile = "fresh")),
            SharedPayload(text = "First"),
        )

        assertTrue(store.read("fresh.org").contains("* First"))
        assertEquals(listOf(context.getString(R.string.share_saved_to, "fresh")), messages)
    }

    @Test
    fun `a URL heading uses the fetched page title`() = runTest {
        ShareIntake.consumeShare(deps(configured), SharedPayload(text = "Sent text", url = "https://example.com"))

        assertEquals(listOf("https://example.com"), fetchedUrls)
        assertTrue(store.read("notes.org").contains("* [[https://example.com][Fetched title]]"))
    }

    @Test
    fun `with title fetching off there is no lookup and the shared text describes the link`() = runTest {
        ShareIntake.consumeShare(
            deps(configured.copy(fetchSharedLinkTitles = false)),
            SharedPayload(text = "Sent text", url = "https://example.com"),
        )

        assertTrue(fetchedUrls.isEmpty())
        assertTrue(store.read("notes.org").contains("* [[https://example.com][Sent text]]"))
    }

    @Test
    fun `a heading target files the note under it at the configured placement`() = runTest {
        ShareIntake.consumeShare(
            deps(configured.copy(shareTargetHeadingPath = "Inbox", shareInsertPlacement = NotePlacement.TOP)),
            SharedPayload(text = "New first child"),
        )

        val lines = store.read("notes.org").lines()
        assertEquals("** New first child", lines[lines.indexOf("* Inbox") + 1])
        assertEquals(listOf(context.getString(R.string.share_saved_to, "notes › Inbox")), messages)
    }

    @Test
    fun `a missing heading falls back to the file and says so`() = runTest {
        ShareIntake.consumeShare(
            deps(configured.copy(shareTargetHeadingPath = "Gone")),
            SharedPayload(text = "Still saved"),
        )

        assertTrue(store.read("notes.org").trimEnd().endsWith("* Still saved"))
        assertEquals(listOf(context.getString(R.string.share_heading_not_found, "Gone", "notes")), messages)
    }

    @Test
    fun `ID and CREATED come from the injected allocator and clock only when enabled`() = runTest {
        ShareIntake.consumeShare(
            deps(configured.copy(addIdToNewNotes = true, addCreatedToNewNotes = true)),
            SharedPayload(text = "Stamped"),
        )
        val stamped = store.read("notes.org")
        assertTrue(stamped, stamped.contains(":ID:") && stamped.contains("fixed-id"))
        assertTrue(stamped, stamped.contains("2026-11-24"))

        ShareIntake.consumeShare(deps(configured), SharedPayload(text = "Plain"))
        val plainTail = store.read("notes.org").substringAfter("* Plain")
        assertFalse(plainTail, plainTail.contains(":PROPERTIES:"))
    }
}
