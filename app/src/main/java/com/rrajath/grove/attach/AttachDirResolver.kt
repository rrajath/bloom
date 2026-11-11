package com.rrajath.grove.attach

import com.rrajath.grove.vault.FileStore

/** Answers whether a vault-relative directory exists. Implemented over the vault's FileStore. */
fun interface DirExists {
    suspend fun exists(vaultDir: String): Boolean
}

/**
 * Maps an [AttachOwner] to its vault-relative attachment directory, following
 * `org-attach-dir` / `org-attach-dir-from-id` (org-attach.el):
 *
 * 1. `:DIR:` (then `:ATTACH_DIR:`) wins, resolved against the org file's directory.
 * 2. Else, with an `:ID:`, each ID path format in order: `<idDir>/<format(id)>`,
 *    then `data/<format(id)>` when [AttachConfig.idDir] isn't `data/`. The first
 *    existing directory wins; when none exists, the first non-null candidate
 *    under `idDir` is the one to create.
 * 3. Neither: no directory.
 *
 * Paths that escape the vault (`~`, absolute `:DIR:`, `..` above the root)
 * resolve to null, which callers treat as missing.
 */
class AttachDirResolver(
    private val dirs: DirExists,
    private val config: AttachConfig,
) {

    /** One candidate: the dir under `idDir` and, when it differs, the `data/` fallback. */
    private data class Candidate(val primary: String, val fallback: String?)

    private fun candidateList(orgFile: String, owner: AttachOwner): List<Candidate> {
        val orgDir = orgFile.substringBeforeLast('/', "")
        if (config.honorDirProperty) {
            owner.dirProperty?.let { dir ->
                return listOfNotNull(resolveRelative(orgDir, dir.trim())?.let { Candidate(it, null) })
            }
        }
        val id = owner.id?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val base = if (config.idDir.startsWith("/")) config.idDir else joinPath(orgDir, config.idDir)
        val usesDefaultBase = !config.idDir.startsWith("/") && config.idDir.trim('/') == DEFAULT_ID_DIR
        return config.idPathFormats.mapNotNull { fmt ->
            val name = fmt.path(id) ?: return@mapNotNull null
            val primary = normalizeVaultPath(joinPath(base, name))?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val fallback = if (usesDefaultBase) null else normalizeVaultPath(joinPath(orgDir, DEFAULT_ID_DIR, name))
            Candidate(primary, fallback?.takeIf { it != primary })
        }
    }

    /** Candidate vault-relative dirs in org-attach search order (fallbacks included). */
    fun candidates(orgFile: String, owner: AttachOwner): List<String> =
        candidateList(orgFile, owner).flatMap { listOfNotNull(it.primary, it.fallback) }

    /** The first candidate that exists, or null. At most one stat per candidate. */
    suspend fun readDir(orgFile: String, owner: AttachOwner): String? {
        for (c in candidateList(orgFile, owner)) {
            if (dirs.exists(c.primary)) return c.primary
            if (c.fallback != null && dirs.exists(c.fallback)) return c.fallback
        }
        return null
    }

    /** [readDir], else the first non-null candidate (created only when a file is written). */
    suspend fun writeDir(orgFile: String, owner: AttachOwner): String? =
        readDir(orgFile, owner) ?: candidateList(orgFile, owner).firstOrNull()?.primary

    private fun resolveRelative(orgDir: String, dir: String): String? {
        if (dir.isEmpty() || dir.startsWith("~") || dir.startsWith("/")) return null
        return normalizeVaultPath(joinPath(orgDir, dir))?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val DEFAULT_ID_DIR = "data"
    }
}

/**
 * [DirExists] answered from directory listings, each listed once: a dir exists
 * when its parent exists and lists it. Resolving many IDs then costs one
 * listing of `data/` plus one per shard that exists, instead of one or more
 * provider queries per candidate. For one batch of lookups (an inventory);
 * the listings aren't refreshed.
 */
class ListedDirs(private val store: FileStore) : DirExists {
    private val listings = mutableMapOf<String, Set<String>>()

    override suspend fun exists(vaultDir: String): Boolean {
        val path = vaultDir.trim('/')
        if (path.isEmpty()) return true
        val parent = path.substringBeforeLast('/', "")
        if (!exists(parent)) return false
        return path.substringAfterLast('/') in children(parent)
    }

    private suspend fun children(dir: String): Set<String> =
        listings[dir] ?: store.listSubdirs(dir).toSet().also { listings[dir] = it }
}

/** Joins path parts with `/`, ignoring empty parts. Not normalized. */
internal fun joinPath(vararg parts: String): String =
    parts.filter { it.isNotEmpty() }.joinToString("/")

/**
 * Normalizes a vault-relative path: drops empty and `.` segments, collapses
 * `..`. A leading `/` means the vault root. Null when the path climbs above the
 * root. The empty string is the vault root itself.
 */
fun normalizeVaultPath(path: String): String? {
    val out = ArrayDeque<String>()
    for (seg in path.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> if (out.isEmpty()) return null else out.removeLast()
            else -> out.addLast(seg)
        }
    }
    return out.joinToString("/")
}
