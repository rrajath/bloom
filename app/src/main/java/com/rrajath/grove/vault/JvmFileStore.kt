package com.rrajath.grove.vault

import com.rrajath.grove.attach.AttachSkipRoots
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Plain java.io implementation. Used by JVM unit tests, and reusable for any
 * future direct-filesystem vault location. Recurses into subdirectories,
 * skipping dot-directories and attachment shards (see [isSkippedVaultDir]).
 */
class JvmFileStore(
    private val root: File,
    private val ignore: IgnorePatterns = IgnorePatterns(""),
    skipAttachShards: Boolean = SKIP_ATTACH_SHARDS,
    /** Where shards are skipped (from the attach config); a config change builds a new store. */
    attachRoots: AttachSkipRoots = AttachSkipRoots.DataOnly,
) : FileStore {
    private val skipRoots = attachRoots.takeIf { skipAttachShards }

    override suspend fun list(): List<FileEntry> =
        root.walkTopDown()
            .onEnter {
                it == root || (
                    !isSkippedVaultDir(it.name, relativeParent(it), skipRoots) &&
                        !ignore.isDirIgnored(it.name, it.relativeTo(root).invariantSeparatorsPath)
                    )
            }
            .filter {
                it.isFile && !ignore.isFileIgnored(it.name, it.relativeTo(root).invariantSeparatorsPath)
            }
            .map {
                FileEntry(
                    it.relativeTo(root).invariantSeparatorsPath,
                    it.lastModified(),
                    it.length(),
                )
            }
            .sortedBy { it.name }
            .toList()

    override suspend fun read(name: String): String = resolve(name).readText()

    override fun absolutePath(name: String): String = resolve(name).absolutePath

    override suspend fun write(name: String, content: String) {
        resolve(name).apply { parentFile?.mkdirs() }.writeText(content)
    }

    override suspend fun create(name: String): Boolean {
        val target = resolve(name)
        target.parentFile?.mkdirs()
        return target.createNewFile()
    }

    override suspend fun rename(oldName: String, newName: String): Boolean {
        val target = resolve(newName)
        if (target.exists()) return false
        target.parentFile?.mkdirs()
        return resolve(oldName).renameTo(target)
    }

    override suspend fun delete(name: String): Boolean = resolve(name).delete()

    override suspend fun exists(name: String): Boolean = resolve(name).exists()

    override suspend fun pruneEmptyDirs(dir: String) {
        val trimmed = dir.trim('/')
        if (trimmed.isEmpty()) return
        // Deepest-first so an emptied parent whose only child was an (also now
        // empty) subdirectory still collapses.
        File(root, trimmed).takeIf { it.isDirectory }?.walkBottomUp()?.forEach { d ->
            if (d.isDirectory && d.list()?.isEmpty() == true) d.delete()
        }
        var current = trimmed.substringBeforeLast('/', "")
        while (current.isNotEmpty()) {
            val d = File(root, current)
            if (d.list()?.isEmpty() != true || !d.delete()) break
            current = current.substringBeforeLast('/', "")
        }
    }

    override suspend fun openInput(name: String): InputStream {
        val f = resolve(name)
        if (!f.isFile) throw FileNotFoundException(name)
        return FileInputStream(f)
    }

    override suspend fun createBinary(name: String): Boolean {
        val target = resolve(name)
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) return false
        return target.createNewFile()
    }

    override suspend fun openOutput(name: String): OutputStream {
        val f = resolve(name)
        if (!f.isFile) throw FileNotFoundException(name)
        return FileOutputStream(f, false)
    }

    override suspend fun statRaw(name: String): FileEntry? =
        resolve(name).takeIf { it.isFile }?.let { FileEntry(name.trim('/'), it.lastModified(), it.length()) }

    override suspend fun renameRaw(name: String, newLeaf: String): Boolean {
        require('/' !in newLeaf && newLeaf.isNotBlank()) { "Bad leaf name: $newLeaf" }
        val src = resolve(name)
        if (!src.isFile) return false
        val dst = File(src.parentFile, newLeaf)
        if (dst.exists()) return false
        return src.renameTo(dst)
    }

    override suspend fun deleteRaw(name: String): Boolean = resolve(name).let { it.isFile && it.delete() }

    override suspend fun dirExists(dir: String): Boolean = dirFile(dir).isDirectory

    override suspend fun listDirRaw(dir: String): List<FileEntry> {
        val d = dirFile(dir)
        val prefix = dir.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return d.listFiles().orEmpty()
            .filter { it.isFile }
            .map { FileEntry(prefix + it.name, it.lastModified(), it.length()) }
            .sortedBy { it.name }
    }

    override suspend fun listSubdirs(dir: String): List<String> =
        dirFile(dir).listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()

    override suspend fun deleteDir(dir: String): Boolean {
        require(dir.trim('/').isNotEmpty()) { "Refusing to delete the vault root" }
        val d = dirFile(dir)
        return d.isDirectory && d.deleteRecursively()
    }

    override suspend fun moveDir(src: String, dstParent: String): Boolean {
        require(src.trim('/').isNotEmpty()) { "Refusing to move the vault root" }
        val from = dirFile(src)
        if (!from.isDirectory) return false
        val parent = dirFile(dstParent)
        val to = File(parent, from.name)
        if (to.exists()) return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        if (from.renameTo(to)) return true
        return from.copyRecursively(to) && from.deleteRecursively()
    }

    private fun dirFile(dir: String): File {
        val trimmed = dir.trim('/')
        return if (trimmed.isEmpty()) root else resolve(trimmed)
    }

    private fun relativeParent(dir: File): String =
        dir.parentFile?.relativeTo(root)?.invariantSeparatorsPath.orEmpty()

    private fun resolve(name: String): File {
        require(name.isNotBlank()) { "Empty vault path" }
        require(name.split('/').none { it == ".." }) { "Vault path must not contain '..': $name" }
        return File(root, name)
    }
}
