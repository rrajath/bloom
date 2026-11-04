package com.rrajath.grove.attach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import com.rrajath.grove.testing.TestGroveApplication
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real decode/encode under Robolectric's native graphics, with EXIF fixtures written by ExifInterface. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class)
open class ImageTranscoderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A [w]x[h] JPEG with camera, date and GPS tags, plus [orientation]. */
    private fun fixture(w: Int, h: Int, orientation: Int = ExifInterface.ORIENTATION_NORMAL): File {
        val f = tmp.newFile("src-${w}x$h-$orientation.jpg")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 80, 40)) }
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(f).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            setAttribute(ExifInterface.TAG_MODEL, "Pixel 9a")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:03 09:15:00")
            setLatLong(37.4219, -122.0841)
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return f
    }

    private fun size(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun `resizes to the long-edge target and keeps the aspect ratio`() {
        val out = File(tmp.root, "out.jpg")
        assertTrue(ImageTranscoder.transcodeFile(fixture(2000, 1500), out, 1280, force = false))
        assertEquals(1280 to 960, size(out))
    }

    @Test
    fun `copies EXIF tags including GPS and resets orientation`() {
        val out = File(tmp.root, "out.jpg")
        assertTrue(ImageTranscoder.transcodeFile(fixture(2000, 1500), out, 1280, force = false))
        val exif = ExifInterface(out)
        assertEquals("Google", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Pixel 9a", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2026:11:03 09:15:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        val latLong = exif.latLong!!
        assertEquals(37.4219, latLong[0], 1e-3)
        assertEquals(-122.0841, latLong[1], 1e-3)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
    }

    @Test
    fun `bakes a 90 degree EXIF rotation into the pixels`() {
        val out = File(tmp.root, "out.jpg")
        assertTrue(ImageTranscoder.transcodeFile(fixture(2000, 1500, ExifInterface.ORIENTATION_ROTATE_90), out, 1280, force = false))
        assertEquals(960 to 1280, size(out))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifInterface(out).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
    }

    @Test
    fun `never upscales and leaves a small enough image alone`() {
        val out = File(tmp.root, "out.jpg")
        assertFalse(ImageTranscoder.transcodeFile(fixture(800, 600), out, 1280, force = false))
        assertFalse(out.exists() && out.length() > 0)
    }

    @Test
    fun `force re-encodes at the original size`() {
        val out = File(tmp.root, "out.jpg")
        assertTrue(ImageTranscoder.transcodeFile(fixture(800, 600), out, 1280, force = true))
        assertEquals(800 to 600, size(out))
        assertTrue(ImageTranscoder.transcodeFile(fixture(900, 700), out, null, force = true))
        assertEquals(900 to 700, size(out))
    }

    @Test
    fun `an undecodable file is not transcoded`() {
        val junk = tmp.newFile("junk.heic").apply { writeText("not an image") }
        assertFalse(ImageTranscoder.transcodeFile(junk, File(tmp.root, "out.jpg"), 1280, force = true))
    }

    @Test
    fun `sample size is the largest power of two that stays at or above the target`() {
        assertEquals(1, ImageTranscoder.sampleSizeFor(2000, 1280))
        assertEquals(2, ImageTranscoder.sampleSizeFor(4000, 1920))
        assertEquals(4, ImageTranscoder.sampleSizeFor(8160, 1920))
        assertEquals(1, ImageTranscoder.sampleSizeFor(1280, 1280))
    }

    @Test
    fun `process returns a temp JPEG and cleans up its source copy`() = runTest {
        val dir = File(tmp.root, "cache")
        val bytes = fixture(2000, 1500).readBytes()
        val out = ImageTranscoder(dir).process(AttachSource("u", "IMG_1.jpg", null, "image/jpeg"), { ByteArrayInputStream(bytes) }, 1280)
        assertNotNull(out)
        assertEquals(1280 to 960, size(out!!))
        assertEquals(listOf(out.name), dir.list()!!.toList())
    }

    @Test
    fun `process with Original keeps a JPEG as-is but still converts HEIC`() = runTest {
        val dir = File(tmp.root, "cache")
        val bytes = fixture(2000, 1500).readBytes()
        assertNull(ImageTranscoder(dir).process(AttachSource("u", "IMG_1.jpg", null, "image/jpeg"), { ByteArrayInputStream(bytes) }, null))
        // A JPEG stands in for HEIC content here: the decision is by name/MIME, the decode is the same.
        val heic = ImageTranscoder(dir).process(AttachSource("u", "IMG_2.HEIC", null, null), { ByteArrayInputStream(bytes) }, null)
        assertNotNull(heic)
        assertEquals(2000 to 1500, size(heic!!))
    }

    @Test
    fun `process returns null when the source can't be opened or decoded`() = runTest {
        val dir = File(tmp.root, "cache")
        val t = ImageTranscoder(dir)
        assertNull(t.process(AttachSource("u", "a.jpg", null), { null }, 1280))
        assertNull(t.process(AttachSource("u", "a.heic", null), { ByteArrayInputStream("x".toByteArray()) }, null))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun `format detection and jpg renaming`() {
        assertTrue(isHeicSource(AttachSource("u", "IMG.HEIC", null)))
        assertTrue(isHeicSource(AttachSource("u", "photo", null, "image/heif")))
        assertTrue(isTranscodableImage(AttachSource("u", "a.JPEG", null)))
        assertFalse(isTranscodableImage(AttachSource("u", "a.png", null, "image/png")))
        assertFalse(isTranscodableImage(AttachSource("u", "anim.gif", null)))
        assertEquals("IMG_1234.jpg", withJpegExtension("IMG_1234.HEIC"))
        assertEquals("photo.jpg", withJpegExtension("photo"))
        assertEquals(".hidden.jpg", withJpegExtension(".hidden"))
    }
}

/** The same cases on API 27, where transcoding takes the BitmapFactory sample + matrix path instead of ImageDecoder. */
@Config(application = TestGroveApplication::class, sdk = [27])
class ImageTranscoderLegacyDecodeTest : ImageTranscoderTest()
