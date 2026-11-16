package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Read-mode existence cache: memoized, Missing re-checked after sync, cleared on resume. */
class AttachmentStatusCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cache = AttachmentStatusCache()
    private val resolver by lazy {
        AttachmentResolver(AttachmentLookup(AttachmentFiles(JvmFileStore(tmp.root, skipAttachShards = true)), AttachConfig.Default), cache)
    }

    private fun put(path: String) = File(tmp.root, path).apply { parentFile?.mkdirs() }.writeText("x")

    private val text = """
        * Lunch
        :PROPERTIES:
        :ID: abcdef
        :END:
        [[attachment:a.jpg]]
        * Dinner
        :PROPERTIES:
        :ID: 123456
        :END:
        [[attachment:a.jpg]]
    """.trimIndent() + "\n"
    private val doc = OrgParser.parse(text)
    private val link = AttachmentLink.classify("attachment:a.jpg", null)!!

    @Test
    fun `a known line pins the owner, so a repeated link resolves per heading`() = runTest {
        put("data/ab/cdef/a.jpg")
        put("data/12/3456/a.jpg")
        val lunch = resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 4)
        val dinner = resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 9)
        assertEquals("data/ab/cdef/a.jpg", (lunch as AttachmentStatus.Present).path)
        assertEquals("data/12/3456/a.jpg", (dinner as AttachmentStatus.Present).path)
    }

    @Test
    fun `missing stays cached until a sync invalidates it`() = runTest {
        assertEquals(AttachmentStatus.Missing, resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 4))
        put("data/ab/cdef/a.jpg")
        assertEquals(AttachmentStatus.Missing, resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 4))
        val before = cache.generation.value
        cache.invalidateMissing()
        assertTrue(cache.generation.value > before)
        assertTrue(resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 4) is AttachmentStatus.Present)
    }

    @Test
    fun `knownMissing tracks Missing entries under any line of the file`() {
        val present = AttachmentStatus.Present(FileEntry("data/ab/cdef/a.jpg", 0, 1))
        val key4 = AttachmentStatusCache.key("n.org", "attachment:a.jpg", 4)
        val key9 = AttachmentStatusCache.key("n.org", "attachment:a.jpg", 9)
        assertFalse(cache.knownMissing("n.org", "attachment:a.jpg"))

        cache.putStatus(key4, AttachmentStatus.Missing)
        cache.putStatus(key9, AttachmentStatus.Missing)
        assertTrue(cache.knownMissing("n.org", "attachment:a.jpg"))
        assertFalse(cache.knownMissing("other.org", "attachment:a.jpg"))
        assertFalse(cache.knownMissing("n.org", "attachment:a.j"))

        // One line turns present: the other still reports missing.
        cache.putStatus(key4, present)
        assertTrue(cache.knownMissing("n.org", "attachment:a.jpg"))
        // Re-putting Missing on the same line doesn't double count.
        cache.putStatus(key9, AttachmentStatus.Missing)
        cache.putStatus(key9, present)
        assertFalse(cache.knownMissing("n.org", "attachment:a.jpg"))

        cache.putStatus(key9, AttachmentStatus.Missing)
        cache.invalidateMissing()
        assertFalse(cache.knownMissing("n.org", "attachment:a.jpg"))
        cache.putStatus(key9, AttachmentStatus.Missing)
        cache.clear()
        assertFalse(cache.knownMissing("n.org", "attachment:a.jpg"))
        cache.putStatus(key9, AttachmentStatus.Missing)
        cache.forget(key9)
        assertFalse(cache.knownMissing("n.org", "attachment:a.jpg"))
    }

    @Test
    fun `recheck skips a stale Present answer`() = runTest {
        put("data/ab/cdef/a.jpg")
        assertTrue(resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 4) is AttachmentStatus.Present)
        File(tmp.root, "data/ab/cdef/a.jpg").delete()
        assertTrue(resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 4) is AttachmentStatus.Present)
        assertEquals(AttachmentStatus.Missing, resolver.recheck(link, "attachment:a.jpg", "n.org", doc, docLine = 4))
        assertTrue(cache.knownMissing("n.org", "attachment:a.jpg"))
    }

    @Test
    fun `invalidateMissing keeps present entries, clear drops everything`() = runTest {
        put("data/ab/cdef/a.jpg")
        assertTrue(resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 4) is AttachmentStatus.Present)
        File(tmp.root, "data/ab/cdef/a.jpg").delete()
        cache.invalidateMissing()
        assertTrue(resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 4) is AttachmentStatus.Present)
        cache.clear()
        assertEquals(AttachmentStatus.Missing, resolver.status(link, "attachment:a.jpg", "n.org", doc, docLine = 4))
    }

    @Test
    fun `file links resolve against the org file's directory`() = runTest {
        put("trips/img/a.png")
        val l = AttachmentLink.classify("file:img/a.png", null)!!
        val st = resolver.status(l, "file:img/a.png", "trips/kyoto.org", doc)
        assertEquals("trips/img/a.png", (st as AttachmentStatus.Present).path)
    }

    @Test
    fun `image sizes are capped, dropping the least recently used`() {
        val entries = (0..AttachmentStatusCache.MAX_BOUNDS).map { FileEntry("data/$it.jpg", 1L, 1L) }
        entries.forEach { cache.putBounds(it, ImageBounds(4, 3)) }
        assertEquals(null, cache.bounds(entries[0]))
        cache.bounds(entries[1]) // Touch, so the next put evicts entries[2] instead.
        cache.putBounds(FileEntry("data/new.jpg", 1L, 1L), ImageBounds(4, 3))
        assertEquals(ImageBounds(4, 3), cache.bounds(entries[1]))
        assertEquals(null, cache.bounds(entries[2]))
    }
}
