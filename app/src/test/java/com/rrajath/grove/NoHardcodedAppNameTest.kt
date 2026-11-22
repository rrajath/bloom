package com.rrajath.grove

import com.rrajath.grove.testing.support.appModuleDir
import com.rrajath.grove.testing.support.kotlinStringLiterals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app was renamed from Grove to Bloom. User-visible text must take the name
 * from `R.string.app_name`, never a literal, so a future rename is a one-line
 * resource change.
 *
 * Code identifiers (`GroveApp`, `com.rrajath.grove`, `grove://`, `grove-index.db`)
 * intentionally keep the old name; the checks are case-sensitive and whole-word,
 * so only a capitalized "Grove" or "Bloom" inside a string literal, or "Grove" in a
 * string resource, fails. The "Grove Light"/"Grove Dark" and "Bloom Light"/"Bloom Dark"
 * theme names are theme names, not the app name, and are allowed by [allowedLiterals].
 */
class NoHardcodedAppNameTest {

    private val appDir: File = appModuleDir

    private val oldName = Regex("""\bGrove\b""")

    /** Either name, old or current: neither belongs in a Kotlin literal. */
    private val anyName = Regex("""\b(Grove|Bloom)\b""")

    /** Exact literals that contain an app name on purpose. */
    private val allowedLiterals = setOf("Grove Light", "Grove Dark", "Bloom Light", "Bloom Dark")

    @Test
    fun `no Kotlin string literal names the app`() {
        val hits = sourceSets().flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                kotlinStringLiterals(file.readText())
                    .filter { (_, _, text) -> text !in allowedLiterals && anyName.containsMatchIn(text) }
                    .map { (line, _, text) -> "${file.relativeTo(appDir)}:$line: \"$text\"" }
            }.toList()
        }
        assertTrue(
            "Hardcoded app name in string literals; use R.string.app_name instead:\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun `string resources never name the app outside app_name`() {
        val stringFiles = File(appDir, "src").listFiles().orEmpty()
            .flatMap { set -> File(set, "res").listFiles().orEmpty().toList() }
            .filter { it.isDirectory && it.name.startsWith("values") }
            .mapNotNull { File(it, "strings.xml").takeIf(File::isFile) }
        assertTrue("no strings.xml found under $appDir", stringFiles.isNotEmpty())

        val hits = stringFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                "${file.relativeTo(appDir)}:${i + 1}: ${line.trim()}".takeIf { oldName.containsMatchIn(line) }
            }
        }
        assertTrue("Old app name in string resources:\n" + hits.joinToString("\n"), hits.isEmpty())

        // The name is spelled out once; every other string takes it as a format arg.
        val main = File(appDir, "src/main/res/values/strings.xml").readLines()
        val nameLines = main.filter { "Bloom" in it && "<string" in it }
        assertTrue(
            "\"Bloom\" should appear only in app_name, found:\n" + nameLines.joinToString("\n"),
            nameLines.size == 1 && "name=\"app_name\"" in nameLines.single(),
        )
    }

    @Test
    fun `no string literal points at the old repo or docs site`() {
        val oldUrl = Regex("""github\.com/rrajath/grove\b|grove\.rrajath\.com""")
        val hits = sourceSets().flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                kotlinStringLiterals(file.readText())
                    .filter { (_, _, text) -> oldUrl.containsMatchIn(text) }
                    .map { (line, _, text) -> "${file.relativeTo(appDir)}:$line: \"$text\"" }
            }.toList()
        }
        assertTrue("Old repo/docs URLs; use rrajath/bloom or bloom.rrajath.com:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun `manifest labels never spell out the app name`() {
        val manifest = File(appDir, "src/main/AndroidManifest.xml").readText()
        val literal = Regex("""android:label="[^"@]*\b(Grove|Bloom)\b[^"]*"""").findAll(manifest).map { it.value }.toList()
        assertTrue("Literal android:label values in the manifest:\n" + literal.joinToString("\n"), literal.isEmpty())
    }

    private fun sourceSets(): List<File> =
        listOf("main", "debug", "release")
            .map { File(appDir, "src/$it/java") }
            .filter { it.isDirectory }
            .also { assertTrue("no source sets found under $appDir", it.isNotEmpty()) }
}
