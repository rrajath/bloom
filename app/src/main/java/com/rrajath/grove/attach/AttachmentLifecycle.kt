package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.vault.FileStore
import com.rrajath.grove.vault.isAttachShard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which `.org` files hold an `:ID:` (heading or file level), from the note index. */
fun interface IdHolders {
    /** For each of [ids] held anywhere in the vault, the vault paths of the files holding it. */
    suspend fun filesHolding(ids: Collection<String>): Map<String, Set<String>>

    companion object {
        val None = IdHolders { emptyMap() }
    }
}

/** One file's text before and after an edit that may have moved subtrees between files. */
data class FileRewrite(val file: String, val before: String, val after: String)

/**
 * Told about edits that rewrote several files at once (refile, auto-archive,
 * and their UNDO) so attach dirs follow the subtrees that moved. Fire and
 * forget: the work runs in the application scope. [verb] starts the failure
 * toast (`Refiled; 2 attachment folders couldn't be moved`).
 */
fun interface AttachmentFollow {
    fun filesRewritten(writes: List<FileRewrite>, verb: String)

    companion object {
        val None = AttachmentFollow { _, _ -> }
    }
}

/** One existing attach dir found by an inventory. */
data class AttachDirEntry(
    val id: String,
    /** Vault-relative attach dir. */
    val dir: String,
    val files: Int,
    val bytes: Long,
    /**
     * Another note outside the operation resolves to the same dir: a move copies
     * it instead, a delete keeps it.
     */
    val shared: Boolean,
)

/** The attach dirs an operation on a file or subtree would touch (design 10.1). */
data class AttachmentInventory(
    val dirs: List<AttachDirEntry>,
    /** `:DIR:` values of owners in scope; never moved or deleted (v1). */
    val dirProperties: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = dirs.isEmpty()
    val folders: Int get() = dirs.size
    val files: Int get() = dirs.sumOf { it.files }
    val bytes: Long get() = dirs.sumOf { it.bytes }
    val sharedFolders: Int get() = dirs.count { it.shared }

    companion object {
        val Empty = AttachmentInventory(emptyList())
    }
}

/** Outcome of a lifecycle operation, counted per attach dir. */
data class LifecycleResult(val moved: Int = 0, val copied: Int = 0, val deleted: Int = 0, val kept: Int = 0, val failed: Int = 0) {
    companion object {
        val None = LifecycleResult()
    }
}

/**
 * Keeps attach dirs with their notes when notebooks, folders and subtrees move
 * or go away (design section 10). Only `:ID:` dirs are handled; `:DIR:` owners
 * are reported but never touched.
 *
 * Attach dirs live next to the org file (`<orgDir>/data/...`), so two notes only
 * share a dir when they sit in the same directory and carry the same `:ID:`.
 * That is what "shared" means here: a move copies such a dir and a delete keeps it.
 */
class AttachmentLifecycle(
    private val store: FileStore,
    private val holders: IdHolders,
    private val config: AttachConfig = AttachConfig.Default,
) {
    private val files = AttachmentFiles(store)
    private val resolver = AttachDirResolver(files, config)

    /** Serializes moves, so an UNDO can't race the refile it reverses. */
    private val mutex = Mutex()

    /** Every attach dir owned by [doc] (file-level owner and every heading), for a notebook move or delete. */
    suspend fun inventoryForFile(orgFile: String, doc: OrgDocument): AttachmentInventory {
        val owners = buildList {
            add(doc.fileOwner())
            doc.headlines.forEach { add(doc.ownerOf(it)) }
        }
        return inventory(orgFile, owners, heldElsewhereInFile = emptySet())
    }

    /**
     * The attach dirs owned by the subtrees rooted at [lines] in [doc], for a
     * heading delete. An ID also held by a heading outside those subtrees (or by
     * the file itself) counts as shared.
     */
    suspend fun inventoryForSubtrees(orgFile: String, doc: OrgDocument, lines: Set<Int>): AttachmentInventory {
        val ranges = doc.headlines.filter { it.lineIndex in lines }.map { it.lineIndex until doc.subtreeEndLine(it) }
        fun inScope(line: Int) = ranges.any { line in it }
        val inside = doc.headlines.filter { inScope(it.lineIndex) }
        if (inside.isEmpty()) return AttachmentInventory.Empty
        val outsideIds = buildSet {
            doc.fileOwner().id?.let(::add)
            doc.headlines.filterNot { inScope(it.lineIndex) }.forEach { h -> doc.ownerOf(h).id?.let(::add) }
        }
        return inventory(orgFile, inside.map { doc.ownerOf(it) }, outsideIds)
    }

    private suspend fun inventory(
        orgFile: String,
        owners: List<AttachOwner>,
        heldElsewhereInFile: Set<String>,
    ): AttachmentInventory {
        val dirProps = if (config.honorDirProperty) owners.mapNotNull { it.dirProperty?.trim() }.distinct() else emptyList()
        val ids = owners
            .filter { !config.honorDirProperty || it.dirProperty == null }
            .mapNotNull { it.id?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        if (ids.isEmpty()) return AttachmentInventory(emptyList(), dirProps)
        val sharedInDir = idsHeldInDir(orgFile, ids, exclude = setOf(orgFile))
        val seen = mutableSetOf<String>()
        val entries = ids.mapNotNull { id ->
            val dir = resolver.readDir(orgFile, AttachOwner.File(id, null)) ?: return@mapNotNull null
            if (!seen.add(dir)) return@mapNotNull null
            val size = files.sizeOf(dir)
            AttachDirEntry(id, dir, size.files, size.bytes, shared = id in sharedInDir || id in heldElsewhereInFile)
        }
        return AttachmentInventory(entries, dirProps)
    }

    /**
     * After the notebook at [oldPath] moved to [newPath]: move each dir of
     * [inventory] to the same place relative to the new directory, merging with
     * what's there (collisions renamed). Shared dirs are copied. Emptied shards
     * and `data/` are pruned. A dir that fails stays where it was.
     */
    suspend fun moveNotebookAttachments(oldPath: String, newPath: String, inventory: AttachmentInventory): LifecycleResult =
        mutex.withLock {
            val oldDir = dirOf(oldPath)
            val newDir = dirOf(newPath)
            if (oldDir == newDir) return@withLock LifecycleResult.None
            var result = LifecycleResult.None
            for (e in inventory.dirs) {
                val rel = relativeTo(e.dir, oldDir)
                if (rel == null) {
                    result = result.copy(failed = result.failed + 1)
                    continue
                }
                result = relocate(e.dir, joinPath(newDir, rel), copy = e.shared, pruneStop = oldDir, result)
            }
            result
        }

    /**
     * Deletes the dirs of [inventory] that aren't shared. Right before each one,
     * [stillHeld] is asked again (a copy of the heading may have appeared since
     * the inventory ran); a held dir is kept. Emptied shards and `data/` are pruned.
     */
    suspend fun deleteDirs(
        orgFile: String,
        inventory: AttachmentInventory,
        stillHeld: suspend (id: String) -> Boolean,
    ): LifecycleResult = mutex.withLock {
        val orgDir = dirOf(orgFile)
        var result = LifecycleResult.None
        for (e in inventory.dirs) {
            if (e.shared || stillHeld(e.id)) {
                result = result.copy(kept = result.kept + 1)
                continue
            }
            if (files.deleteDir(e.dir)) {
                files.pruneEmpty(parentOf(e.dir), stopAt = orgDir)
                result = result.copy(deleted = result.deleted + 1)
            } else {
                result = result.copy(failed = result.failed + 1)
            }
        }
        result
    }

    /** True when another file in [orgFile]'s directory holds [id] ([exclude] aside). */
    suspend fun heldInDir(orgFile: String, id: String, exclude: Set<String> = setOf(orgFile)): Boolean =
        id in idsHeldInDir(orgFile, listOf(id), exclude)

    /**
     * Follows `:ID:` owners that an edit moved from one file to another in a
     * different directory (refile, auto-archive, and their UNDO): each ID that
     * a file gained and another file of [writes] held before has its dir moved
     * to the same relative place next to the gaining file, merging when it
     * exists. The dir is copied when the ID is still in the source file or in
     * another file of the source directory. No-op for same-directory moves.
     */
    suspend fun followMovedOwners(writes: List<FileRewrite>): LifecycleResult = mutex.withLock {
        val merged = writes.groupBy { it.file }.map { (file, ws) -> FileRewrite(file, ws.first().before, ws.last().after) }
        if (merged.size < 2) return@withLock LifecycleResult.None
        val before = merged.associate { it.file to orgIdsIn(it.before) }
        val after = merged.associate { it.file to orgIdsIn(it.after) }
        val involved = merged.map { it.file }.toSet()
        var result = LifecycleResult.None
        for (gainer in merged) {
            val gained = after.getValue(gainer.file) - before.getValue(gainer.file)
            for (id in gained) {
                val others = merged.filter { it.file != gainer.file && id in before.getValue(it.file) }
                val source = others.firstOrNull { id !in after.getValue(it.file) } ?: others.firstOrNull() ?: continue
                val srcDir = dirOf(source.file)
                val dstDir = dirOf(gainer.file)
                if (srcDir == dstDir) continue
                val from = resolver.readDir(source.file, AttachOwner.File(id, null)) ?: continue
                val rel = relativeTo(from, srcDir) ?: continue
                val copy = id in after.getValue(source.file) || heldInDir(source.file, id, exclude = involved)
                result = relocate(from, joinPath(dstDir, rel), copy, pruneStop = srcDir, result)
            }
        }
        result
    }

    /** True when any attach dir exists at or under [dir]. */
    suspend fun folderHasAttachments(dir: String): Boolean = attachRoots(dir.trim('/'), limit = 1).isNotEmpty()

    /**
     * After every `.org` under [oldDir] moved to [newDir] (a folder rename or
     * move): moves each attach shard of every `data/` at or under [oldDir] to
     * the same place under [newDir], then prunes. The whole folder moves, so the
     * relative layout stays intact.
     */
    suspend fun moveFolderAttachments(oldDir: String, newDir: String): LifecycleResult = mutex.withLock {
        val from = oldDir.trim('/')
        val to = newDir.trim('/')
        var result = LifecycleResult.None
        for (root in attachRoots(from)) {
            val rel = relativeTo(root, from) ?: continue
            for (shard in store.listSubdirs(root).filter(::isAttachShard)) {
                val src = joinPath(root, shard)
                val ok = files.mergeDir(src, joinPath(to, rel, shard))
                result = if (ok) result.copy(moved = result.moved + 1) else result.copy(failed = result.failed + 1)
            }
            files.pruneEmpty(root, stopAt = parentOf(from))
        }
        // Folders left empty by the .org moves (the vault's own prune ran while data/ still held shards).
        store.pruneEmptyDirs(from)
        result
    }

    /** After a folder delete: deletes the attach shards of every `data/` at or under [dir], then prunes. */
    suspend fun deleteFolderAttachments(dir: String): LifecycleResult = mutex.withLock {
        val trimmed = dir.trim('/')
        var result = LifecycleResult.None
        for (root in attachRoots(trimmed)) {
            for (shard in store.listSubdirs(root).filter(::isAttachShard)) {
                result = if (files.deleteDir(joinPath(root, shard))) {
                    result.copy(deleted = result.deleted + 1)
                } else {
                    result.copy(failed = result.failed + 1)
                }
            }
            files.pruneEmpty(root, stopAt = parentOf(trimmed))
        }
        if (trimmed.isNotEmpty()) store.pruneEmptyDirs(trimmed)
        result
    }

    private suspend fun relocate(
        from: String,
        to: String,
        copy: Boolean,
        pruneStop: String,
        acc: LifecycleResult,
    ): LifecycleResult = if (copy) {
        if (files.copyDir(from, to)) acc.copy(copied = acc.copied + 1) else acc.copy(failed = acc.failed + 1)
    } else if (files.mergeDir(from, to)) {
        files.pruneEmpty(parentOf(from), stopAt = pruneStop)
        acc.copy(moved = acc.moved + 1)
    } else {
        acc.copy(failed = acc.failed + 1)
    }

    /** IDs among [ids] that a file in [orgFile]'s directory holds, [exclude] aside. */
    private suspend fun idsHeldInDir(orgFile: String, ids: Collection<String>, exclude: Set<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val dir = dirOf(orgFile)
        return holders.filesHolding(ids)
            .filterValues { held -> held.any { it !in exclude && dirOf(it) == dir } }
            .keys
    }

    /**
     * Every attach root (`data/` holding at least one shard) at or under [dir],
     * walking user folders but never into shards. A `data/` folder's non-shard
     * children (user folders such as `data/notes/`) are walked too. The walk
     * stops once [limit] roots are found. A missing [dir] lists as empty, so
     * there's no separate existence check (each one is a provider query on SAF).
     */
    private suspend fun attachRoots(dir: String, limit: Int = Int.MAX_VALUE): List<String> {
        val leaf = config.idDir.trim('/').substringAfterLast('/')
        val out = mutableListOf<String>()
        suspend fun walk(path: String) {
            for (sub in store.listSubdirs(path)) {
                if (out.size >= limit) return
                if (sub.startsWith(".")) continue
                val child = joinPath(path, sub)
                if (sub == leaf) {
                    val kids = store.listSubdirs(child)
                    if (kids.any(::isAttachShard)) out += child
                    for (kid in kids) {
                        if (out.size >= limit) return
                        if (!isAttachShard(kid) && !kid.startsWith(".")) walk(joinPath(child, kid))
                    }
                } else {
                    walk(child)
                }
            }
        }
        if (dir.isNotEmpty()) walk(dir)
        return out
    }
}

/**
 * What restoring [restores] (file, text) would change, read before the restore
 * is written: the input an UNDO hands to [AttachmentFollow]. Only multi-file
 * restores can move a subtree, so a single file gives nothing.
 */
suspend fun com.rrajath.grove.vault.Vault.rewritesFor(restores: List<Pair<String, String>>): List<FileRewrite> {
    if (restores.size < 2) return emptyList()
    return restores.mapNotNull { (name, text) -> open(name)?.text?.let { FileRewrite(name, it, text) } }
}

private val ORG_ID_LINE = Regex("""(?im)^[ \t]*:ID:[ \t]+(\S+)[ \t]*$""")

/** Every `:ID:` property value in raw org [text] (headings and the file drawer). */
fun orgIdsIn(text: String): Set<String> = ORG_ID_LINE.findAll(text).mapTo(mutableSetOf()) { it.groupValues[1] }

/** The vault-relative directory of [path] ("" for the root). */
internal fun dirOf(path: String): String = path.trim('/').substringBeforeLast('/', "")

private fun parentOf(path: String): String = path.trim('/').substringBeforeLast('/', "")

/** [path] relative to [dir], or null when it isn't under it. */
private fun relativeTo(path: String, dir: String): String? {
    if (dir.isEmpty()) return path
    return if (path.startsWith("$dir/")) path.removePrefix("$dir/") else null
}
