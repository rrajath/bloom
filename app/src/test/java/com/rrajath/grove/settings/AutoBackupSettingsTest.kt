package com.rrajath.grove.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.testing.support.IsolatedPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The device-local automatic-backup fields persist through [SettingsRepository]. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AutoBackupSettingsTest {

    private val store = IsolatedPreferencesDataStore()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext(), scope, store.store)

    @After
    fun tearDown() {
        scope.cancel()
        store.close()
    }

    private fun current() = runBlocking { repo.settings.first() }

    /**
     * [SettingsRepository.settings] is shared with replay 1 and collected on an IO
     * scope, so right after an edit returns it can still replay the previous value.
     * Wait for the value carrying the last write instead.
     */
    private fun after(lastWrite: (GroveSettings) -> Boolean) =
        runBlocking { withTimeout(5_000) { repo.settings.first(lastWrite) } }

    @Test
    fun `defaults are off with no folder, status or hash`() {
        val s = current()
        assertFalse(s.autoBackupEnabled)
        assertNull(s.autoBackupTreeUri)
        assertNull(s.autoBackupLastSuccessAt)
        assertFalse(s.autoBackupLastError)
        assertNull(s.autoBackupLastHash)
    }

    @Test
    fun `toggle and folder persist`() = runBlocking {
        repo.setAutoBackupEnabled(true)
        repo.setAutoBackupTreeUri("content://tree/backup")
        val s = after { it.autoBackupTreeUri != null }
        assertTrue(s.autoBackupEnabled)
        assertEquals("content://tree/backup", s.autoBackupTreeUri)
    }

    @Test
    fun `a success records time and hash and clears the error`() = runBlocking {
        repo.recordAutoBackupFailure()
        assertTrue(after { it.autoBackupLastError }.autoBackupLastError)

        repo.recordAutoBackupSuccess(at = 1234L, hash = "abc")
        val s = after { it.autoBackupLastSuccessAt != null }
        assertEquals(1234L, s.autoBackupLastSuccessAt)
        assertEquals("abc", s.autoBackupLastHash)
        assertFalse(s.autoBackupLastError)
    }

    @Test
    fun `a failure sets the error and drops the hash so the next attempt writes`() = runBlocking {
        repo.recordAutoBackupSuccess(at = 1L, hash = "abc")
        repo.recordAutoBackupFailure()
        val s = after { it.autoBackupLastError }
        assertTrue(s.autoBackupLastError)
        assertNull(s.autoBackupLastHash)
        assertEquals(1L, s.autoBackupLastSuccessAt)
    }

    @Test
    fun `picking a different folder resets the status so the new folder gets written`() = runBlocking {
        repo.setAutoBackupTreeUri("content://tree/a")
        repo.recordAutoBackupSuccess(at = 1L, hash = "abc")
        repo.recordAutoBackupFailure()
        repo.setAutoBackupTreeUri("content://tree/b")
        val s = after { it.autoBackupTreeUri == "content://tree/b" }
        assertEquals("content://tree/b", s.autoBackupTreeUri)
        assertNull(s.autoBackupLastHash)
        assertNull(s.autoBackupLastSuccessAt)
        assertFalse(s.autoBackupLastError)
    }
}
