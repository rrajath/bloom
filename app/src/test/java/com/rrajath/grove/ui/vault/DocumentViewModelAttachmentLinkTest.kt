package com.rrajath.grove.ui.vault

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.attach.AttachmentIntents
import com.rrajath.grove.data.FavoritesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.JvmFileStore
import com.rrajath.grove.vault.Vault
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tapping an `attachment:` / `file:` link in read mode (attachments flag on):
 * the file opens through the "Open with" chooser, and a missing one toasts
 * its name. Regression: every `attachment:` link toasted "Couldn't find".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, sdk = [34])
class DocumentViewModelAttachmentLinkTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<GroveApplication>()
    private val vaultDir by lazy { tmp.newFolder("vault") }

    /** A JVM store that hands out file URIs, standing in for SAF document URIs. */
    private val store: FileStore by lazy {
        val jvm = JvmFileStore(vaultDir, skipAttachShards = true)
        object : FileStore by jvm {
            override suspend fun contentUri(name: String): Uri? =
                File(vaultDir, name).takeIf { it.isFile }?.let { Uri.fromFile(it) }
        }
    }
    private val db: GroveDatabase by lazy {
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    }
    private val repoScope by lazy { CoroutineScope(mainDispatcherRule.dispatcher) }
    private val liveVms = mutableListOf<DocumentViewModel>()

    private val notebook = """
        * Lunch
        :PROPERTIES:
        :ID: abcdef
        :END:
        [[attachment:IMG_1.jpg]]
        [[file:data/ab/cdef/menu.pdf][Menu]]
    """.trimIndent() + "\n"

    @Before
    fun setUp() {
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
            addExtensionMimeTypeMapping("pdf", "application/pdf")
        }
        put("notes.org", notebook)
    }

    @After
    fun tearDown() {
        liveVms.forEach { it.viewModelScope.cancel() }
        repoScope.cancel()
        db.close()
    }

    private fun put(path: String, text: String = "x") =
        File(vaultDir, path).apply { parentFile?.mkdirs() }.writeText(text)

    private fun registerViewer(path: String, mime: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.example.viewer"; name = "Viewer" }
        }
        val intent = AttachmentIntents.viewIntent(Uri.fromFile(File(vaultDir, path)), mime)
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)
    }

    private fun TestScope.loaded(): DocumentViewModel {
        val vm = DocumentViewModel(
            vaultFlow = MutableStateFlow(Vault(store, parseDispatcher = mainDispatcherRule.dispatcher)),
            keywordsFlow = MutableStateFlow(OrgKeywords.DEFAULT),
            sync = FakeSyncTrigger(),
            database = db,
            settingsRepository = SettingsRepository(app, repoScope),
            favoritesRepository = FavoritesRepository(app),
            dispatchers = mainDispatcherRule.appDispatchers,
            app = app,
        ).also { liveVms += it }
        vm.load("notes.org")
        advanceUntilIdle()
        return vm
    }

    /** Taps [target] and returns the toasts it produced. */
    private fun TestScope.tap(vm: DocumentViewModel, target: String): List<String> {
        val toasts = mutableListOf<OutlineToast?>()
        val job = backgroundScope.launch { vm.toast.toList(toasts) }
        vm.openOrgLink(target, "notes.org", onOpenNote = { error("navigated") }, onOpenOutline = { error("navigated") })
        advanceUntilIdle()
        job.cancel()
        return toasts.mapNotNull { it?.message }
    }

    private fun startedViewIntent(): Intent {
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        return chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!
    }

    @Test
    fun `an attachment link opens the file from the heading's attach dir`() = runTest {
        put("data/ab/cdef/IMG_1.jpg")
        registerViewer("data/ab/cdef/IMG_1.jpg", "image/jpeg")
        val vm = loaded()

        assertEquals(emptyList<String>(), tap(vm, "attachment:IMG_1.jpg"))
        val view = startedViewIntent()
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertTrue(view.data!!.path!!.endsWith("vault/data/ab/cdef/IMG_1.jpg"))
        assertEquals("image/jpeg", view.type)
    }

    @Test
    fun `a file link into an attach dir opens the file`() = runTest {
        put("data/ab/cdef/menu.pdf")
        registerViewer("data/ab/cdef/menu.pdf", "application/pdf")
        val vm = loaded()

        assertEquals(emptyList<String>(), tap(vm, "file:data/ab/cdef/menu.pdf"))
        assertTrue(startedViewIntent().data!!.path!!.endsWith("vault/data/ab/cdef/menu.pdf"))
    }

    @Test
    fun `a missing attachment toasts its bare name`() = runTest {
        val vm = loaded()
        assertEquals(listOf("Couldn't find “IMG_1.jpg”"), tap(vm, "attachment:IMG_1.jpg"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `a missing file link toasts the file name, not a made-up org path`() = runTest {
        val vm = loaded()
        assertEquals(listOf("Couldn't find “menu.pdf”"), tap(vm, "file:data/ab/cdef/menu.pdf"))
    }

    @Test
    fun `an attachment with no viewer app says so`() = runTest {
        put("data/ab/cdef/IMG_1.jpg")
        val vm = loaded()
        assertEquals(listOf(AttachmentIntents.NO_HANDLER_MESSAGE), tap(vm, "attachment:IMG_1.jpg"))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
