package com.rrajath.grove.attach

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachDirResolverTest {

    private val uuid = "8B41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C"

    private class FakeDirs(vararg existing: String) : DirExists {
        val existing = existing.toMutableSet()
        val probes = mutableListOf<String>()
        override suspend fun exists(vaultDir: String): Boolean {
            probes += vaultDir
            return vaultDir in existing
        }
    }

    private fun heading(id: String? = null, dir: String? = null) =
        AttachOwner.Heading(lineIndex = 0, titlePath = listOf("H"), id = id, dirProperty = dir)

    // --- IdPathFormat (org-attach.el lines 169-206) ---

    @Test fun `uuid format splits after two chars and needs more than two`() {
        assertEquals("8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C", IdPathFormat.Uuid.path(uuid))
        assertEquals("ab/c", IdPathFormat.Uuid.path("abc"))
        assertNull(IdPathFormat.Uuid.path("ab"))
    }

    @Test fun `ts format splits after six chars and needs more than six`() {
        assertEquals("202611/02T125201.123456", IdPathFormat.Ts.path("20261102T125201.123456"))
        assertEquals("abcdef/g", IdPathFormat.Ts.path("abcdefg"))
        assertNull(IdPathFormat.Ts.path("abcdef"))
    }

    @Test fun `fallback format is double underscore then first char then id`() {
        assertEquals("__/a/a", IdPathFormat.Fallback.path("a"))
        assertEquals("__/8/8B41", IdPathFormat.Fallback.path("8B41"))
        assertNull(IdPathFormat.Fallback.path(""))
    }

    // --- resolution ---

    @Test fun `new uuid id resolves under data next to the org file`() = runBlocking {
        val r = AttachDirResolver(FakeDirs(), AttachConfig.Default)
        assertNull(r.readDir("notes/travel.org", heading(uuid)))
        assertEquals("notes/data/8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C", r.writeDir("notes/travel.org", heading(uuid)))
    }

    @Test fun `org file at vault root resolves to a root data dir`() = runBlocking {
        assertEquals("data/8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C", AttachDirResolver(FakeDirs(), AttachConfig.Default).writeDir("inbox.org", heading(uuid)))
    }

    @Test fun `id case is never changed`() = runBlocking {
        val lower = uuid.lowercase()
        assertEquals("data/8b/${lower.drop(2)}", AttachDirResolver(FakeDirs(), AttachConfig.Default).writeDir("a.org", heading(lower)))
    }

    @Test fun `first existing format wins over earlier non-existing ones`() = runBlocking {
        val ts = "20261102T125201.123456"
        val dirs = FakeDirs("data/202611/02T125201.123456")
        assertEquals("data/202611/02T125201.123456", AttachDirResolver(dirs, AttachConfig.Default).readDir("a.org", heading(ts)))
        assertEquals("data/202611/02T125201.123456", AttachDirResolver(dirs, AttachConfig.Default).writeDir("a.org", heading(ts)))
    }

    @Test fun `read stats at most one dir per candidate and stops at the first hit`() = runBlocking {
        val dirs = FakeDirs("data/8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C")
        AttachDirResolver(dirs, AttachConfig.Default).readDir("a.org", heading(uuid))
        assertEquals(1, dirs.probes.size)
        val none = FakeDirs()
        AttachDirResolver(none, AttachConfig.Default).readDir("a.org", heading(uuid))
        assertEquals(3, none.probes.size)
    }

    @Test fun `custom id dir falls back to data per format`() = runBlocking {
        val config = AttachConfig(idDir = "attach/")
        val dirs = FakeDirs("notes/data/8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C")
        val r = AttachDirResolver(dirs, config)
        assertEquals("notes/data/8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C", r.readDir("notes/x.org", heading(uuid)))
        assertEquals("notes/attach/8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C", AttachDirResolver(FakeDirs(), config).writeDir("notes/x.org", heading(uuid)))
    }

    @Test fun `vault rooted id dir ignores the org file directory`() = runBlocking {
        val r = AttachDirResolver(FakeDirs(), AttachConfig(idDir = "/attachments/"))
        assertEquals("attachments/8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C", r.writeDir("deep/nested/x.org", heading(uuid)))
    }

    @Test fun `no id and no dir has no attach dir`() = runBlocking {
        val r = AttachDirResolver(FakeDirs(), AttachConfig.Default)
        assertNull(r.writeDir("a.org", heading()))
        assertTrue(r.candidates("a.org", heading()).isEmpty())
    }

    @Test fun `dir property wins over id and resolves against the org file dir`() = runBlocking {
        val r = AttachDirResolver(FakeDirs(), AttachConfig.Default)
        assertEquals("notes/photos/kyoto", r.writeDir("notes/travel.org", heading(uuid, "photos/kyoto/")))
        assertEquals("Photos/kyoto", r.writeDir("notes/travel.org", heading(uuid, "../Photos/kyoto")))
        assertEquals(listOf("Photos/kyoto"), r.candidates("notes/travel.org", heading(uuid, "../Photos/kyoto")))
    }

    @Test fun `dir property is ignored when not honored`() = runBlocking {
        val r = AttachDirResolver(FakeDirs(), AttachConfig(honorDirProperty = false))
        assertEquals("data/8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C", r.writeDir("a.org", heading(uuid, "photos")))
    }

    @Test fun `dir property escaping the vault is missing`() = runBlocking {
        val r = AttachDirResolver(FakeDirs(), AttachConfig.Default)
        assertNull(r.writeDir("notes/a.org", heading(uuid, "../../outside")))
        assertNull(r.writeDir("a.org", heading(uuid, "~/Pictures")))
        assertNull(r.writeDir("a.org", heading(uuid, "/Users/me/Pictures")))
        assertNull(r.writeDir("a.org", heading(uuid, "..")))
    }

    @Test fun `id dir climbing above the vault drops that candidate`() = runBlocking {
        val r = AttachDirResolver(FakeDirs(), AttachConfig(idDir = "../../x/"))
        assertNull(r.writeDir("a.org", heading(uuid)))
    }

    @Test fun `file owner resolves the same way as a heading`() = runBlocking {
        val r = AttachDirResolver(FakeDirs(), AttachConfig.Default)
        assertEquals("data/8B/41D2F0-1C9A-4E57-9A0B-6D2E3F4A5B6C", r.writeDir("a.org", AttachOwner.File(uuid, null)))
    }

    @Test fun `normalizeVaultPath collapses dots and rejects escapes`() {
        assertEquals("a/c", normalizeVaultPath("a/./b/../c"))
        assertEquals("a", normalizeVaultPath("/a/"))
        assertEquals("", normalizeVaultPath("a/.."))
        assertNull(normalizeVaultPath("a/../.."))
    }
}
