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
import com.rrajath.grove.R
import com.rrajath.grove.attach.AttachDirResolver
import com.rrajath.grove.attach.AttachDone
import com.rrajath.grove.attach.AttachKind
import com.rrajath.grove.attach.AttachOwner
import com.rrajath.grove.attach.AttachRequest
import com.rrajath.grove.attach.AttachScope
import com.rrajath.grove.attach.AttachSource
import com.rrajath.grove.attach.AttachmentCopyManager
import com.rrajath.grove.attach.AttachmentFileProvider
import com.rrajath.grove.attach.AttachmentFiles
import com.rrajath.grove.attach.AttachmentIntents
import com.rrajath.grove.attach.AttachmentScopeScanner
import com.rrajath.grove.attach.ImportResult
import com.rrajath.grove.attach.ListedDirs
import com.rrajath.grove.attach.ScopedAttachment
import com.rrajath.grove.attach.failureMessage
import com.rrajath.grove.attach.fileLinkText
import com.rrajath.grove.attach.fileOwner
import com.rrajath.grove.attach.headingAtLine
import com.rrajath.grove.attach.insertAttachmentLinksInBuffer
import com.rrajath.grove.attach.lineOfOffset
import com.rrajath.grove.attach.linkableName
import com.rrajath.grove.attach.ownerOf
import com.rrajath.grove.attach.ownerToastLabel
import com.rrajath.grove.attach.relativeLinkPath
import com.rrajath.grove.attach.resolveOwner
import com.rrajath.grove.attach.scopesFor
import com.rrajath.grove.attach.successMessage
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.data.IndexedAttachOwner
import com.rrajath.grove.org.newOrgId
import com.rrajath.grove.ui.vault.OutlineToast
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the attach sheet shows: the owner, its dir, and the files listed for the selected scope. */
data class AttachSheetModel(
    val owner: AttachOwner,
    /** `**` for a level-2 heading, `#+` for the file owner. */
    val stars: String,
    /** Heading title, or the org file's name for the file owner. */
    val title: String,
    /** The owner's vault-relative attach dir when it exists, else null. */
    val dir: String?,
    /** Scopes the switch offers: no This heading for the file owner. */
    val scopes: List<AttachScope>,
    val scope: AttachScope,
    /** Every row for [scope], sorted; null while loading. */
    val rows: List<ScopedAttachment>?,
    /** How many of [rows] are shown (grows by [AttachController.PAGE_SIZE] per Load more). */
    val shown: Int,
    /** Capture's file-level fallback: [owner] is the target file's own owner, read from disk. */
    val onDisk: Boolean = false,
)

/** The rows on screen: the first [AttachSheetModel.shown]. */
val AttachSheetModel.visibleRows: List<ScopedAttachment> get() = rows.orEmpty().take(shown)

val AttachSheetModel.hasMore: Boolean get() = rows.orEmpty().size > shown

/**
 * Delete is offered only for the owner's own files (rows in its attach [dir]), in
 * every scope. Deleting another owner's file would silently break that owner's links.
 */
fun AttachSheetModel.canDelete(row: ScopedAttachment): Boolean =
    dir != null && row.dir == dir

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
    /** Capture's file-level fallback (see [AttachSheetModel.onDisk]). */
    val onDisk: Boolean = false,
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
    private val indexedOwners: suspend () -> List<IndexedAttachOwner> = { emptyList() },
    /**
     * Set by quick capture, whose buffer is only a snippet bound for [orgFile].
     * With no heading at the cursor, attach falls back to [orgFile]'s own file
     * owner on disk, and this writes a new file-level `:ID:` into it (false
     * when it couldn't). Null everywhere else: the buffer is the whole file.
     */
    private val addDiskFileId: (suspend (orgFile: String, id: String) -> Boolean)? = null,
    /**
     * The file-level `:ID:` the buffer will get when it's saved, though it isn't
     * in the text yet: a new Roam node capture adds its `%(id)` at write time.
     * An in-buffer file owner with no `:ID:` or `:DIR:` uses it instead of a
     * fresh ID, so the files land in the folder the saved file points at.
     */
    private val pendingFileId: String? = null,
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
    /** Rows per scope for the open sheet. */
    private val cache = mutableMapOf<AttachScope, List<ScopedAttachment>>()
    /** In-flight scans, at most one per scope, so toggling back to a loading scope doesn't rescan it. */
    private val scans = mutableMapOf<AttachScope, Job>()
    /**
     * Bumped by [invalidate] (open, close, delete), so a scan started before the
     * rows went stale never lands in the cache or on screen.
     */
    private var generation = 0

    /** Drops cached rows and in-flight scans: the sheet's file lists may have changed. */
    private fun invalidate() {
        generation++
        cache.clear()
        scans.values.forEach { it.cancel() }
        scans.clear()
    }

    val cameraAvailable: Boolean by lazy {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null
    }

    fun showToast(message: String) {
        toast = OutlineToast(message, ++toastId)
    }

    internal fun clearToast(id: Long) {
        if (toast?.id == id) toast = null
    }

    /** Vault paths of the files this editor copied in, oldest first (capture's discard deletes them). */
    private val added = androidx.compose.runtime.mutableStateListOf<String>()
    val addedFiles: List<String> get() = added

    /** The file-level `:ID:` the capture fallback wrote into [orgFile] on disk, or null. */
    var addedDiskFileId by mutableStateOf<String?>(null)
        private set

    /** Whether discarding would leave anything behind: files copied in, or a copy still running. */
    val hasSessionAttachments: Boolean
        get() = added.isNotEmpty() || manager?.isBusyFor(orgFile) == true

    /**
     * Capture's discard with "Also delete attachments": stops a running copy for
     * [orgFile] (its files are removed) and deletes every file this editor copied
     * in. The `:ID:` in [addedDiskFileId] is the caller's to remove.
     */
    fun discardSessionAttachments() {
        manager?.cancelFor(orgFile)
        manager?.discard(added.toList(), orgFile.substringBeforeLast('/', ""))
        added.clear()
    }

    /** [orgFile]'s text on disk, as last read by the capture fallback; null otherwise. */
    private var diskText: String? = null

    private val orgFileName: String get() = orgFile.substringAfterLast('/')

    /** [orgFile] read from the vault, or null when it doesn't exist or can't be read. */
    private suspend fun readDisk(store: FileStore): String? = withContext(Dispatchers.IO) {
        runCatching { if (store.exists(orgFile)) store.read(orgFile) else null }.getOrNull()
    }

    /** A system toast: the editor's own toast is hidden while the sheet is up. */
    private fun systemToast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** [owner] with [pendingFileId] filled in, for a file owner that has neither `:ID:` nor `:DIR:`. */
    private fun withPendingFileId(owner: AttachOwner): AttachOwner =
        if (pendingFileId != null && owner is AttachOwner.File && owner.id == null && owner.dirProperty == null) {
            owner.copy(id = pendingFileId)
        } else {
            owner
        }

    /** The buffer as the scans should see it: with [pendingFileId] as its file `:ID:` when it has none yet. */
    private fun bufferWithPendingFileId(): String {
        val text = textState.text.toString()
        val id = pendingFileId ?: return text
        val doc = OrgParser.parse(text)
        val file = doc.fileOwner()
        return if (file.id == null && file.dirProperty == null) OrgMutations.upsertFileProperty(doc, "ID", id) else text
    }

    /** Paperclip: resolve the owner at the cursor and open the sheet. */
    fun open() {
        val text = textState.text.toString()
        val doc = OrgParser.parse(text)
        val owner = withPendingFileId(resolveOwner(doc, textState.selection.start))
        if (addDiskFileId != null && owner is AttachOwner.File) {
            openOnDisk()
            return
        }
        val scopes = scopesFor(owner)
        val model = AttachSheetModel(
            owner = owner,
            stars = starsFor(doc, owner),
            title = when (owner) {
                is AttachOwner.Heading -> owner.title
                is AttachOwner.File -> orgFile.substringAfterLast('/')
            },
            dir = null,
            scopes = scopes,
            scope = scopes.first(),
            rows = null,
            shown = PAGE_SIZE,
        )
        invalidate()
        sheet = model
        load(model.scope)
    }

    /** Capture with no heading at the cursor: open the sheet for the target file itself. */
    private fun openOnDisk() {
        val store = storeProvider() ?: return
        scope.launch {
            val text = readDisk(store)
            if (text == null) {
                systemToast(context.getString(R.string.attach_capture_no_target, orgFileName))
                return@launch
            }
            diskText = text
            val owner = OrgParser.parse(text).fileOwner()
            val scopes = scopesFor(owner)
            invalidate()
            sheet = AttachSheetModel(
                owner = owner,
                stars = "#+",
                title = orgFileName,
                dir = null,
                scopes = scopes,
                scope = scopes.first(),
                rows = null,
                shown = PAGE_SIZE,
                onDisk = true,
            )
            systemToast(context.getString(R.string.attach_capture_file_fallback, orgFileName))
            load(scopes.first())
        }
    }

    fun close() {
        invalidate()
        sheet = null
    }

    /** Switch the list to [target]: the switch moves now, rows follow from the cache or a background scan. */
    fun selectScope(target: AttachScope) {
        val model = sheet ?: return
        if (target == model.scope || target !in model.scopes) return
        val cached = cache[target]
        sheet = model.copy(scope = target, rows = cached, shown = PAGE_SIZE)
        if (cached == null) load(target)
    }

    fun loadMore() {
        sheet = sheet?.let { it.copy(shown = it.shown + PAGE_SIZE) }
    }

    /**
     * Scans [target] on IO unless a scan for it is already running. The result
     * is cached for this sheet, but only shown while the sheet is still on [target];
     * a result from before the last [invalidate] is dropped.
     */
    private fun load(target: AttachScope) {
        val model = sheet ?: return
        if (scans[target]?.isActive == true) return
        val store = storeProvider() ?: return
        val started = generation
        val text = if (model.onDisk) diskText.orEmpty() else bufferWithPendingFileId()
        scans[target] = scope.launch {
            val (dir, rows) = withContext(Dispatchers.IO) {
                val files = AttachmentFiles(store)
                // Shard listings are read once per scan instead of one query per candidate dir.
                val scanner = AttachmentScopeScanner(files, AttachDirResolver(ListedDirs(store)))
                val ownDir = AttachDirResolver(files).readDir(orgFile, model.owner)
                val rows = when (target) {
                    AttachScope.HEADING -> ownDir?.let { files.list(it) }.orEmpty()
                        .sortedBy { it.name.substringAfterLast('/').lowercase() }
                        .map { ScopedAttachment(it, ownDir!!, orgFile, (model.owner as? AttachOwner.Heading)?.title, "") }
                    AttachScope.FILE -> scanner.scanFile(orgFile, OrgParser.parse(text))
                    AttachScope.VAULT -> scanner.scanVault(orgFile, OrgParser.parse(text), indexedOwners())
                }
                ownDir to rows
            }
            if (generation != started) return@launch
            scans.remove(target)
            cache[target] = rows
            val current = sheet ?: return@launch
            sheet = if (current.scope == target) current.copy(dir = dir, rows = rows) else current.copy(dir = dir)
        }
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
            onDisk = model.onDisk,
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
        val (owner, changed) = if (p.onDisk) {
            val text = readDisk(store)
            if (text == null) {
                cameraFile?.delete()
                showToast(context.getString(R.string.attach_capture_no_target, orgFileName))
                return
            }
            OrgParser.parse(text).fileOwner() to false
        } else {
            relocatePending(OrgParser.parse(textState.text.toString()), p).let { (o, ch) -> withPendingFileId(o) to ch }
        }
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
            ownerLabel = if (p.onDisk) orgFileName else ownerToastLabel(owner),
            ownerChanged = changed,
            onDisk = p.onDisk,
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
                r.names.forEach { added += "${done.request.dir}/$it" }
                if (done.request.onDisk) {
                    scope.launch { linkOnDisk(done.request, r) }
                    return
                }
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

    /**
     * Capture's file-level fallback finished: give the target file its new
     * `:ID:` on disk, then link the files from the snippet with `[[file:…]]`
     * links relative to it, since the snippet has no owner of its own.
     */
    private suspend fun linkOnDisk(request: AttachRequest, result: ImportResult.Success) {
        val idSaved = request.newId?.let { id -> addDiskFileId?.invoke(orgFile, id) == true } ?: true
        if (idSaved && request.newId != null) addedDiskFileId = request.newId
        val block = result.names.joinToString("\n") { fileLinkText(relativeLinkPath(orgFile, "${request.dir}/$it")) }
        val cursor = textState.selection.start.coerceIn(0, textState.text.length)
        val text = textState.text.toString()
        val prefix = if (cursor == 0 || text[cursor - 1] == '\n') "" else "\n"
        val suffix = if (cursor == text.length || text[cursor] == '\n') "" else "\n"
        val inserted = prefix + block + suffix
        textState.edit {
            replace(cursor, cursor, inserted)
            selection = TextRange(cursor + prefix.length + block.length)
        }
        showToast(
            if (idSaved) successMessage(request, result)
            else context.getString(R.string.attach_capture_id_failed, orgFileName),
        )
    }

    /**
     * List row: the owner's own file gets `[[attachment:name]]` (no ID change);
     * another owner's file gets a `[[file:…]]` link relative to this org file.
     */
    fun insertExisting(row: ScopedAttachment) {
        val model = sheet ?: return
        val name = row.file.name.substringAfterLast('/')
        // The capture fallback's owner isn't in the buffer, so even its own files get file links.
        if (row.dir == model.dir && !model.onDisk) {
            val edit = insertAttachmentLinksInBuffer(
                textState.text.toString(), textState.selection.start, model.owner, null, listOf(name),
            ) ?: return
            sheet = null
            textState.edit {
                replace(0, length, edit.text)
                selection = TextRange(edit.cursor)
            }
            showToast(context.getString(R.string.attach_linked, name))
            return
        }
        if (!linkableName(row.file.name)) {
            val reason = if ("::" in row.file.name) R.string.attach_link_colons_refused else R.string.attach_link_bracket_refused
            showToast(context.getString(reason))
            return
        }
        val link = fileLinkText(relativeLinkPath(orgFile, row.file.name))
        val cursor = textState.selection.start
        sheet = null
        textState.edit {
            replace(cursor, cursor, link)
            selection = TextRange(cursor + link.length)
        }
        val source = row.ownerTitle ?: row.ownerOrgFile.substringAfterLast('/')
        showToast(context.getString(R.string.attach_linked_from, name, source))
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
            invalidate()
            sheet?.let { load(it.scope) }
        }
    }

    companion object {
        const val PAGE_SIZE = 10
        private val CAMERA_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}

/** `**` for a level-2 heading owner, `#+` for the file owner. */
internal fun starsFor(doc: OrgDocument, owner: AttachOwner): String = when (owner) {
    is AttachOwner.File -> "#+"
    is AttachOwner.Heading -> "*".repeat(doc.headlines.firstOrNull { it.lineIndex == owner.lineIndex }?.level ?: 1)
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
fun rememberAttachController(
    textState: TextFieldState,
    orgFile: String,
    bufferReady: Boolean,
    /** Quick capture only: see [AttachController]'s `addDiskFileId`. */
    addDiskFileId: (suspend (orgFile: String, id: String) -> Boolean)? = null,
    /** Quick capture only: see [AttachController]'s `pendingFileId`. */
    pendingFileId: String? = null,
): AttachController {
    val app = LocalContext.current.applicationContext as? GroveApplication
    return rememberAttachController(
        textState, orgFile, bufferReady,
        manager = app?.attachmentCopyManager,
        indexedOwners = { app?.database?.indexDao()?.attachOwners().orEmpty() },
        storeProvider = { app?.fileStore?.value },
        addDiskFileId = addDiskFileId,
        pendingFileId = pendingFileId,
    )
}

@Composable
internal fun rememberAttachController(
    textState: TextFieldState,
    orgFile: String,
    bufferReady: Boolean,
    manager: AttachmentCopyManager?,
    indexedOwners: suspend () -> List<IndexedAttachOwner> = { emptyList() },
    addDiskFileId: (suspend (orgFile: String, id: String) -> Boolean)? = null,
    pendingFileId: String? = null,
    storeProvider: () -> FileStore?,
): AttachController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(textState, orgFile) {
        AttachController(context, scope, textState, orgFile, manager, storeProvider, indexedOwners, addDiskFileId, pendingFileId)
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
