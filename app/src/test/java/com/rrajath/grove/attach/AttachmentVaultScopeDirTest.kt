package com.rrajath.grove.attach

import android.app.Application
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.JvmFileStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The attach sheet's Vault scope, end to end through the real index: owners in
 * other files resolve by `:DIR:` (which wins over `:ID:`), and `:DIR:`-only
 * headings are listed at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AttachmentVaultScopeDirTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val db = InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)

    @After
    fun tearDown() = db.close()

    private fun put(vault: File, path: String, modified: Long) {
        val f = File(vault, path)
        f.parentFile!!.mkdirs()
        f.writeText(path)
        f.setLastModified(modified)
    }

    @Test
    fun `vault scope lists other files' DIR owners resolved by DIR`() = runTest {
        val vault = tmp.newFolder("vault")
        val store = JvmFileStore(vault, skipAttachShards = true)
        store.write("x.org", "* Current\nno owner here\n")
        store.write(
            "notes/y.org",
            """
            :PROPERTIES:
            :DIR: file-files
            :END:
            * Both
            :PROPERTIES:
            :ID: B1
            :DIR: data/photos
            :END:
            * Only dir
            :PROPERTIES:
            :DIR: only-dir
            :END:
            """.trimIndent() + "\n",
        )
        put(vault, "notes/file-files/f.txt", 1_000_000)
        put(vault, "notes/data/photos/both.jpg", 3_000_000)
        put(vault, "notes/only-dir/only.jpg", 2_000_000)
        // The `:ID:` dir for B1: `:DIR:` wins, so this must not be listed.
        put(vault, "notes/data/__/B/B1/decoy.jpg", 4_000_000)
        TestVaultSeeder.index(db, store)

        val files = AttachmentFiles(store)
        val rows = AttachmentScopeScanner(files, AttachDirResolver(ListedDirs(store))).scanVault(
            "x.org",
            OrgParser.parse(store.read("x.org")),
            db.indexDao().attachOwners(),
        )

        assertEquals(
            listOf("notes/data/photos/both.jpg", "notes/only-dir/only.jpg", "notes/file-files/f.txt"),
            rows.map { it.file.name },
        )
        assertEquals(listOf("y.org › Both", "y.org › Only dir", "y.org"), rows.map { it.source })
    }
}
