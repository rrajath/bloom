package com.rrajath.grove.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.storage.Listing
import com.rrajath.grove.vault.storage.ProviderProfile
import com.rrajath.grove.vault.storage.ProviderProfiles
import com.rrajath.grove.vault.storage.SafBackend
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SafBackend.listChildren] on a provider whose listing refresh isn't atomic
 * (Seafile 4.0.15 deletes a folder's cached entries, then inserts the fresh
 * ones): overlapping listings of one folder share a single query, and a
 * listing that loses names the last one had is confirmed by a second one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SafBackendNonAtomicListingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeDocumentsProvider

    private fun backend(profile: ProviderProfile = ProviderProfiles.SEAFILE): SafBackend {
        provider = FakeDocumentsProvider.install(tmp.root, FakeDocumentsProvider.OPAQUE_AUTHORITY)
        return SafBackend(
            context,
            FakeDocumentsProvider.treeUri(FakeDocumentsProvider.OPAQUE_AUTHORITY),
            listingDeadlineMs = 2_000,
            profile = profile,
        )
    }

    private fun names(listing: Listing) = listing.entries.map { it.name }.toSet()

    @Test
    fun `a listing that drops known files is confirmed by a second one`() = runBlocking {
        File(tmp.root, "a.org").writeText("* A\n")
        File(tmp.root, "b.org").writeText("* B\n")
        val backend = backend()
        val root = backend.rootId
        assertEquals(setOf("a.org", "b.org"), names(backend.listChildren(root)))

        provider.dropFromNextListing += "a.org"
        val listing = backend.listChildren(root)

        assertTrue(listing is Listing.Complete)
        assertEquals(setOf("a.org", "b.org"), names(listing))
    }

    @Test
    fun `a file really deleted elsewhere is still dropped`() = runBlocking {
        File(tmp.root, "a.org").writeText("* A\n")
        File(tmp.root, "b.org").writeText("* B\n")
        val backend = backend()
        val root = backend.rootId
        backend.listChildren(root)

        File(tmp.root, "a.org").delete()

        assertEquals(setOf("b.org"), names(backend.listChildren(root)))
    }

    @Test
    fun `overlapping listings of one folder share a single query`() = runBlocking {
        File(tmp.root, "a.org").writeText("* A\n")
        val backend = backend()
        val root = backend.rootId
        provider.childrenQueryDelayMs = 300

        val results = (1..4).map { async(Dispatchers.IO) { backend.listChildren(root) } }.awaitAll()

        assertEquals(1, provider.childrenQueries.get())
        assertTrue(results.all { names(it) == setOf("a.org") })
    }

    @Test
    fun `other providers list every time, as before`() = runBlocking {
        File(tmp.root, "a.org").writeText("* A\n")
        val backend = backend(ProviderProfiles.DEFAULT)
        val root = backend.rootId
        backend.listChildren(root)

        provider.dropFromNextListing += "a.org"

        assertEquals(emptySet<String>(), names(backend.listChildren(root)))
    }
}
