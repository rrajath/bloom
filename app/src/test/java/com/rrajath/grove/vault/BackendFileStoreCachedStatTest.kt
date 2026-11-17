package com.rrajath.grove.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.storage.BackendFileStore
import com.rrajath.grove.vault.storage.ProviderProfile
import com.rrajath.grove.vault.storage.ProviderProfiles
import com.rrajath.grove.vault.storage.SafBackend
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.isOk
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BackendFileStore] on a provider whose stat answers from its cached folder
 * listing (Seafile): a conditional write lists the folder first, so an edit
 * made on the server since isn't overwritten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackendFileStoreCachedStatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private lateinit var provider: FakeDocumentsProvider

    private fun store(profile: ProviderProfile): BackendFileStore {
        provider = FakeDocumentsProvider.install(tmp.root, FakeDocumentsProvider.OPAQUE_AUTHORITY)
        provider.statFromListing = true
        val backend = SafBackend(
            context,
            FakeDocumentsProvider.treeUri(FakeDocumentsProvider.OPAQUE_AUTHORITY),
            listingDeadlineMs = 200,
            profile = profile,
        )
        return BackendFileStore(backend)
    }

    private fun file(path: String) = File(tmp.root, path)

    /** Edits [path] as another device would: new text and a later mtime, behind the provider's cache. */
    private fun editElsewhere(path: String, text: String) {
        val f = file(path)
        val mtime = f.lastModified()
        f.writeText(text)
        f.setLastModified(mtime + 60_000)
    }

    private suspend fun revisionOf(store: BackendFileStore, name: String): String =
        store.stat(name)!!.let { "${it.lastModified}:${it.size}" }

    @Test
    fun `a conditional write sees an edit made on the server since the last listing`() = runTest {
        file("notes").mkdirs()
        file("notes/a.org").writeText("* One\n")
        val store = store(ProviderProfiles.SEAFILE)
        store.list()
        val loaded = revisionOf(store, "notes/a.org")

        editElsewhere("notes/a.org", "* One\n* From the web\n")
        val written = store.write("notes/a.org", "* One\n* From Bloom\n", ifMatch = loaded)

        assertTrue("expected a conflict, got $written", written is StorageError.Conflict)
        assertEquals("* One\n* From the web\n", file("notes/a.org").readText())
    }

    @Test
    fun `a conditional write with nothing changed elsewhere still writes`() = runTest {
        file("a.org").writeText("* One\n")
        val store = store(ProviderProfiles.SEAFILE)
        store.list()
        val loaded = revisionOf(store, "a.org")

        assertTrue(store.write("a.org", "* One\n* From Bloom\n", ifMatch = loaded).isOk)
        assertEquals("* One\n* From Bloom\n", file("a.org").readText())
    }

    @Test
    fun `a conditional write is refused when the folder never finishes loading`() = runTest {
        file("notes").mkdirs()
        file("notes/a.org").writeText("* One\n")
        val store = store(ProviderProfiles.SEAFILE)
        store.list()
        val loaded = revisionOf(store, "notes/a.org")

        // Server hung: the provider answers "loading" and never finishes.
        provider.loadingDirs.add("${FakeDocumentsProvider.ROOT_ID}/notes")
        val written = store.write("notes/a.org", "* DONE One\n", ifMatch = loaded)

        assertEquals(StorageError.Offline, written)
        assertEquals("* One\n", file("notes/a.org").readText())
    }

    @Test
    fun `a conditional write is refused when the folder listing fails`() = runTest {
        file("a.org").writeText("* One\n")
        val store = store(ProviderProfiles.SEAFILE)
        store.list()
        val loaded = revisionOf(store, "a.org")

        // No network: the provider reports an error for the listing.
        provider.errorDirs.add(FakeDocumentsProvider.ROOT_ID)
        val written = store.write("a.org", "* DONE One\n", ifMatch = loaded)

        assertEquals(StorageError.Offline, written)
        assertEquals("* One\n", file("a.org").readText())
    }

    @Test
    fun `a write refused as offline is reported, so the app can show it at once`() = runTest {
        file("a.org").writeText("* One\n")
        val store = store(ProviderProfiles.SEAFILE)
        store.list()
        val loaded = revisionOf(store, "a.org")
        val reports = async(start = CoroutineStart.UNDISPATCHED) { store.unreachable.first() }

        provider.errorDirs.add(FakeDocumentsProvider.ROOT_ID)
        store.write("a.org", "* DONE One\n", ifMatch = loaded)

        assertTrue(withTimeout(5_000) { reports.await() }.isNotBlank())
    }
}
