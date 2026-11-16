package com.rrajath.grove.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.storage.BackendFileStore
import com.rrajath.grove.vault.storage.ProviderProfile
import com.rrajath.grove.vault.storage.ProviderProfiles
import com.rrajath.grove.vault.storage.SafBackend
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BackendFileStore.read] on a provider whose failed download ends the stream
 * normally (Seafile 4.0.15): a read is accepted only when its byte count
 * matches the size a fresh listing states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackendFileStoreVerifiedReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeDocumentsProvider

    private fun store(profile: ProviderProfile = ProviderProfiles.SEAFILE): BackendFileStore {
        provider = FakeDocumentsProvider.install(tmp.root, FakeDocumentsProvider.OPAQUE_AUTHORITY)
        val backend = SafBackend(
            context,
            FakeDocumentsProvider.treeUri(FakeDocumentsProvider.OPAQUE_AUTHORITY),
            listingDeadlineMs = 200,
            profile = profile,
        )
        return BackendFileStore(backend)
    }

    private val text = "* One\n* Two\n"

    @Test
    fun `a download that ends early once is read again`() = runBlocking {
        File(tmp.root, "a.org").writeText(text)
        val store = store()
        store.list()
        provider.cutReads["a.org"] = 1

        assertEquals(text, store.read("a.org"))
    }

    @Test
    fun `a download that keeps ending early fails instead of reading as empty`() = runBlocking {
        File(tmp.root, "a.org").writeText(text)
        val store = store()
        store.list()
        provider.cutReads["a.org"] = 2

        try {
            store.read("a.org")
            fail("an incomplete read was accepted")
        } catch (e: UnverifiedReadException) {
            assertEquals("a.org", e.name)
        }
    }

    @Test
    fun `a download cut partway fails too`() = runBlocking {
        File(tmp.root, "a.org").writeText(text)
        val store = store()
        store.list()
        provider.cutReadBytes = 5
        provider.cutReads["a.org"] = 2

        try {
            store.read("a.org")
            fail("a truncated read was accepted")
        } catch (_: UnverifiedReadException) {
        }
    }

    @Test
    fun `a stale stated size is refreshed from a listing, not refused`() = runBlocking {
        File(tmp.root, "a.org").writeText(text)
        val store = store()
        provider.statFromListing = true
        store.list()
        File(tmp.root, "a.org").writeText("$text* Three\n")

        assertEquals("$text* Three\n", store.read("a.org"))
    }

    @Test
    fun `a genuinely empty file reads as empty`() = runBlocking {
        File(tmp.root, "a.org").writeText("")
        val store = store()
        store.list()

        assertEquals("", store.read("a.org"))
    }

    @Test
    fun `other providers read without the check, as before`() = runBlocking {
        File(tmp.root, "a.org").writeText(text)
        val store = store(ProviderProfiles.DEFAULT)
        store.list()
        provider.cutReads["a.org"] = 1

        assertTrue(store.read("a.org").isEmpty())
    }
}
