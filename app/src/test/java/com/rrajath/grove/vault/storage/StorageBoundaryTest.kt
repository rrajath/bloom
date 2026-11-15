package com.rrajath.grove.vault.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Storage Access Framework is reached only through `vault/storage/`. A
 * future built-in network client lands as another `StorageBackend`, which only
 * works if nothing above that package grew its own SAF calls in the meantime.
 *
 * [allowed] is the files that touch these APIs today for reasons outside vault
 * I/O (parsing a picked tree URI for display). It may only shrink: add nothing
 * to it, route the new code through `vault/storage/` instead.
 */
class StorageBoundaryTest {

    private val appDir: File =
        File("app").takeIf { File(it, "src/main").isDirectory } ?: File(".")

    private val storageApis = Regex("""\b(ContentResolver|DocumentsContract|DocumentFile)\b""")

    /** Paths relative to `src/main/java/`. */
    private val allowed = setOf(
        // Reads the tree root's document id from the picked URI, to show its folder name.
        "com/rrajath/grove/ui/GroveApp.kt",
        "com/rrajath/grove/ui/vault/VaultViewModels.kt",
    )

    @Test
    fun `storage framework APIs are only used inside vault storage`() {
        val srcRoot = File(appDir, "src/main/java")
        val storageDir = File(srcRoot, "com/rrajath/grove/vault/storage")
        val hits = srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.startsWith(storageDir) }
            .filter { it.relativeTo(srcRoot).invariantSeparatorsPath !in allowed }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trim()
                    val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    "${file.relativeTo(srcRoot)}:${i + 1}: $code".takeIf { !isComment && storageApis.containsMatchIn(code) }
                }
            }
            .toList()
        assertTrue(
            "Storage framework APIs used outside vault/storage/; go through StorageBackend instead:\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun `the allowlist has no stale entries`() {
        val srcRoot = File(appDir, "src/main/java")
        val stale = allowed.filter { path ->
            val file = File(srcRoot, path)
            !file.isFile || file.readLines().none { storageApis.containsMatchIn(it) }
        }
        assertTrue("Remove from the allowlist (no longer needed): $stale", stale.isEmpty())
    }
}
