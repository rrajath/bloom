package com.rrajath.grove

import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.vault.TestVaultHook
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app-level attachment lifecycle is there on the first read once the vault
 * store is up. It used to be a lazily started `stateIn` on the background
 * appScope: the first read (the first notebook move or delete after launch)
 * returned null, so the move ran without the attachments sheet and left the
 * `data/` folder behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, sdk = [34])
class GroveApplicationAttachmentLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = TestVaultHook.reset()

    @Test
    fun `first read after the store is up returns the lifecycle, and the same one after`() {
        val app = ApplicationProvider.getApplicationContext<TestGroveApplication>()
        TestVaultHook.useDirectory(tmp.root)
        val deadline = System.currentTimeMillis() + 10_000
        while (app.fileStore.value == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("vault store never came up", app.fileStore.value != null)

        val first = app.attachmentLifecycle()
        assertNotNull("lifecycle missing on the first read", first)
        assertSame(first, app.attachmentLifecycle())
    }
}
