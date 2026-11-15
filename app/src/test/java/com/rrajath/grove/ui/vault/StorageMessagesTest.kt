package com.rrajath.grove.ui.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.storage.StorageError
import com.rrajath.grove.vault.storage.StorageOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One accurate message per [StorageError] kind: a refusal must never read as a name clash. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StorageMessagesTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun message(error: StorageError, subject: StorageSubject = StorageSubject.Notebook) =
        storageErrorMessage(context, error, subject)

    private val everyKind: List<StorageError> = listOf(
        StorageError.NameTaken,
        StorageError.NotFound,
        StorageError.Unsupported(StorageOp.Create),
        StorageError.Unsupported(StorageOp.Rename),
        StorageError.Unsupported(StorageOp.Move),
        StorageError.Unsupported(StorageOp.Delete),
        StorageError.Unsupported(StorageOp.Write),
        StorageError.Unsupported(StorageOp.Read),
        StorageError.ProviderError(null),
        StorageError.ProviderError("disk full"),
        StorageError.Offline,
        StorageError.Conflict("1:2"),
        StorageError.AuthFailed,
    )

    @Test
    fun `every kind has its own non-empty message`() {
        val messages = everyKind.map { message(it) }
        assertTrue(messages.all { it.isNotBlank() })
        assertEquals("no two kinds share a message", messages.size, messages.toSet().size)
    }

    @Test
    fun `only a name clash says it already exists`() {
        everyKind.filter { it != StorageError.NameTaken }.forEach {
            assertFalse("$it: ${message(it)}", message(it).contains("already exists"))
        }
        assertEquals("A notebook with that name already exists", message(StorageError.NameTaken))
        assertEquals(
            "A folder with that name already exists",
            message(StorageError.NameTaken, StorageSubject.Folder),
        )
    }

    @Test
    fun `an unsupported operation names what the provider won't do`() {
        assertTrue(message(StorageError.Unsupported(StorageOp.Rename)).contains("rename"))
        assertTrue(message(StorageError.Unsupported(StorageOp.Delete)).contains("delete"))
        assertTrue(message(StorageError.Unsupported(StorageOp.Move)).contains("move"))
        assertTrue(message(StorageError.Unsupported(StorageOp.Create)).contains("create"))
    }

    @Test
    fun `a provider error carries the provider's own words when it gave any`() {
        assertTrue(message(StorageError.ProviderError("disk full")).endsWith("disk full"))
        assertFalse(message(StorageError.ProviderError(null)).contains("null"))
        assertEquals(message(StorageError.ProviderError(null)), message(StorageError.ProviderError("  ")))
    }

    @Test
    fun `offline, conflict and sign-in each say what to do`() {
        assertTrue(message(StorageError.Offline).contains("Can't reach"))
        assertTrue(message(StorageError.Conflict(null)).contains("Reload"))
        assertTrue(message(StorageError.AuthFailed).contains("sign in"))
    }

    @Test
    fun `no message contains an em dash`() {
        everyKind.forEach { assertFalse(message(it).contains('—')) }
    }
}
