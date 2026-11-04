package com.rrajath.grove.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.attach.AttachDirResolver
import com.rrajath.grove.attach.AttachDone
import com.rrajath.grove.attach.AttachKind
import com.rrajath.grove.attach.AttachOwner
import com.rrajath.grove.attach.AttachRequest
import com.rrajath.grove.attach.AttachSource
import com.rrajath.grove.attach.AttachmentCopyManager
import com.rrajath.grove.attach.AttachmentFileProvider
import com.rrajath.grove.attach.AttachmentFiles
import com.rrajath.grove.attach.AttachmentIntents
import com.rrajath.grove.attach.ImportResult
import com.rrajath.grove.attach.failureMessage
import com.rrajath.grove.attach.fileOwner
import com.rrajath.grove.attach.headingAtLine
import com.rrajath.grove.attach.insertAttachmentLinksInBuffer
import com.rrajath.grove.attach.lineOfOffset
import com.rrajath.grove.attach.ownerOf
import com.rrajath.grove.attach.ownerToastLabel
import com.rrajath.grove.attach.resolveOwner
import com.rrajath.grove.attach.successMessage
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.newOrgId
import com.rrajath.grove.ui.vault.OutlineToast
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the attach sheet shows: the owner, its dir, and the files already in it. */
data class AttachSheetModel(
    val owner: AttachOwner,
    /** `**` for a level-2 heading, `#+` for the file owner. */
    val stars: String,
    /** Heading title, or the org file's name for the file owner. */
    val title: String,
    /** Vault-relative attach dir when it exists, else null. */
    val dir: String?,
    /** `data/8B/41D2F0…` or `no :ID: yet`. */
    val dirLabel: String,
    /** Null while loading. */
    val files: List<FileEntry>?,
)

/**
 * The owner an attach started for, saved across process death next to the
 * activity-result launchers (6.7) so a result delivered to a recreated editor
 * can re-find it in the reloaded buffer.
 */
@Serializable
internal data class PendingAttach(
    val kind: String,
    val isFile: Boolean,
    val id: String?,
    val titlePath: List<String>,
    val lineIndex: Int,
    val cursor: Int,
    /** Camera temp file name inside `cacheDir/attach-camera`. */
    val cameraFile: String? = null,
)

/** Picker results waiting for the buffer to load (after a process death). */
internal data class PendingResult(val pending: PendingAttach, val uris: List<Uri>)

/**
 * Edit-mode attach (1c) for one editor field: the sheet, the three source
 * launchers, the list actions, and applying finished copies to the buffer.
 * Created by [rememberAttachController]; the editor renders it with
 * [AttachOverlays].
 */
@Stable
class AttachController internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val textState: TextFieldState,
    val orgFile: String,
    internal val manager: AttachmentCopyManager?,
    private val storeProvider: () -> FileStore?,
) {
    var sheet by mutableStateOf<AttachSheetModel?>(null)
        private set
    var toast by mutableStateOf<OutlineToast?>(null)
        private set
    var confirmDelete by mutableStateOf<FileEntry?>(null)
    internal var pending: PendingAttach? = null
    /** Picker results not yet processed (waiting for the buffer after a process death). */
    private var waiting by mutableStateOf<PendingResult?>(null)
    internal var launchCamera: (Uri) -> Unit = {}
    internal var launchPhotos: () -> Unit = {}
    internal var launchFiles: () -> Unit = {}
    private var toastId = 0L

    val cameraAvailable: Boolean by lazy {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null
    }

    fun showToast(message: String) {
        toast = OutlineToast(message, ++toastId)
    }

    internal fun clearToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    /** Paperclip: resolve the owner at the cursor and open the sheet. */
    fun open() {
        val text = textState.text.toString()
        val doc = OrgParser.parse(text)
        val owner = resolveOwner(doc, textState.selection.start)
        val model = AttachSheetModel(
            owner = owner,
            stars = starsFor(doc, owner),
            title = when (owner) {
                is AttachOwner.Heading -> owner.title
                is AttachOwner.File -> orgFile.substringAfterLast('/')
            },
            dir = null,
            dirLabel = if (owner.id == null && owner.dirProperty == null) NO_ID_LABEL else "",
            files = null,
        )
        sheet = model
        scope.launch { refreshList(model) }
    }

    fun close() {
        sheet = null
    }

    private suspend fun refreshList(model: AttachSheetModel) {
        val store = storeProvider() ?: return
        val files = AttachmentFiles(store)
        val resolver = AttachDirResolver(files)
        val (dir, list, label) = withContext(Dispatchers.IO) {
            val read = resolver.readDir(orgFile, model.owner)
            val shown = read ?: resolver.writeDir(orgFile, model.owner)
            Triple(
                read,
                read?.let { files.list(it) }.orEmpty().sortedBy { it.name.substringAfterLast('/').lowercase() },
                shown?.let { dirLabel(orgFile, it) } ?: NO_ID_LABEL,
            )
        }
        if (sheet?.owner == model.owner) sheet = model.copy(dir = dir, dirLabel = label, files = list)
    }

    /** One of the three source pills. */
    fun pick(kind: AttachKind) {
        val model = sheet ?: return
        if (manager?.isBusy == true) return
        val owner = model.owner
        var p = PendingAttach(
            kind = kind.name,
            isFile = owner is AttachOwner.File,
            id = owner.id,
            titlePath = (owner as? AttachOwner.Heading)?.titlePath.orEmpty(),
            lineIndex = (owner as? AttachOwner.Heading)?.lineIndex ?: -1,
            cursor = textState.selection.start,
        )
        sheet = null
        when (kind) {
            AttachKind.Camera -> {
                val name = "IMG_" + LocalDateTime.now().format(CAMERA_STAMP) + ".jpg"
                val file = File(AttachmentFileProvider.cameraDir(context), name)
                p = p.copy(cameraFile = name)
                pending = p
                launchCamera(AttachmentFileProvider.uriFor(context, file))
            }
            AttachKind.Photos -> {
                pending = p
                launchPhotos()
            }
            AttachKind.Files -> {
                pending = p
                launchFiles()
            }
        }
    }

    /** A launcher delivered [uris] for the pending attach (empty on cancel). */
    internal fun onPickerResult(uris: List<Uri>) {
        val p = pending ?: return
        pending = null
        waiting = PendingResult(p, uris)
    }

    /**
     * Starts processing a waiting result once the editor buffer is loaded. The
     * work runs on the controller's scope, not in the calling effect: the effect
     * is keyed on [waiting], so clearing it would cancel work run inside it.
     */
    internal fun processWaiting(bufferReady: Boolean) {
        val w = waiting ?: return
        if (!bufferReady) return
        waiting = null
        scope.launch { onPicked(w.pending, w.uris) }
    }

    internal val hasWaiting: Boolean get() = waiting != null

    /**
     * A picker returned. Re-finds the owner in the live buffer (ID, then title
     * path, then the heading at the saved cursor), names the sources, and
     * starts the copy. Empty [uris] (cancel) does nothing.
     */
    internal suspend fun onPicked(p: PendingAttach, uris: List<Uri>) {
        val kind = AttachKind.valueOf(p.kind)
        val cameraFile = p.cameraFile?.let { File(AttachmentFileProvider.cameraDir(context), it) }
        if (uris.isEmpty()) {
            cameraFile?.delete()
            return
        }
        val store = storeProvider() ?: return
        val mgr = manager ?: return
        val doc = OrgParser.parse(textState.text.toString())
        val (owner, changed) = relocatePending(doc, p)
        val sources = withContext(Dispatchers.IO) {
            if (cameraFile != null) {
                listOf(AttachSource(Uri.fromFile(cameraFile).toString(), cameraFile.name, cameraFile.length(), "image/jpeg"))
            } else {
                uris.map { describe(context, it) }
            }
        }
        val newId = if (owner.id == null && owner.dirProperty == null) newOrgId() else null
        val writeOwner = when (owner) {
            is AttachOwner.Heading -> owner.copy(id = owner.id ?: newId)
            is AttachOwner.File -> owner.copy(id = owner.id ?: newId)
        }
        val dir = withContext(Dispatchers.IO) { AttachDirResolver(AttachmentFiles(store)).writeDir(orgFile, writeOwner) }
        if (dir == null) {
            cameraFile?.delete()
            showToast("Couldn't attach ${sources.first().displayName}: couldn't write to vault")
            return
        }
        val request = AttachRequest(
            orgFile = orgFile,
            owner = owner,
            newId = newId,
            dir = dir,
            kind = kind,
            sources = sources,
            ownerLabel = ownerToastLabel(owner),
            ownerChanged = changed,
        )
        val started = mgr.start(request, onFinished = { cameraFile?.delete() })
        if (!started) cameraFile?.delete()
    }

    private fun relocatePending(doc: OrgDocument, p: PendingAttach): Pair<AttachOwner, Boolean> {
        if (p.isFile) return doc.fileOwner() to false
        val byId = p.id?.let { id -> doc.headlines.firstOrNull { doc.ownerOf(it).id == id } }
        val match = byId ?: doc.headlines.firstOrNull { doc.ownerOf(it).titlePath == p.titlePath }
        if (match != null) return doc.ownerOf(match) to false
        val h = doc.headingAtLine(lineOfOffset(doc.text, p.cursor))
        return (h?.let { doc.ownerOf(it) } ?: doc.fileOwner()) to true
    }

    /** A copy finished while this editor is open: write the links, or report the failure. */
    internal fun onDone(done: AttachDone) {
        when (val r = done.result) {
            is ImportResult.Failed -> showToast(failureMessage(r))
            is ImportResult.Success -> {
                val edit = insertAttachmentLinksInBuffer(
                    textState.text.toString(), textState.selection.start,
                    done.request.owner, done.request.newId, r.names,
                )
                if (edit == null) {
                    showToast("Attached files kept, but the heading was not found")
                    return
                }
                // One edit, so editor undo treats the ID + links as a single step.
                textState.edit {
                    replace(0, length, edit.text)
                    selection = TextRange(edit.cursor)
                }
                showToast(successMessage(done.request, r))
            }
        }
    }

    /** List row: insert `[[attachment:name]]` at the cursor (no ID change). */
    fun insertExisting(file: FileEntry) {
        val model = sheet ?: return
        val name = file.name.substringAfterLast('/')
        val edit = insertAttachmentLinksInBuffer(
            textState.text.toString(), textState.selection.start, model.owner, null, listOf(name),
        ) ?: return
        sheet = null
        textState.edit {
            replace(0, length, edit.text)
            selection = TextRange(edit.cursor)
        }
        showToast("Linked $name")
    }

    /** A content URI the sheet's image thumbnails load from; null when the file can't be resolved. */
    suspend fun thumbnailUri(file: FileEntry): Uri? {
        val store = storeProvider() ?: return null
        return withContext(Dispatchers.IO) { runCatching { store.contentUri(file.name) }.getOrNull() }
    }

    fun openWith(file: FileEntry) {
        val store = storeProvider() ?: return
        scope.launch {
            when (AttachmentIntents.open(context, store, file.name)) {
                AttachmentIntents.Result.NoHandler -> showToast(AttachmentIntents.NO_HANDLER_MESSAGE)
                AttachmentIntents.Result.Missing -> showToast("File not found: ${file.name.substringAfterLast('/')}")
                AttachmentIntents.Result.Launched -> Unit
            }
        }
    }

    /** Confirmed delete from the list: remove the file, prune emptied shard dirs, refresh. */
    fun delete(file: FileEntry) {
        confirmDelete = null
        val model = sheet ?: return
        val store = storeProvider() ?: return
        val name = file.name.substringAfterLast('/')
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val files = AttachmentFiles(store)
                val deleted = files.delete(file.name)
                if (deleted && model.dir != null) files.pruneEmpty(model.dir, orgFile.substringBeforeLast('/', ""))
                deleted
            }
            showToast(if (ok) "Deleted $name" else "Couldn't delete $name")
            sheet?.let { refreshList(it) }
        }
    }

    companion object {
        const val NO_ID_LABEL = "no :ID: yet"
        private val CAMERA_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}

/** `**` for a level-2 heading owner, `#+` for the file owner. */
internal fun starsFor(doc: OrgDocument, owner: AttachOwner): String = when (owner) {
    is AttachOwner.File -> "#+"
    is AttachOwner.Heading -> "*".repeat(doc.headlines.firstOrNull { it.lineIndex == owner.lineIndex }?.level ?: 1)
}

/**
 * The sheet's dir label (1c): the attach dir relative to the org file's
 * folder, with the last segment cut to 6 characters plus an ellipsis, e.g.
 * `data/8B/41D2F0…`.
 */
internal fun dirLabel(orgFile: String, dir: String): String {
    val orgDir = orgFile.substringBeforeLast('/', "")
    val rel = if (orgDir.isNotEmpty() && dir.startsWith("$orgDir/")) dir.removePrefix("$orgDir/") else dir
    val head = rel.substringBeforeLast('/', "")
    val leaf = rel.substringAfterLast('/')
    val cut = if (leaf.length > 6) leaf.take(6) + "…" else leaf
    return if (head.isEmpty()) cut else "$head/$cut"
}

/** Name and size of a picked document. */
private fun describe(context: Context, uri: Uri): AttachSource {
    var name: String? = null
    var size: Long? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it >= 0 }
                }
            }
    }
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    return AttachSource(uri.toString(), name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment", size, mime)
}

private val pendingJson = Json { ignoreUnknownKeys = true }

/**
 * Sets up attach for one editor field. [bufferReady] is false until the
 * editor has loaded its buffer; picker results delivered before that (a
 * recreated activity after a process death) wait for it.
 */
@Composable
fun rememberAttachController(textState: TextFieldState, orgFile: String, bufferReady: Boolean): AttachController {
    val app = LocalContext.current.applicationContext as? GroveApplication
    return rememberAttachController(
        textState, orgFile, bufferReady,
        manager = app?.attachmentCopyManager,
        storeProvider = { app?.fileStore?.value },
    )
}

@Composable
internal fun rememberAttachController(
    textState: TextFieldState,
    orgFile: String,
    bufferReady: Boolean,
    manager: AttachmentCopyManager?,
    storeProvider: () -> FileStore?,
): AttachController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(textState, orgFile) {
        AttachController(context, scope, textState, orgFile, manager, storeProvider)
    }
    // Saved as JSON so it survives process death without the parcelize plugin.
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    if (controller.pending == null) {
        saved?.let { s -> controller.pending = runCatching { pendingJson.decodeFromString<PendingAttach>(s) }.getOrNull() }
    }

    fun deliver(uris: List<Uri>) {
        saved = null
        controller.onPickerResult(uris)
    }

    fun persistPending() {
        saved = controller.pending?.let { pendingJson.encodeToString(PendingAttach.serializer(), it) }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = controller.pending?.cameraFile?.let { File(AttachmentFileProvider.cameraDir(context), it) }
        deliver(if (ok && file != null && file.length() > 0) listOf(Uri.fromFile(file)) else emptyList())
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { deliver(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { deliver(it) }

    controller.launchCamera = { uri ->
        persistPending()
        camera.launch(uri)
    }
    controller.launchPhotos = {
        persistPending()
        photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    controller.launchFiles = {
        persistPending()
        files.launch(arrayOf("*/*"))
    }

    LaunchedEffect(controller.hasWaiting, bufferReady) { controller.processWaiting(bufferReady) }

    DisposableEffect(controller) {
        val handle = controller.manager?.register(orgFile) { done -> controller.onDone(done) }
        onDispose { handle?.close() }
    }

    val toast = controller.toast
    LaunchedEffect(toast?.id) {
        val id = toast?.id ?: return@LaunchedEffect
        delay(TOAST_MILLIS)
        controller.clearToast(id)
    }
    return controller
}

private const val MAX_PHOTOS = 20
private const val TOAST_MILLIS = 1900L
