package com.rrajath.grove.settings.backup

import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.data.FavoriteNote
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsSerialization
import com.rrajath.grove.settings.SettingsSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoBackupManagerTest {

    private val dispatcher = StandardTestDispatcher()
    private val enabled = GroveSettings(autoBackupEnabled = true, autoBackupTreeUri = "content://tree/backup")

    private val settings = MutableStateFlow(enabled)
    private val templates = MutableStateFlow<List<CaptureTemplate>>(emptyList())
    private val favorites = MutableStateFlow<List<FavoriteNote>>(emptyList())
    private val searches = MutableStateFlow<List<SavedSearch>>(emptyList())
    private val writer = FakeBackupWriter()
    private var now = 1_000L

    /** Mirrors SettingsRepository: recording status is itself a settings emission. */
    private val status = object : AutoBackupStatusSink {
        override suspend fun recordAutoBackupSuccess(treeUri: String, at: Long, hash: String) = settings.update {
            if (it.autoBackupTreeUri != treeUri) it
            else it.copy(autoBackupLastSuccessAt = at, autoBackupLastHash = hash, autoBackupLastError = false)
        }

        override suspend fun recordAutoBackupFailure(treeUri: String) = settings.update {
            if (it.autoBackupTreeUri != treeUri) it else it.copy(autoBackupLastError = true, autoBackupLastHash = null)
        }
    }

    private fun TestScope.manager(): AutoBackupManager {
        val m = AutoBackupManager(
            scope = backgroundScope,
            settings = object : SettingsSource { override val settings = this@AutoBackupManagerTest.settings },
            templates = templates,
            favorites = favorites,
            savedSearches = searches,
            writer = writer,
            status = status,
            dispatchers = AppDispatchers(main = dispatcher, io = dispatcher, default = dispatcher),
            clock = { now },
        )
        m.start()
        return m
    }

    /** advanceUntilIdle stops once only backgroundScope work is left, so move the clock explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private fun search(n: Int) = SavedSearch(id = "s$n", name = "search $n", query = "tag:$n")

    @Test
    fun `five changes within two seconds produce one write`() = runTest(dispatcher) {
        manager()
        settle()
        writer.writes.clear()

        repeat(5) { i ->
            searches.value = (0..i).map(::search)
            advanceTimeBy(300)
        }
        // The loop already waited 300 ms past the last change: 1 ms short of the debounce.
        advanceTimeBy(1_699)
        runCurrent()
        assertEquals(0, writer.writes.size)

        settle()
        assertEquals(1, writer.writes.size)
        assertTrue(writer.writes.single().second.contains("search 4"))
    }

    @Test
    fun `first emission writes after the debounce and records success`() = runTest(dispatcher) {
        manager()
        settle()
        assertEquals(1, writer.writes.size)
        assertEquals("content://tree/backup", writer.writes.single().first)
        assertEquals(1_000L, settings.value.autoBackupLastSuccessAt)
        assertEquals(AutoBackupManager.sha256(writer.writes.single().second), settings.value.autoBackupLastHash)
    }

    @Test
    fun `an emission with identical json produces no write`() = runTest(dispatcher) {
        manager()
        settle()
        writer.writes.clear()

        // Fields outside the export (vault, onboarding) change: same JSON.
        settings.update { it.copy(vaultTreeUri = "content://vault", onboardingDone = true) }
        settle()
        assertEquals(0, writer.writes.size)
    }

    @Test
    fun `disabled produces no write`() = runTest(dispatcher) {
        settings.value = enabled.copy(autoBackupEnabled = false)
        val m = manager()
        searches.value = listOf(search(1))
        settle()
        m.flush()
        settle()
        assertEquals(0, writer.writes.size)
    }

    @Test
    fun `no folder produces no write`() = runTest(dispatcher) {
        settings.value = enabled.copy(autoBackupTreeUri = null)
        val m = manager()
        searches.value = listOf(search(1))
        settle()
        m.flush()
        settle()
        assertEquals(0, writer.writes.size)
    }

    @Test
    fun `turning the toggle off stops a pending write`() = runTest(dispatcher) {
        manager()
        settle()
        writer.writes.clear()

        searches.value = listOf(search(1))
        advanceTimeBy(500)
        settings.update { it.copy(autoBackupEnabled = false) }
        settle()
        assertEquals(0, writer.writes.size)
    }

    @Test
    fun `flush writes pending content immediately and the later debounce does not write again`() = runTest(dispatcher) {
        val m = manager()
        settle()
        writer.writes.clear()

        searches.value = listOf(search(1))
        runCurrent()
        m.flush()
        assertEquals(1, writer.writes.size)
        assertTrue(writer.writes.single().second.contains("search 1"))

        settle()
        assertEquals(1, writer.writes.size)
    }

    @Test
    fun `a persisted hash matching on start produces no write`() = runTest(dispatcher) {
        val text = SettingsSerialization.export(enabled, emptyList(), emptyList(), emptyList())
        settings.value = enabled.copy(autoBackupLastHash = AutoBackupManager.sha256(text))
        manager()
        settle()
        assertEquals(0, writer.writes.size)
    }

    @Test
    fun `a persisted hash differing on start produces one write`() = runTest(dispatcher) {
        settings.value = enabled.copy(autoBackupLastHash = "stale")
        manager()
        settle()
        assertEquals(1, writer.writes.size)
    }

    @Test
    fun `writer failure sets the error flag and the next success clears it`() = runTest(dispatcher) {
        writer.fail = true
        manager()
        settle()
        assertTrue(settings.value.autoBackupLastError)

        // No retry loop: the failure's own settings emission is not a change.
        assertEquals(1, writer.attempts)

        writer.fail = false
        searches.value = listOf(search(1))
        settle()
        assertFalse(settings.value.autoBackupLastError)
        assertEquals(1, writer.writes.size)
    }

    @Test
    fun `flush after a failure retries the unchanged content`() = runTest(dispatcher) {
        writer.fail = true
        val m = manager()
        settle()
        writer.fail = false
        m.flush()
        assertEquals(1, writer.writes.size)
        assertFalse(settings.value.autoBackupLastError)
    }

    @Test
    fun `while off only settings is watched`() = runTest(dispatcher) {
        settings.value = enabled.copy(autoBackupEnabled = false)
        manager()
        settle()
        assertEquals(0, templates.subscriptionCount.value)
        assertEquals(0, favorites.subscriptionCount.value)
        assertEquals(0, searches.subscriptionCount.value)

        settings.update { it.copy(autoBackupEnabled = true) }
        settle()
        assertEquals(1, searches.subscriptionCount.value)
        assertEquals(1, writer.writes.size)
    }

    @Test
    fun `a deleted file is written again at start even though the content is unchanged`() = runTest(dispatcher) {
        val text = SettingsSerialization.export(enabled, emptyList(), emptyList(), emptyList())
        settings.value = enabled.copy(autoBackupLastHash = AutoBackupManager.sha256(text), autoBackupLastSuccessAt = 1L)
        writer.present = false
        manager()
        settle()
        assertEquals(listOf("content://tree/backup" to text), writer.writes)
    }

    @Test
    fun `a present file with a matching hash is left alone at start`() = runTest(dispatcher) {
        val text = SettingsSerialization.export(enabled, emptyList(), emptyList(), emptyList())
        settings.value = enabled.copy(autoBackupLastHash = AutoBackupManager.sha256(text), autoBackupLastSuccessAt = 1L)
        manager()
        settle()
        assertEquals(0, writer.writes.size)
    }

    @Test
    fun `forced flush rewrites content matching the last write`() = runTest(dispatcher) {
        val m = manager()
        settle()
        writer.writes.clear()
        m.flush()
        assertEquals(0, writer.writes.size)
        m.flush(force = true)
        assertEquals(1, writer.writes.size)
    }

    private class FakeBackupWriter : BackupWriter {
        val writes = mutableListOf<Pair<String, String>>()
        var attempts = 0
        var fail = false
        var present = true
        override suspend fun write(treeUri: String, text: String): Result<Unit> {
            attempts++
            if (fail) return Result.failure(java.io.IOException("boom"))
            writes += treeUri to text
            present = true
            return Result.success(Unit)
        }

        override suspend fun exists(treeUri: String) = present
    }
}
