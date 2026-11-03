package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        AttachmentResolver(AttachmentLookup(AttachmentFiles(JvmFileStore(tmp.root, skipAttachShards = true))), cache)
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
}
