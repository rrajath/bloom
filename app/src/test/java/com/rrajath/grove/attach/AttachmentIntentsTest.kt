package com.rrajath.grove.attach

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AttachmentIntentsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Avault/document/primary%3Avault%2Fdata%2F3F%2FX%2Fa.pdf")

    /** A store that only knows one attachment. */
    private val store = object : FileStore {
        override suspend fun list(): List<FileEntry> = emptyList()
        override suspend fun read(name: String) = error("unused")
        override suspend fun write(name: String, content: String) = error("unused")
        override suspend fun create(name: String) = false
        override suspend fun rename(oldName: String, newName: String) = false
        override suspend fun delete(name: String) = false
        override suspend fun exists(name: String) = false
        override suspend fun contentUri(name: String): Uri? = uri.takeIf { name == "data/3F/X/a.pdf" }
    }

    @Before
    fun mimeTypes() {
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("pdf", "application/pdf")
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
        }
    }

    private fun registerViewer(mime: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.example.viewer"; name = "Viewer" }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(AttachmentIntents.viewIntent(uri, mime), info)
    }

    @Test
    fun `mime type comes from the extension, case-insensitively`() {
        assertEquals("application/pdf", AttachmentIntents.mimeType("data/3F/X/a.pdf"))
        assertEquals("image/jpeg", AttachmentIntents.mimeType("IMG_1.JPG"))
        assertEquals("application/octet-stream", AttachmentIntents.mimeType("data/x.unknownext"))
        assertEquals("application/octet-stream", AttachmentIntents.mimeType("README"))
    }

    @Test
    fun `open reports a missing file`() = runTest {
        assertEquals(AttachmentIntents.Result.Missing, AttachmentIntents.open(context, store, "data/3F/X/gone.pdf"))
    }

    @Test
    fun `open reports when no app can view the file`() = runTest {
        assertEquals(AttachmentIntents.Result.NoHandler, AttachmentIntents.open(context, store, "data/3F/X/a.pdf"))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `open launches a chooser around a granted view intent`() = runTest {
        registerViewer("application/pdf")
        assertEquals(AttachmentIntents.Result.Launched, AttachmentIntents.open(context, store, "data/3F/X/a.pdf"))

        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, inner.action)
        assertEquals(uri, inner.data)
        assertEquals("application/pdf", inner.type)
        assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `share sends the stream with a read grant`() = runTest {
        assertEquals(AttachmentIntents.Result.Launched, AttachmentIntents.share(context, store, "data/3F/X/a.pdf"))
        val chooser = shadowOf(context).nextStartedActivity
        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("application/pdf", inner.type)
        @Suppress("DEPRECATION")
        assertEquals(uri, inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, inner.clipData?.getItemAt(0)?.uri)
        assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `image loader has a memory cache and no disk cache`() {
        val loader = AttachmentImageLoader.build(context)
        assertNotNull(loader.memoryCache)
        assertNull(loader.diskCache)
    }
}
