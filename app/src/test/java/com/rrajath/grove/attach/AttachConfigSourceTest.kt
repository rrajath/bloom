package com.rrajath.grove.attach

import com.rrajath.grove.vault.FileEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachConfigSourceTest {

    @Test
    fun `settings source is null until settings load, then follows the folder`() = runTest(UnconfinedTestDispatcher()) {
        val folders = MutableSharedFlow<String>()
        val source = SettingsAttachConfigSource(folders, backgroundScope)
        assertNull(source.config.value)
        assertEquals(AttachConfig.Default, source.current())
        folders.emit("attach/")
        assertEquals("attach/", source.config.value?.idDir)
        assertEquals("attach/", source.current().idDir)
        folders.emit("media/files/")
        assertEquals("media/files/", source.current().idDir)
    }

    @Test
    fun `a folder change clears the status cache, the first load and a repeat don't`() = runTest(UnconfinedTestDispatcher()) {
        val folders = MutableSharedFlow<String>()
        val source = SettingsAttachConfigSource(folders, backgroundScope)
        val cache = AttachmentStatusCache()
        val changes = mutableListOf<String>()
        source.onConfigChange(backgroundScope) { changes += it.idDir; cache.clear() }
        val key = AttachmentStatusCache.key("n.org", "attachment:a.jpg", 0)

        folders.emit("data/")
        cache.putStatus(key, AttachmentStatus.Present(FileEntry("data/ab/cdef/a.jpg", 0, 1)))
        folders.emit("data/")
        assertNotNull(cache.status(key))
        assertEquals(emptyList<String>(), changes)

        folders.emit("attach/")
        assertNull(cache.status(key))
        assertEquals(listOf("attach/"), changes)
    }
}
