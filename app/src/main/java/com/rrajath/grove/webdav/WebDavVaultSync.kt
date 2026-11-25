// i18n: sync-log messages and internal state, never shown as UI text
package com.rrajath.grove.webdav

import com.rrajath.grove.sync.SyncCoalescer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Serializable
private data class FileState(val hash: String, val revision: String)

@Serializable
private data class StateFile(val files: Map<String, FileState> = emptyMap())

data class WebDavSyncResult(
    val pulled: Int = 0,
    val pushed: Int = 0,
    val deletedRemote: Int = 0,
    val deletedLocal: Int = 0,
    val conflicts: Int = 0,
) {
    val changed: Int get() = pulled + pushed + deletedRemote + deletedLocal + conflicts
}

/**
 * Two-way sync between a WebDAV collection and a local mirror directory.
 *
 * The mirror is a real directory served by
 * [JvmFileStore][com.rrajath.grove.vault.JvmFileStore], so the rest of the app
 * keeps working unchanged (and offline): the index, editor, attachments,
 * reminders and search all read local files. Before every [SyncManager][com.rrajath.grove.sync.SyncManager]
 * pass the remote and mirror are reconciled, then the existing revision-diffed
 * indexer picks up whatever changed on disk.
 *
 * Change detection uses a per-account state file: the SHA-256 of the content
 * both sides last agreed on, plus the remote ETag (or `mtime:size` when the
 * server has no ETag). A file that differs from that baseline only locally is
 * pushed, only remotely is pulled, and on both sides becomes a
 * `*.sync-conflict-YYYYMMDD-HHMMSS-DEVICE.org` copy — the same naming the
 * existing conflict notification and resolution UI understands. The remote
 * version wins at the original path; the local edit is preserved and uploaded
 * as the conflict copy.
 *
 * Deletions propagate too, but a deletion on one side never discards an edit
 * on the other: the edited side is resurrected instead.
 */
class WebDavVaultSync(
    private val scope: CoroutineScope,
    private val mirrorRoot: File,
    private val stateDir: File,
    /**
     * Resolves the mirror for one vault. Production keys it by URL + account so
     * switching collections never treats another vault's mirror as local work.
     * Tests may keep the legacy single-directory behaviour.
     */
    private val mirrorRootForConfig: (WebDavConfig) -> File = { mirrorRoot },
    private val configProvider: suspend () -> WebDavConfig?,
    private val clientFactory: (WebDavConfig) -> WebDavClient = { HttpWebDavClient(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val deviceTag: String = "ANDROID",
    private val onLog: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }

    /** Created on first [requestSync], so a JVM test that only calls [sync] leaks no coroutine. */
    private val coalescer by lazy {
        SyncCoalescer(scope, 1_500.milliseconds) { _ ->
            runCatching { sync() }.onFailure { onLog("webdav background sync failed: ${it.message}") }
        }
    }

    init {
        mirrorRoot.mkdirs()
        stateDir.mkdirs()
    }

    /** Debounced background pass (after an in-app edit, or a config change). */
    fun requestSync(reason: String) {
        coalescer.request(reason)
    }

    /** Set after a 503/429 so repeated triggers cannot keep hammering the server. */
    @Volatile
    private var rateLimitedUntilMs = 0L

    @Volatile
    private var rateLimitedStatus = 503

    /** Full two-way pass, awaited by the sync orchestrator before it reindexes. */
    suspend fun sync(configOverride: WebDavConfig? = null): WebDavSyncResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                checkCooldown()
                try {
                    syncLocked(configOverride)
                } catch (error: WebDavHttpException) {
                    throw rememberServerBusy(error)
                }
            }
        }

    /**
     * Connectivity check from Settings. One Depth:1 PROPFIND on the collection
     * root: enough to verify TLS, credentials and that the folder exists, without
     * walking a whole drive. Deliberately does not take [mutex] — a connectivity
     * probe must not queue behind a running full sync — and is time-bounded so
     * the UI can never sit in "busy" forever on an unreachable server.
     */
    suspend fun test(configOverride: WebDavConfig? = null): Int =
        withContext(Dispatchers.IO) {
            withTimeout(TEST_TIMEOUT_MS) {
                checkCooldown()
                val config = configOverride ?: configProvider()
                    ?: throw WebDavException("WebDAV is not configured")
                try {
                    clientFactory(config).listShallow("").count { !it.isDir }
                } catch (error: WebDavHttpException) {
                    throw rememberServerBusy(error)
                }
            }
        }

    /** Skip WebDAV requests during the cooldown after a server-busy response. */
    private fun checkCooldown() {
        val remaining = rateLimitedUntilMs - System.currentTimeMillis()
        if (remaining > 0) {
            throw WebDavRateLimitedException(
                ((remaining + 999) / 1000).toInt().coerceAtLeast(1),
                rateLimitedStatus,
            )
        }
    }

    private fun rememberServerBusy(error: WebDavHttpException): Throwable =
        if (error.code == 503 || error.code == 429) {
            rateLimitedStatus = error.code
            rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
            WebDavRateLimitedException((RATE_LIMIT_COOLDOWN_MS / 1000).toInt(), error.code)
        } else {
            error
        }

    companion object {
        // Two DNS addresses x 10s connect + one PROPFIND must fit, so a user on
        // a network where one A record is blocked still sees the other tried.
        private const val TEST_TIMEOUT_MS = 45_000L
        // Nutstore (and other hosted WebDAV services) answer 503 when a client
        // polls too often. Backing off locally is kinder than immediate retries.
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
    }

    /** Forget all baseline state for [config] (the next pass re-reconciles from scratch). */
    fun resetState(config: WebDavConfig) {
        stateFile(config).delete()
    }

    private suspend fun syncLocked(configOverride: WebDavConfig?): WebDavSyncResult {
        val config = configOverride ?: configProvider() ?: return WebDavSyncResult()
        val client = clientFactory(config)
        // Resolve everything for this vault once, so a config change mid-pass
        // cannot mix one collection's mirror with another's baseline.
        val mirrorRoot = mirrorRootForConfig(config).apply { mkdirs() }
        val statePath = stateFile(config)
        val previous = readState(statePath)
        val next = LinkedHashMap(previous)
        val remote = client.list()
        val local = localFiles(mirrorRoot)
        val counters = Counters()

        for (path in (remote.keys + local.keys + previous.keys).toSortedSet()) {
            if (!WebDavUrl.isSafeRelative(path)) continue
            val localFile = local[path]
            val remoteEntry = remote[path]
            val base = previous[path]

            val localBytes = localFile?.readBytes()
            val localHash = localBytes?.let(::hash)
            val localChanged = if (base == null) localFile != null else localHash != base.hash
            val remoteChanged = if (base == null) remoteEntry != null
                else remoteEntry?.revision != base.revision
            // A server that returns neither ETag nor mtime nor size gives every
            // file the same "0:0" revision, so metadata alone can never detect a
            // remote edit. Those files are hashed on every pass instead.
            val reliableMetadata = remoteEntry?.hasUsableRevision() ?: true
            val needsDownload = remoteEntry != null && (remoteChanged || !reliableMetadata)
            // Download only what changed remotely. Comparing hashes avoids treating a
            // metadata-only ETag change as a content edit.
            val remoteBytes = if (needsDownload) client.get(path) else null
            val remoteHash = remoteBytes?.let(::hash)
            val remoteModified = remoteEntry != null && needsDownload &&
                (base == null || remoteHash != base.hash)
            // Vanished between PROPFIND and GET (an edit on another device):
            // leave it to the next pass instead of failing the whole sync.
            if (remoteEntry != null && needsDownload && remoteBytes == null) continue

            when {
                localFile != null && remoteEntry != null -> when {
                    // Same bytes on both sides (e.g. first run on two copies of one vault).
                    localHash != null && localHash == remoteHash ->
                        next[path] = FileState(localHash, remoteEntry.revision)
                    !localChanged && !remoteModified ->
                        next[path] = FileState(base?.hash ?: localHash!!, remoteEntry.revision)
                    localChanged && !remoteModified ->
                        pushOrConflict(client, path, localBytes!!, remoteEntry, next, counters, local, mirrorRoot)
                    !localChanged && remoteModified ->
                        pull(remoteBytes, remoteEntry, next, counters, mirrorRoot)
                    else ->
                        conflict(client, path, localBytes!!, remoteBytes, remoteEntry, next, counters, local, mirrorRoot)
                }

                localFile == null && remoteEntry != null -> {
                    if (base == null || remoteModified) {
                        pull(remoteBytes ?: client.get(path), remoteEntry, next, counters, mirrorRoot)
                    } else {
                        // Local deletion, remote untouched: remove the remote copy.
                        // A 412 here means it changed again after our PROPFIND;
                        // leave the state alone so the next pass re-evaluates it.
                        val removed = runCatching { client.delete(path, remoteEntry.etag) }.isSuccess
                        if (removed) {
                            next.remove(path)
                            counters.deletedRemote++
                        }
                    }
                }

                localFile != null -> {
                    if (base == null || localChanged) {
                        // New local file, or local edits survive a remote deletion.
                        pushOrConflict(client, path, localBytes!!, null, next, counters, local, mirrorRoot)
                    } else {
                        // Remote deletion, local untouched.
                        localFile.delete()
                        next.remove(path)
                        counters.deletedLocal++
                    }
                }

                else -> next.remove(path)
            }
        }

        saveState(statePath, next)
        val result = WebDavSyncResult(
            pulled = counters.pulled,
            pushed = counters.pushed,
            deletedRemote = counters.deletedRemote,
            deletedLocal = counters.deletedLocal,
            conflicts = counters.conflicts,
        )
        onLog("webdav sync: ${result.pulled} pulled, ${result.pushed} pushed, ${result.conflicts} conflicts")
        return result
    }

    private suspend fun pushOrConflict(
        client: WebDavClient,
        path: String,
        bytes: ByteArray,
        remoteEntry: WebDavEntry?,
        next: MutableMap<String, FileState>,
        counters: Counters,
        local: Map<String, File>,
        mirrorRoot: File,
    ) {
        try {
            val uploaded = client.put(path, bytes, remoteEntry?.etag)
            next[path] = FileState(hash(bytes), uploaded.revision)
            counters.pushed++
        } catch (e: WebDavPreconditionFailed) {
            // The remote changed after our PROPFIND. Fetch it: identical bytes
            // mean a harmless race, different bytes mean a real conflict.
            val remoteBytes = client.get(path) ?: throw e
            val current = client.stat(path) ?: remoteEntry
            if (hash(remoteBytes) == hash(bytes)) {
                next[path] = FileState(hash(bytes), current?.revision ?: remoteEntry?.revision.orEmpty())
            } else {
                val entry = current ?: remoteEntry ?: WebDavEntry(
                    path, false, null, 0L, remoteBytes.size.toLong(),
                )
                conflict(client, path, bytes, remoteBytes, entry, next, counters, local, mirrorRoot)
            }
        }
    }

    private fun pull(
        bytes: ByteArray?,
        entry: WebDavEntry,
        next: MutableMap<String, FileState>,
        counters: Counters,
        mirrorRoot: File,
    ) {
        val content = bytes ?: throw WebDavException("Remote file is unreadable: ${entry.path}")
        writeLocal(mirrorRoot, entry.path, content, entry.lastModified)
        next[entry.path] = FileState(hash(content), entry.revision)
        counters.pulled++
    }

    private suspend fun conflict(
        client: WebDavClient,
        path: String,
        localBytes: ByteArray,
        remoteBytes: ByteArray?,
        remoteEntry: WebDavEntry,
        next: MutableMap<String, FileState>,
        counters: Counters,
        local: Map<String, File>,
        mirrorRoot: File,
    ) {
        val remote = remoteBytes ?: client.get(path)
            ?: throw WebDavException("Remote file is unreadable: $path")
        // Remote wins at the original path; local content is preserved under a
        // name SyncConflicts.detect() already understands.
        writeLocal(mirrorRoot, path, remote, remoteEntry.lastModified)
        next[path] = FileState(hash(remote), remoteEntry.revision)

        val copy = conflictName(path, (local.keys + next.keys).toSet())
        writeLocal(mirrorRoot, copy, localBytes)
        val uploaded = client.put(copy, localBytes, null)
        next[copy] = FileState(hash(localBytes), uploaded.revision)
        counters.conflicts++
        counters.pushed++
    }

    /** `projects/acme.org` -> `projects/acme.sync-conflict-20261124-133000-ANDROID.org`. */
    private fun conflictName(path: String, existing: Set<String>): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        val hasExtension = dot > slash + 1
        val stem = if (hasExtension) path.substring(0, dot) else path
        val extension = if (hasExtension) path.substring(dot) else ""
        fun candidate(n: Int): String {
            val suffix = if (n == 0) "" else n.toString()
            return "$stem.sync-conflict-$stamp-$deviceTag$suffix$extension"
        }
        var n = 0
        var result = candidate(0)
        while (result in existing) {
            n++
            result = candidate(n)
        }
        return result
    }

    private fun writeLocal(mirrorRoot: File, path: String, bytes: ByteArray, lastModified: Long = 0L) {
        val file = File(mirrorRoot, path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        if (lastModified > 0) file.setLastModified(lastModified)
    }

    private fun localFiles(mirrorRoot: File): Map<String, File> {
        val result = HashMap<String, File>()
        if (!mirrorRoot.isDirectory) return result
        mirrorRoot.walkTopDown()
            .onEnter { it == mirrorRoot || !it.name.startsWith(".") }
            .filter { it.isFile }
            .forEach {
                val relative = it.relativeTo(mirrorRoot).invariantSeparatorsPath
                if (WebDavUrl.isSafeRelative(relative)) result[relative] = it
            }
        return result
    }

    private fun stateFile(config: WebDavConfig): File = WebDavPaths.stateFile(stateDir, config)

    private fun readState(file: File): Map<String, FileState> =
        runCatching { json.decodeFromString(StateFile.serializer(), file.readText()).files }
            .getOrDefault(emptyMap())

    private fun saveState(file: File, state: Map<String, FileState>) {
        val text = json.encodeToString(StateFile.serializer(), StateFile(state))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private class Counters {
        var pulled = 0
        var pushed = 0
        var deletedRemote = 0
        var deletedLocal = 0
        var conflicts = 0
    }
}

/** False for a PROPFIND that reported none of ETag / mtime / size. */
private fun WebDavEntry.hasUsableRevision(): Boolean =
    etag != null || lastModified > 0L || size > 0L

private fun hash(bytes: ByteArray): String = WebDavPaths.hash(bytes)
