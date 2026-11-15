package com.rrajath.grove.ui.screens.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.storage.StorageCapabilities
import com.rrajath.grove.vault.storage.VaultCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProviderSummaryTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun summary(vararg files: StorageCapabilities) =
        providerSummary(context, VaultCapabilities(files = files.withIndex().associate { "f${it.index}.org" to it.value }))

    @Test
    fun `a provider that allows everything says so`() {
        assertEquals("Rename, move and delete all work", summary(StorageCapabilities.ALL))
    }

    @Test
    fun `Seafile lists everything it rules out`() {
        assertEquals(
            "Doesn't let other apps rename, move or delete files",
            summary(StorageCapabilities(delete = false, rename = false, move = false, copy = false)),
        )
    }

    @Test
    fun `one missing action reads on its own, and copy plus delete counts as move`() {
        assertEquals("Doesn't let other apps rename files", summary(StorageCapabilities(rename = false, move = false)))
    }
}
