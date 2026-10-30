package com.rrajath.grove

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

    private val appDir: File =
        File("app").takeIf { File(it, "src/main").isDirectory } ?: File(".")

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
                    .filter { (_, text) -> text !in allowedLiterals && anyName.containsMatchIn(text) }
                    .map { (line, text) -> "${file.relativeTo(appDir)}:$line: \"$text\"" }
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
                    .filter { (_, text) -> oldUrl.containsMatchIn(text) }
                    .map { (line, text) -> "${file.relativeTo(appDir)}:$line: \"$text\"" }
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

    /**
     * Returns (1-based line, contents) for each string literal in [src], skipping
     * comments and char literals. Handles `"…"`, raw `"""…"""` and escapes; `${…}`
     * template code is kept as part of the literal, which is fine for a name check.
     */
    private fun kotlinStringLiterals(src: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        var line = 1
        fun advance(n: Int = 1) {
            repeat(n) {
                if (i < src.length && src[i] == '\n') line++
                i++
            }
        }
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') advance()
                src.startsWith("/*", i) -> {
                    advance(2)
                    while (i < src.length && !src.startsWith("*/", i)) advance()
                    advance(2)
                }
                src.startsWith("\"\"\"", i) -> {
                    advance(3)
                    val startLine = line
                    val start = i
                    while (i < src.length && !src.startsWith("\"\"\"", i)) advance()
                    out += startLine to src.substring(start, minOf(i, src.length))
                    advance(3)
                }
                src[i] == '"' -> {
                    advance()
                    val startLine = line
                    val sb = StringBuilder()
                    while (i < src.length && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            sb.append(src[i + 1])
                            advance(2)
                        } else {
                            sb.append(src[i])
                            advance()
                        }
                    }
                    out += startLine to sb.toString()
                    advance()
                }
                src[i] == '\'' -> {
                    advance()
                    while (i < src.length && src[i] != '\'' && src[i] != '\n') {
                        if (src[i] == '\\') advance(2) else advance()
                    }
                    advance()
                }
                else -> advance()
            }
        }
        return out
    }
}
