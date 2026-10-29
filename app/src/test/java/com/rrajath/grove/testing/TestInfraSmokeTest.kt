package com.rrajath.grove.testing

import com.rrajath.grove.settings.GroveSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks that the shared test-fixtures source set is wired up and the
 * fakes behave. No Robolectric — this runs in the fast per-push suite.
 */
class TestInfraSmokeTest {

    @Test
    fun `FakeFileStore round-trips content and tracks revision`() = runTest {
        val store = FakeFileStore()
        assertTrue(store.create("a/b/note.org"))
        assertFalse(store.create("a/b/note.org"))
        store.write("a/b/note.org", "* Task")

        assertEquals("* Task", store.read("a/b/note.org"))
        assertEquals(listOf("a/b/note.org"), store.list().map { it.name })

        val before = store.revisionOf("a/b/note.org")
        store.touch("a/b/note.org")
        assertTrue(before != store.revisionOf("a/b/note.org"))
    }

    @Test
    fun `FakeFileStore skips dot-directories and missing reads throw`() = runTest {
        val store = FakeFileStore(mapOf("keep.org" to "* K", ".git/config" to "[core]"))
        assertEquals(listOf("keep.org"), store.list().map { it.name })
        assertNull(store.stat("missing.org"))
        runCatching { store.read("missing.org") }.also { assertTrue(it.isFailure) }
    }

    @Test
    fun `FakeSettingsRepository emits seed then updates`() = runTest {
        val repo = FakeSettingsRepository(GroveSettings(syncAppIconWithTheme = true))
        assertEquals(true, repo.settings.first().syncAppIconWithTheme)
        repo.update { it.copy(syncAppIconWithTheme = false) }
        assertEquals(false, repo.settings.first().syncAppIconWithTheme)
    }

    @Test
    fun `OrgFixtures load from resources, each ending in exactly one newline`() {
        // The bodies moved to src/testFixtures/resources/fixtures so the debug
        // build can seed a Maestro vault from identical content (M5). Guard the
        // resource-load path and the trailing-newline shape assertions elsewhere
        // rely on.
        OrgFixtures.all.forEach { (name, content) ->
            assertTrue("$name is empty", content.isNotBlank())
            assertTrue("$name must end with a single newline", content.endsWith("\n"))
            assertFalse("$name must not end with a blank line", content.endsWith("\n\n"))
        }
        assertTrue(OrgFixtures.INBOX.startsWith("#+TITLE: Inbox\n"))
        assertTrue(OrgFixtures.READING_LIST.contains("photosynthesis"))
    }

    @Test
    fun `TestVaultSeeder writes every fixture into the store`() = runTest {
        val store = FakeFileStore()
        TestVaultSeeder.seed(store)

        val names = store.list().map { it.name }
        assertEquals(OrgFixtures.all.keys.sorted(), names)
        assertTrue(store.read("reading-list.org").contains("photosynthesis"))
        assertTrue(store.read("inbox.org").contains(":CUSTOM_ID: capture-inbox"))
        // Large-subtree fixture is big enough to trip the fold-on-open threshold (60).
        assertTrue(store.read("large-subtree.org").lines().count { it.startsWith("** ") } > 60)
    }
}
