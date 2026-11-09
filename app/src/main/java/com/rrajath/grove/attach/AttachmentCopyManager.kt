package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.vault.FileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the picked files came from; decides the toast wording and the camera temp cleanup. */
enum class AttachKind { Camera, Photos, Files }

/** Everything one attach needs, captured when the picker returns. */
data class AttachRequest(
    /** Vault-relative `.org` path the owner lives in. */
    val orgFile: String,
    /** The owner as resolved against the editor buffer when the picker returned. */
    val owner: AttachOwner,
    /** A freshly generated `:ID:` to write into the owner, or null when it already has an ID or `:DIR:`. */
    val newId: String?,
    /** Vault-relative attach dir the files go into. */
    val dir: String,
    val kind: AttachKind,
    val sources: List<AttachSource>,
    /** Toast suffix: `Lunch` or `this file`. */
    val ownerLabel: String,
    /** True when the owner seen after the picker isn't the one the sheet was opened for. */
    val ownerChanged: Boolean = false,
    /**
     * Capture's file-level fallback: the owner is [orgFile]'s own file owner on
     * disk, not anything in the editor buffer, so a live editor links the files
     * with relative `[[file:…]]` links and writes [newId] to the file on disk.
     */
    val onDisk: Boolean = false,
)

/** A finished attach, handed to the editor (or the file fallback) to write the links. */
data class AttachDone(val request: AttachRequest, val result: ImportResult)

/** What the progress snackbar shows for the running copy. */
data class CopyProgress(
    val jobId: Long,
    val orgFile: String,
    val startedAtMillis: Long,
    val kind: AttachKind,
    val progress: ImportProgress,
)

/** Receives finished attaches for one org file while its editor is open. */
fun interface AttachSink {
    fun deliver(done: AttachDone)
}

/**
 * Application-scoped runner for attach copies (6.7). A copy outlives the
 * editor that started it: when the editor for [AttachRequest.orgFile] is still
 * registered as a sink it applies the links to its live buffer, otherwise
 * [fallback] writes them into the file on disk. One copy runs at a time.
 */
class AttachmentCopyManager(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val store: () -> FileStore?,
    private val opener: SourceOpener,
    /** Applies a finished attach to the file on disk when no editor is listening; returns a toast or null. */
    private val fallback: suspend (AttachDone) -> String?,
    /** Shows a message when no editor is listening (an app-level toast on device). */
    private val notify: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Resizes/converts Camera and Photos images (Settings § Attachments); null copies as-is. */
    private val photoProcessor: PhotoProcessor? = null,
    /** Current Photo size setting's long edge in px, or null for Original. Read once per copy. */
    private val photoLongEdge: suspend () -> Int? = { null },
    /** Called after a copy put new files in the vault, before its links are written. */
    private val onFilesAdded: () -> Unit = {},
) {
    private val _progress = MutableStateFlow<CopyProgress?>(null)
    val progress: StateFlow<CopyProgress?> = _progress

    /**
     * Listening editors per org file, newest last. Capture can sit on top of an
     * editor for its own target file; the newest one gets the result, and
     * closing it hands delivery back to the one below.
     */
    private val sinks = mutableMapOf<String, MutableList<AttachSink>>()
    private var job: Job? = null
    private var jobId = 0L

    val isBusy: Boolean get() = job?.isActive == true

    /** Registers [sink] for [orgFile]; close the handle when the editor goes away. */
    fun register(orgFile: String, sink: AttachSink): AutoCloseable {
        synchronized(sinks) { sinks.getOrPut(orgFile) { mutableListOf() }.add(sink) }
        return AutoCloseable {
            synchronized(sinks) {
                val list = sinks[orgFile] ?: return@synchronized
                list.removeAll { it === sink }
                if (list.isEmpty()) sinks.remove(orgFile)
            }
        }
    }

    /** Starts [request]. False when another copy is still running or there's no vault. */
    fun start(request: AttachRequest, onFinished: () -> Unit = {}): Boolean {
        if (isBusy) return false
        val fs = store() ?: return false
        val id = ++jobId
        val startedAt = clock()
        val importer = AttachmentImporter(fs, opener, photoProcessor)
        val pruneStop = request.orgFile.substringBeforeLast('/', "")
        job = scope.launch {
            try {
                val processPhotos = request.kind != AttachKind.Files
                val longEdge = if (processPhotos) photoLongEdge() else null
                val result = withContext(io) {
                    importer.import(request.dir, request.sources, pruneStop, processPhotos, longEdge) { p ->
                        _progress.value = CopyProgress(id, request.orgFile, startedAt, request.kind, p)
                    }
                }
                _progress.value = null
                if (result is ImportResult.Success) onFilesAdded()
                deliver(AttachDone(request, result))
            } finally {
                _progress.value = null
                onFinished()
            }
        }
        return true
    }

    /** Cancels the running copy. Its files are removed and nothing is delivered. */
    fun cancel() {
        job?.cancel()
        _progress.value = null
    }

    private suspend fun deliver(done: AttachDone) {
        val sink = synchronized(sinks) { sinks[done.request.orgFile]?.lastOrNull() }
        if (sink != null) {
            sink.deliver(done)
            return
        }
        val message = when (val r = done.result) {
            is ImportResult.Failed -> failureMessage(r)
            is ImportResult.Success -> withContext(io) { fallback(done) }
        }
        message?.let(notify)
    }
}

/** `Couldn't attach receipt.pdf: storage full`. */
fun failureMessage(r: ImportResult.Failed): String = "Couldn't attach ${r.name}: ${r.failure.reason}"

/**
 * The toast for a successful attach (6.6 / 1q). A rename only gets its own
 * toast for a single item; a multi-pick always shows the combined count.
 */
fun successMessage(request: AttachRequest, result: ImportResult.Success): String {
    val n = result.names.size
    if (request.ownerChanged) return "Heading changed. Attached to ${request.ownerLabel}"
    if (n > 1) {
        val noun = if (request.kind == AttachKind.Files) "files" else "photos"
        return "$n $noun attached to ${request.ownerLabel}"
    }
    val only = result.names.single()
    if (only in result.renamed) return "Saved as $only (name already in use)"
    return "Attached to ${request.ownerLabel}"
}

/** `Lunch` for a heading owner, `this file` for the file owner. */
fun ownerToastLabel(owner: AttachOwner): String = when (owner) {
    is AttachOwner.Heading -> owner.title
    is AttachOwner.File -> "this file"
}

/**
 * Writes the links for a finished attach into the whole-file [text] when the
 * editor that started it is gone: re-finds the owner (by `:ID:`, then by title
 * path, matched as a suffix since the editor buffer may have been a subtree),
 * adds the new `:ID:` if it still lacks one, and appends the links at the end of
 * the owner's own body. Null when a heading owner can't be found.
 */
fun appendAttachmentLinksToFile(text: String, owner: AttachOwner, newId: String?, names: List<String>): AttachInsertResult? {
    val doc = OrgParser.parse(text)
    val found = locateOwner(doc, owner) ?: return null
    val offset = bodyEndOffset(doc, found)
    val id = if (found.id == null && found.dirProperty == null) newId else null
    return insertAttachmentLinks(text, offset, found, id, names)
}

/**
 * Inserts links for [names] into the live editor [text]: re-finds [owner] (by
 * `:ID:`, then title path), adds [newId] when it still has neither `:ID:` nor
 * `:DIR:`, and puts the links at [cursor] when the cursor sits in the owner's
 * own section, else at the end of the owner's body (the files live in that
 * owner's dir, so the links must too). Null when the owner is gone.
 */
fun insertAttachmentLinksInBuffer(
    text: String,
    cursor: Int,
    owner: AttachOwner,
    newId: String?,
    names: List<String>,
): AttachInsertResult? {
    val doc = OrgParser.parse(text)
    val found = locateOwner(doc, owner) ?: return null
    val (sectionStart, sectionEnd) = ownSection(doc, found)
    val cursorLine = lineOfOffset(text, cursor)
    val at = if (cursorLine in sectionStart until sectionEnd) {
        cursor.coerceIn(0, text.length)
    } else {
        bodyEndOffset(doc, found)
    }
    val id = if (found.id == null && found.dirProperty == null) newId else null
    return insertAttachmentLinks(text, at, found, id, names)
}

/** Lines `[start, end)` holding the owner's headline/metadata and own body, children excluded. */
private fun ownSection(doc: OrgDocument, owner: AttachOwner): Pair<Int, Int> = when (owner) {
    is AttachOwner.File -> 0 to (doc.headlines.firstOrNull()?.lineIndex ?: doc.lines.size)
    is AttachOwner.Heading -> owner.lineIndex to
        (doc.headlines.firstOrNull { it.lineIndex > owner.lineIndex }?.lineIndex ?: doc.lines.size)
}

private fun locateOwner(doc: OrgDocument, owner: AttachOwner): AttachOwner? = when (owner) {
    is AttachOwner.File -> doc.fileOwner()
    is AttachOwner.Heading -> {
        val byId = owner.id?.let { id -> doc.headlines.firstOrNull { doc.ownerOf(it).id == id } }
        val match = byId ?: doc.headlines.firstOrNull { doc.titlePath(it).takeLast(owner.titlePath.size) == owner.titlePath }
        match?.let { doc.ownerOf(it) }
    }
}

/** The line just past the owner's last non-blank body line (before the next heading of any level). */
private fun ownBodyEnd(doc: OrgDocument, owner: AttachOwner): Int {
    val (start, next) = when (owner) {
        is AttachOwner.File -> doc.introStart to (doc.headlines.firstOrNull()?.lineIndex ?: doc.lines.size)
        is AttachOwner.Heading -> {
            val h = doc.headlines.first { it.lineIndex == owner.lineIndex }
            h.bodyStart to (doc.headlines.firstOrNull { it.lineIndex > h.lineIndex }?.lineIndex ?: doc.lines.size)
        }
    }
    var end = next
    while (end > start && doc.lines.getOrNull(end - 1)?.isBlank() == true) end--
    return end
}

/**
 * Where links appended to the owner's body go: the end of its last non-blank
 * body line (so a blank separator before the next heading survives), or the
 * start of the body when it's empty.
 */
private fun bodyEndOffset(doc: OrgDocument, owner: AttachOwner): Int {
    val end = ownBodyEnd(doc, owner)
    val bodyStart = when (owner) {
        is AttachOwner.File -> doc.introStart
        is AttachOwner.Heading -> doc.headlines.first { it.lineIndex == owner.lineIndex }.bodyStart
    }
    return if (end > bodyStart) lineStart(doc, end - 1) + doc.lines[end - 1].length else lineStart(doc, end)
}

private fun lineStart(doc: OrgDocument, line: Int): Int {
    if (line >= doc.lines.size) return doc.text.length
    var offset = 0
    for (i in 0 until line) offset += doc.lines[i].length + 1
    return offset
}
