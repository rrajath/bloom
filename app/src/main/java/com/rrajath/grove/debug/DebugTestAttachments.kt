package com.rrajath.grove.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/**
 * Test-only seed for the attachment Maestro flows (`.maestro/flows/attachments/`).
 * [seedVault] wipes the test vault and writes the notes plus their attachment
 * folders, generating the images, so every flow starts from the same files
 * whatever the last one moved. Only reached from [DebugTestVault], which is a
 * no-op unless `TEST_HOOKS`.
 */
internal object DebugTestAttachments {

    const val SET_NAME = "attachments"

    /** `inherit.org`: a child heading whose image lives in its parent's `:DIR:` folder. */
    private val INHERIT_ORG = """
        |#+TITLE: Attach inherit
        |
        |* Album
        |:PROPERTIES:
        |:DIR: album
        |:END:
        |Parent folder.
        |** Kite day
        |[[attachment:kite.jpg]]
        |""".trimMargin()

    fun seedVault(root: File) {
        wipe(root)
        File(root, "inherit.org").writeText(INHERIT_ORG)
        writeJpeg(File(root, "album/kite.jpg"), Color.rgb(70, 130, 180))
    }

    fun wipe(root: File) {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** A small two-band JPEG, enough for Read mode to show it inline. */
    private fun writeJpeg(file: File, color: Int) {
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            drawRect(0f, 140f, 320f, 200f, Paint().apply { this.color = Color.rgb(230, 200, 120) })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
    }
}
