package com.rrajath.grove.ui.attach

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.rrajath.grove.attach.AttachmentStatusCache
import com.rrajath.grove.attach.ImageBounds
import com.rrajath.grove.settings.AttachImageWidth
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The image box follows EXIF rotation, so camera photos copied as-is fill their rounded clip. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class)
class AttachmentBoundsRobolectricTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx by lazy {
        AttachmentRenderContext(
            orgFile = "n.org", doc = null,
            store = JvmFileStore(tmp.root, skipAttachShards = true),
            cache = AttachmentStatusCache(),
            widthMode = AttachImageWidth.FIT, fixedWidthPx = AttachImageWidth.DEFAULT_PX,
            preferLines = null, openTarget = {}, toast = {}, io = Dispatchers.IO,
        )
    }

    private fun jpeg(name: String, orientation: Int?): File {
        val f = File(tmp.root, name)
        f.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            ExifInterface(f).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        }
        return f
    }

    private fun bounds(name: String): ImageBounds? = runBlocking {
        val st = ctx.store.statRaw(name)!!.copy(name = name)
        ctx.bounds(st)
    }

    @Test
    fun rotatedCameraJpegGetsAPortraitBox() {
        jpeg("cam.jpg", ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(ImageBounds(30, 40), bounds("cam.jpg"))
    }

    @Test
    fun boundsAndExifComeFromOneOpen() {
        jpeg("cam.jpg", ExifInterface.ORIENTATION_ROTATE_90)
        val jvm = JvmFileStore(tmp.root, skipAttachShards = true)
        var opens = 0
        val counting = object : com.rrajath.grove.vault.FileStore by jvm {
            override suspend fun openInput(name: String): java.io.InputStream {
                opens++
                return jvm.openInput(name)
            }
        }
        assertEquals(ImageBounds(30, 40), runBlocking { readBounds(counting, "cam.jpg") })
        assertEquals(1, opens)
    }

    @Test
    fun unrotatedJpegKeepsItsHeaderSize() {
        jpeg("plain.jpg", null)
        assertEquals(ImageBounds(40, 30), bounds("plain.jpg"))
    }
}
