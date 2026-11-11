package com.rrajath.grove.attach

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App code takes its [AttachConfig] from [AttachConfigSource], never from a
 * literal: a resolver built with Emacs's defaults would put files in `data/`
 * while the user's `org-attach-id-dir` says otherwise. Resolvers, lookups,
 * lifecycles and owner resolution have no config default for the compiler to
 * fill in; this test catches the remaining way in, naming a config directly.
 */
class NoDefaultAttachConfigTest {

    private val appDir: File =
        File("app").takeIf { File(it, "src/main").isDirectory } ?: File(".")

    private val literal = Regex("""\bAttachConfig\.Default\b|\bAttachConfig\s*\(|\bFixedAttachConfigSource\s*\(""")

    /** Files allowed to name a config, keyed by path under `src/main/java/com/rrajath/grove/`. */
    private val allowed = mapOf(
        "attach/AttachConfig.kt" to "defines it",
        "attach/AttachConfigSource.kt" to "the stand-in before settings load, and the fixed source's default",
        "ui/editor/AttachController.kt" to "fallback with no app (previews), where no store resolves anything",
    )

    @Test
    fun `app code never names an attach config directly`() {
        val root = File(appDir, "src/main/java/com/rrajath/grove")
        assertTrue("source root not found: $root", root.isDirectory)
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativeTo(root).invariantSeparatorsPath !in allowed }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    "${file.relativeTo(appDir)}:${i + 1}: ${line.trim()}".takeIf { literal.containsMatchIn(line) }
                }
            }
            .toList()
        assertTrue(
            "Attach config named directly; take it from GroveApplication.attachConfigSource.current():\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun `allowlisted files exist`() {
        val root = File(appDir, "src/main/java/com/rrajath/grove")
        val missing = allowed.keys.filterNot { File(root, it).isFile }
        assertTrue("Stale allowlist entries: $missing", missing.isEmpty())
    }
}
