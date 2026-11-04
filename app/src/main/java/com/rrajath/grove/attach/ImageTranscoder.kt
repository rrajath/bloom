package com.rrajath.grove.attach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Prepares a Camera or Photos image before it's copied into the vault (6.4).
 * Returns a temp JPEG to copy instead of the original, or null to copy the
 * original bytes unchanged (nothing to do, or the image can't be decoded).
 * The caller deletes the returned file.
 */
fun interface PhotoProcessor {
    suspend fun process(src: AttachSource, open: () -> InputStream?, longEdge: Int?): File?
}

/** True for HEIC/HEIF sources, by MIME type or extension. */
fun isHeicSource(src: AttachSource): Boolean {
    val mime = src.mimeType?.lowercase()
    if (mime != null && (mime.startsWith("image/heic") || mime.startsWith("image/heif"))) return true
    val ext = src.displayName.substringAfterLast('.', "").lowercase()
    return ext == "heic" || ext == "heif"
}

/**
 * True when [src] is a photo format the transcoder handles: JPEG (resize) and
 * HEIC/HEIF (convert). PNG, GIF, WebP and everything else are copied as-is so
 * transparency and animation survive.
 */
fun isTranscodableImage(src: AttachSource): Boolean {
    if (isHeicSource(src)) return true
    val mime = src.mimeType?.lowercase()
    if (mime == "image/jpeg" || mime == "image/jpg") return true
    val ext = src.displayName.substringAfterLast('.', "").lowercase()
    return ext == "jpg" || ext == "jpeg"
}

/** `IMG_1234.HEIC` -> `IMG_1234.jpg`; a name without an extension gets `.jpg` appended. */
fun withJpegExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return "$stem.jpg"
}

/**
 * Resizes to a long-edge target and converts HEIC to JPEG. Steps: copy the
 * source into [tempDir] (decoding needs to read it twice, and EXIF needs a
 * seekable file), read bounds, decode straight to the target size (never up)
 * with the EXIF orientation applied, encode JPEG q85, copy the EXIF tags (GPS
 * included) except orientation, thumbnail and pixel dimensions, then set
 * orientation to normal.
 *
 * API 28+ decodes with [ImageDecoder] at the target size, so only the output
 * bitmap is ever allocated. Older releases decode with the largest power-of-2
 * `inSampleSize` that keeps the long edge at or above the target, then scale
 * and rotate into a second bitmap.
 */
class ImageTranscoder(private val tempDir: File) : PhotoProcessor {

    override suspend fun process(src: AttachSource, open: () -> InputStream?, longEdge: Int?): File? {
        val heic = isHeicSource(src)
        if (!heic && longEdge == null) return null
        if (!tempDir.isDirectory && !tempDir.mkdirs()) return null
        val raw = File.createTempFile("attach-src-", ".tmp", tempDir)
        val out = File.createTempFile("attach-out-", ".jpg", tempDir)
        var keep = false
        try {
            val input = try {
                open()
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } ?: return null
            try {
                input.use { stream ->
                    FileOutputStream(raw).use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = stream.read(buffer)
                            if (n < 0) break
                            sink.write(buffer, 0, n)
                        }
                    }
                }
            } catch (e: IOException) {
                return null
            }
            coroutineContext.ensureActive()
            keep = transcodeFile(raw, out, longEdge, force = heic)
            return if (keep) out else null
        } finally {
            raw.delete()
            if (!keep) out.delete()
        }
    }

    companion object {
        const val JPEG_QUALITY = 85

        /**
         * Writes [src] to [dst] as a JPEG whose long edge is [longEdge] (or the
         * source's, if smaller or null). Without [force] a source that's
         * already small enough is left alone. False when nothing was written.
         */
        fun transcodeFile(src: File, dst: File, longEdge: Int?, force: Boolean): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return false
            val srcLong = maxOf(w, h)
            val target = minOf(longEdge ?: srcLong, srcLong)
            if (!force && target >= srcLong) return false

            val exif = try {
                ExifInterface(src)
            } catch (e: Exception) {
                null
            }
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeAtTarget(src, target)
            } else {
                decodeSampled(src, srcLong, target, orientation)
            } ?: return false
            try {
                val ok = FileOutputStream(dst).use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                if (!ok) return false
            } catch (e: IOException) {
                return false
            } finally {
                result.recycle()
            }
            copyExif(exif, dst)
            return true
        }

        /**
         * [src] decoded with its long edge at [target] (never up), oriented
         * per its EXIF tag (ImageDecoder applies it), in a software bitmap so
         * it can be compressed. Null when it can't be decoded or doesn't fit.
         */
        @RequiresApi(Build.VERSION_CODES.P)
        private fun decodeAtTarget(src: File, target: Int): Bitmap? = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(src)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val long = maxOf(w, h)
                if (target < long) {
                    val scale = target.toFloat() / long
                    decoder.setTargetSize(
                        (w * scale).roundToInt().coerceAtLeast(1),
                        (h * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        /** Pre-P: power-of-2 sampled decode, then one scale + rotate pass into a second bitmap. */
        private fun decodeSampled(src: File, srcLong: Int, target: Int, orientation: Int): Bitmap? {
            val sample = sampleSizeFor(srcLong, target)
            val decoded = try {
                BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: OutOfMemoryError) {
                null
            } ?: return null
            val matrix = Matrix()
            val scale = target.toFloat() / maxOf(decoded.width, decoded.height)
            if (scale < 1f) matrix.postScale(scale, scale)
            applyOrientation(matrix, orientation)
            if (matrix.isIdentity) return decoded
            return try {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } catch (e: OutOfMemoryError) {
                null
            } finally {
                decoded.recycle()
            }
        }

        /** Largest power of 2 that keeps the decoded long edge at or above [target]. */
        fun sampleSizeFor(srcLong: Int, target: Int): Int {
            var sample = 1
            while (target > 0 && srcLong / (sample * 2) >= target) sample *= 2
            return sample
        }

        private fun applyOrientation(m: Matrix, orientation: Int) {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(-90f)
            }
        }

        /** Best effort: a JPEG without the source's EXIF is still a valid attachment. */
        private fun copyExif(from: ExifInterface?, dst: File) {
            try {
                val to = ExifInterface(dst)
                if (from != null) {
                    for (tag in COPIED_TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
                }
                to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                to.saveAttributes()
            } catch (e: Exception) {
                // Keep the pixels; metadata is a nicety.
            }
        }

        /**
         * Tags carried over. ExifInterface has no public "all tags" list, and
         * reflecting over its constants breaks under R8, so this is explicit.
         * Left out on purpose: orientation, thumbnail, pixel dimensions, and
         * the encoding fields the new JPEG writes itself.
         */
        internal val COPIED_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_CONTRAST,
            ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_SHARPNESS,
            ExifInterface.TAG_SUBJECT_DISTANCE,
            ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
            ExifInterface.TAG_SENSING_METHOD,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
        )
    }
}
