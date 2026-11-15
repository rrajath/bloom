package com.rrajath.grove.vault

import com.rrajath.grove.testing.FakeFileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Vault] on a provider whose mtime and size lag its writes (Seafile answers a
 * stat from a listing cache the upload doesn't refresh), so a save can leave a
 * file's revision marker unchanged, and the marker can move later on its own.
 */
class VaultLaggingMetadataTest {

    /** [FakeFileStore] whose stat can be frozen, and whose reads can be held mid-way. */
    private class LaggingStore(val inner: FakeFileStore) : FileStore by inner {
        var frozenStat: FileEntry? = null
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun stat(name: String): FileEntry? = frozenStat ?: inner.stat(name)

        override suspend fun read(name: String): String {
            // The text is taken before the hold: what was on disk when the read started.
            val text = inner.read(name)
            readGate?.await()
            return text
        }
    }

    @Test
    fun `hasContent matches the text a hash was taken of`() = runTest {
        val store = FakeFileStore(mapOf("a.org" to "* One\n"))
        val vault = Vault(store)

        assertTrue(vault.hasContent("a.org", contentHash("* One\n")))
        assertFalse(vault.hasContent("a.org", contentHash("* Two\n")))
        assertFalse(vault.hasContent("a.org", null))
        assertFalse(vault.hasContent("missing.org", contentHash("* One\n")))
    }

    @Test
    fun `a parse that started before a save isn't served after it under an unchanged marker`() = runTest {
        val store = LaggingStore(FakeFileStore(mapOf("a.org" to "* Old\n")))
        val vault = Vault(store, parseDispatcher = Dispatchers.Unconfined)
        store.frozenStat = store.inner.stat("a.org")
        val gate = CompletableDeferred<Unit>()
        store.readGate = gate

        val early = async { vault.open("a.org") }
        yield()
        store.readGate = null
        vault.save("a.org", "* New\n")
        gate.complete(Unit)

        assertEquals("* Old", early.await()!!.headlines.single().let { "* " + it.title })
        assertEquals("New", vault.open("a.org")!!.headlines.single().title)
    }
}
