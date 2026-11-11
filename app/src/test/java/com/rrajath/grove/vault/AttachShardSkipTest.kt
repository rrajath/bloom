package com.rrajath.grove.vault

import com.rrajath.grove.attach.AttachConfig
import com.rrajath.grove.attach.AttachSkipRoots
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachShardSkipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dataOnly = AttachSkipRoots.DataOnly

    @Test
    fun `shard shapes`() {
        assertTrue(isAttachShard("3F"))
        assertTrue(isAttachShard("ab"))
        assertTrue(isAttachShard("202611"))
        assertTrue(isAttachShard("__"))
        assertFalse(isAttachShard("notes"))
        assertFalse(isAttachShard("2026AB"))
        assertFalse(isAttachShard("a"))
        assertFalse(isAttachShard("2026111"))
    }

    @Test
    fun `only shards directly inside a data folder are skipped`() {
        assertTrue(isSkippedVaultDir("3F", "data", dataOnly))
        assertTrue(isSkippedVaultDir("202611", "Work/data", dataOnly))
        assertTrue(isSkippedVaultDir("__", "data", dataOnly))
        assertFalse(isSkippedVaultDir("notes", "data", dataOnly))
        assertFalse(isSkippedVaultDir("3F", "", dataOnly))
        assertFalse(isSkippedVaultDir("3F", "metadata", dataOnly))
        assertFalse(isSkippedVaultDir("3F", "data/3F", dataOnly))
    }

    @Test
    fun `dot dirs are always skipped, shards only when enabled`() {
        assertTrue(isSkippedVaultDir(".stversions", "", null))
        assertTrue(isSkippedVaultDir(".git", "data", dataOnly))
        assertFalse(isSkippedVaultDir("3F", "data", null))
    }

    @Test
    fun `the configured folder is a root as well as data`() {
        val roots = AttachSkipRoots.of(AttachConfig(idDir = "attach/"))
        assertTrue(isSkippedVaultDir("3F", "attach", roots))
        assertTrue(isSkippedVaultDir("3F", "Work/attach", roots))
        assertTrue(isSkippedVaultDir("3F", "Work/data", roots))
        assertFalse(isSkippedVaultDir("3F", "Work/attachments", roots))
        assertFalse(isSkippedVaultDir("3F", "Work", roots))
    }

    @Test
    fun `a nested folder matches as a trailing path`() {
        val roots = AttachSkipRoots.of(AttachConfig(idDir = "media/files/"))
        assertTrue(isSkippedVaultDir("3F", "media/files", roots))
        assertTrue(isSkippedVaultDir("3F", "Work/media/files", roots))
        assertFalse(isSkippedVaultDir("3F", "Work/files", roots))
        assertFalse(isSkippedVaultDir("3F", "Work/media", roots))
    }

    @Test
    fun `the default config skips only data`() {
        val roots = AttachSkipRoots.of(AttachConfig.Default)
        assertTrue(isSkippedVaultDir("3F", "data", roots))
        assertFalse(isSkippedVaultDir("3F", "attach", roots))
    }

    @Test
    fun `a store built for a configured folder hides its shards`() = runTest {
        fun put(path: String) = File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText("x")
        put("Work/n.org")
        put("Work/attach/3F/ABC/a.jpg")
        put("Work/data/4E/DEF/b.jpg")
        put("Work/attach/notes.org")
        val store = JvmFileStore(tmp.root, attachRoots = AttachSkipRoots.of(AttachConfig(idDir = "attach/")))
        assertEquals(listOf("Work/attach/notes.org", "Work/n.org"), store.list().map { it.name }.sorted())
        val dataOnlyStore = JvmFileStore(tmp.root)
        assertTrue(dataOnlyStore.list().any { it.name == "Work/attach/3F/ABC/a.jpg" })
    }
}
