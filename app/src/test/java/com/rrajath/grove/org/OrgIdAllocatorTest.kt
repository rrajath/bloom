package com.rrajath.grove.org

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrgIdAllocatorTest {

    /** Hands out [ids] in order, so a test controls every candidate. */
    private fun scripted(vararg ids: String): () -> String {
        val queue = ArrayDeque(ids.toList())
        return { queue.removeFirst() }
    }

    @Test
    fun `skips a candidate the vault already uses`() = runTest {
        val allocator = OrgIdAllocator(taken = { it == "TAKEN" }, generate = scripted("TAKEN", "FREE"))
        assertEquals("FREE", allocator.next())
    }

    @Test
    fun `never hands out the same id twice, even before the index sees it`() = runTest {
        val allocator = OrgIdAllocator(taken = { false }, generate = scripted("A", "A", "a", "B"))
        assertEquals("A", allocator.next())
        // "A" again, and "a" (org IDs match case-insensitively), are both skipped.
        assertEquals("B", allocator.next())
    }

    @Test
    fun `allocate returns distinct vault-unique ids`() = runTest {
        val allocator = OrgIdAllocator(taken = { it == "X" }, generate = scripted("A", "X", "A", "B", "C"))
        assertEquals(listOf("A", "B", "C"), allocator.allocate(3))
        assertEquals(emptyList<String>(), allocator.allocate(0))
    }

    @Test
    fun `gives up instead of spinning when every candidate is taken`() = runTest {
        val allocator = OrgIdAllocator(taken = { true }, generate = { "SAME" })
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { allocator.next() } }
    }

    @Test
    fun `default generator yields upper-case uuids`() = runTest {
        val id = OrgIdAllocator(taken = { false }).next()
        assertEquals(id.uppercase(), id)
        assertEquals(36, id.length)
    }
}
