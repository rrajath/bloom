package com.rrajath.grove.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every popup menu goes through [GroveDropdownMenu] so they all share the
 * `surface2` + `line2` border look. A raw Material `DropdownMenu(` call would
 * fall back to `surface` and blend into the screen behind it.
 */
class NoRawDropdownMenuTest {

    private val appDir: File =
        File("app").takeIf { File(it, "src/main").isDirectory } ?: File(".")

    /** `DropdownMenu(` not preceded by an identifier char, so `GroveDropdownMenu(` is fine. */
    private val rawCall = Regex("""(?<![A-Za-z0-9_])DropdownMenu\(""")

    @Test
    fun `no raw Material DropdownMenu outside the wrapper`() {
        val src = File(appDir, "src/main/java")
        assertTrue("source dir not found: $src", src.isDirectory)
        val hits = src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "GroveDropdownMenu.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    "${file.relativeTo(appDir)}:${i + 1}: ${line.trim()}".takeIf { rawCall.containsMatchIn(line) }
                }
            }.toList()
        assertTrue("Use GroveDropdownMenu instead of DropdownMenu:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
