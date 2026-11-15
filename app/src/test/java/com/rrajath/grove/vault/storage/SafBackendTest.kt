package com.rrajath.grove.vault.storage

import android.app.Application
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.FakeDocumentsProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SafBackendTest : StorageBackendContract() {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val provider: FakeDocumentsProvider by lazy { FakeDocumentsProvider.install(tmp.root) }

    override fun newBackend(listingDeadlineMs: Long) =
        SafBackend(context, FakeDocumentsProvider.treeUri(), listingDeadlineMs)

    override fun seedFile(path: String, text: String) {
        File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText(text)
        provider // install
    }

    override fun seedDir(path: String) {
        File(tmp.root, path).mkdirs()
        provider // install
    }

    override fun dirId(path: String) =
        if (path.isEmpty()) FakeDocumentsProvider.ROOT_ID else "${FakeDocumentsProvider.ROOT_ID}/$path"

    override fun markLoading(path: String) {
        provider.loadingDirs.add(dirId(path))
    }

    override fun finishLoadingSoon(path: String, afterMs: Long) {
        provider.finishLoadingLater(dirId(path), afterMs)
    }

    override fun markError(path: String) {
        provider.errorDirs.add(dirId(path))
    }

    private fun observersOn(docId: String) = shadowOf(context.contentResolver).let { shadow ->
        listOf(
            DocumentsContract.buildChildDocumentsUriUsingTree(FakeDocumentsProvider.treeUri(), docId),
            DocumentsContract.buildChildDocumentsUri(FakeDocumentsProvider.AUTHORITY, docId),
        ).sumOf { shadow.getContentObservers(it).size }
    }

    @Test
    fun `a provider that notifies on the out-of-tree children uri still wakes the wait`() = runBlocking {
        seedFile("a.org")
        provider.notifyOutOfTree = true
        markLoading("")
        finishLoadingSoon("", afterMs = 100)

        val listing = newBackend().listChildren(dirId(""))

        assertTrue("got $listing", listing is Listing.Complete)
        assertEquals(listOf("a.org"), listing.entries.map { it.name })
    }

    @Test
    fun `a notification that lands before the wait starts is not lost`() = runBlocking {
        seedFile("a.org")
        markLoading("")
        // Finishes immediately: possibly before the backend registers its observer.
        finishLoadingSoon("", afterMs = 0)

        val listing = newBackend().listChildren(dirId(""))

        assertTrue("got $listing", listing is Listing.Complete)
    }

    @Test
    fun `observers are unregistered after complete, timeout and cancellation`() = runBlocking {
        seedFile("a.org")
        val root = dirId("")

        markLoading("")
        finishLoadingSoon("", afterMs = 50)
        newBackend().listChildren(root)
        assertEquals("after complete", 0, observersOn(root))

        markLoading("")
        newBackend(listingDeadlineMs = 100).listChildren(root)
        assertEquals("after timeout", 0, observersOn(root))

        val waiting = async(Dispatchers.Default) { newBackend(listingDeadlineMs = 10_000).listChildren(root) }
        // Wait until the backend is really parked on the loading directory.
        withContext(Dispatchers.IO) {
            val until = System.currentTimeMillis() + 5_000
            while (observersOn(root) == 0 && System.currentTimeMillis() < until) Thread.sleep(10)
        }
        assertTrue("observer should be registered while waiting", observersOn(root) > 0)
        waiting.cancelAndJoin()
        assertEquals("after cancellation", 0, observersOn(root))
    }
}
