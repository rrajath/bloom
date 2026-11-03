package com.rrajath.grove.ui.editor

import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import com.rrajath.grove.attach.AttachmentCopyManager
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives the editor-side attach flow through [rememberAttachController], the way
 * an activity result arrives on device: picker result -> owner re-found ->
 * copy -> links in the buffer. Regression: the result was handled inside a
 * LaunchedEffect keyed on the very state it cleared, so the work was cancelled
 * at its first suspension and nothing was ever copied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class)
class AttachControllerRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultDir by lazy { tmp.newFolder("vault") }
    private val store by lazy { JvmFileStore(vaultDir, skipAttachShards = true) }
    private val manager by lazy {
        AttachmentCopyManager(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            io = Dispatchers.IO,
            store = { store },
            opener = { uri -> File(Uri.parse(uri).path!!).inputStream() },
            fallback = { null },
            notify = {},
        )
    }

    /** Runs the paused main looper (the manager resumes there) until [done] or 5 s pass. */
    private fun settleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within 5s" }
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(10)
        }
    }

    private fun picked(name: String, text: String = "PDF bytes"): Uri =
        Uri.fromFile(File(tmp.newFolder(), name).apply { writeText(text) })

    @Test
    fun `a Files pick copies into the heading's data dir and links it at the cursor`() {
        val text = "* Lunch\nTiny soba place.\n"
        val textState = TextFieldState(text, TextRange(text.indexOf("place.") + 6))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(textState, "travel.org", bufferReady = true, manager) { store }
            }
        }
        composeRule.runOnIdle {
            controller.open()
            controller.pending = PendingAttach("Files", isFile = false, id = null, titlePath = listOf("Lunch"), lineIndex = 0, cursor = textState.selection.start)
            controller.onPickerResult(listOf(picked("receipt.pdf")))
        }
        settleUntil { textState.text.contains("[[attachment:receipt.pdf]]") }

        val doc = OrgParser.parse(textState.text.toString())
        val id = doc.headlines.single().properties["ID"]!!
        val copied = File(vaultDir, "data/${id.take(2)}/${id.drop(2)}/receipt.pdf")
        assertTrue("copied to $copied", copied.isFile)
        assertEquals("PDF bytes", copied.readText())
        assertTrue(textState.text.toString(), textState.text.contains("Tiny soba place.\n[[attachment:receipt.pdf]]"))
        assertEquals("Attached to Lunch", controller.toast?.message)
    }

    @Test
    fun `a result that arrives before the buffer is loaded waits for it`() {
        val textState = TextFieldState("* Lunch\n:PROPERTIES:\n:ID: AB12345\n:END:\nbody\n", TextRange(0))
        var ready by mutableStateOf(false)
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(textState, "travel.org", bufferReady = ready, manager) { store }
            }
        }
        composeRule.runOnIdle {
            controller.pending = PendingAttach("Photos", false, "AB12345", listOf("Lunch"), 0, 0)
            controller.onPickerResult(listOf(picked("a.jpg"), picked("b.jpg")))
        }
        composeRule.waitForIdle()
        assertFalse(File(vaultDir, "data").exists())

        ready = true
        settleUntil { textState.text.contains("[[attachment:b.jpg]]") }
        assertTrue(File(vaultDir, "data/AB/12345/a.jpg").isFile)
        assertTrue(textState.text.toString(), textState.text.contains(":END:\n[[attachment:a.jpg]]\n[[attachment:b.jpg]]\nbody"))
        assertEquals("2 photos attached to Lunch", controller.toast?.message)
    }

    @Test
    fun `a cancelled picker does nothing`() {
        val textState = TextFieldState("* Lunch\n", TextRange(0))
        lateinit var controller: AttachController
        composeRule.setContent {
            GroveTheme {
                controller = rememberAttachController(textState, "travel.org", bufferReady = true, manager) { store }
            }
        }
        composeRule.runOnIdle {
            controller.pending = PendingAttach("Files", false, null, listOf("Lunch"), 0, 0)
            controller.onPickerResult(emptyList())
        }
        composeRule.waitForIdle()
        assertEquals("* Lunch\n", textState.text.toString())
        assertFalse(File(vaultDir, "data").exists())
    }
}
