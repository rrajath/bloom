package com.rrajath.grove

import com.rrajath.grove.testing.support.KotlinLiteral
import com.rrajath.grove.testing.support.appModuleDir
import com.rrajath.grove.testing.support.kotlinStringLiterals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Ratchet against new hardcoded UI text: user-visible strings belong in
 * `res/values/strings*.xml` so Weblate can translate them.
 *
 * Counts prose-like string literals per Kotlin file (see [isProseLike]) and compares
 * them with the checked-in [baselineFile]. The counts must match exactly: a file that
 * gains literals fails (move the new text into a string resource), and a file that
 * loses them fails too, so the baseline stays tight and can't be quietly refilled.
 * After extracting strings, regenerate it:
 *
 * ```
 * UPDATE_I18N_BASELINE=1 ./gradlew testDebugUnitTest --tests "*NoHardcodedUiStringsTest" --rerun
 * ```
 *
 * Text that must stay a literal (org syntax written to disk, log lines, developer-only
 * tooling) is exempt when marked with an `i18n:` comment, for example
 * `// i18n: org syntax, never translate`:
 *  - in the file's leading comments (before `package`): the whole file is exempt;
 *  - on the literal's line, or the line just above it: that literal is exempt;
 *  - `// i18n-begin: <why>` ... `// i18n-end` around a block: every literal inside is exempt.
 */
class NoHardcodedUiStringsTest {

    private val srcDir = File(appModuleDir, "src/main/java")
    private val baselineFile = File(appModuleDir, "src/test/resources/hardcoded-strings-baseline.txt")

    @Test
    fun `hardcoded UI strings match the baseline`() {
        val actual = countsByFile()
        if (System.getenv("UPDATE_I18N_BASELINE") == "1") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(render(actual))
            println("Wrote ${baselineFile.relativeTo(appModuleDir)}: ${actual.values.sum()} literals in ${actual.size} files")
            return
        }
        assertTrue("missing ${baselineFile.relativeTo(appModuleDir)}", baselineFile.isFile)
        val baseline = parse(baselineFile.readLines())

        val grew = actual.filter { (path, n) -> n > (baseline[path] ?: 0) }
        val shrank = (baseline.keys + actual.keys).filter { (actual[it] ?: 0) < (baseline[it] ?: 0) }
        if (grew.isNotEmpty()) {
            val detail = grew.keys.sorted().joinToString("\n") { path ->
                val lines = prose(File(srcDir, path)).joinToString("\n") { "    ${it.line}: \"${it.text}\"" }
                "$path: ${baseline[path] ?: 0} -> ${grew[path]}\n$lines"
            }
            fail(
                "New hardcoded UI text. Move it into res/values/strings*.xml and use stringResource()/" +
                    "getString() (or UiText), or mark a literal that must stay with an `// i18n:` comment.\n$detail",
            )
        }
        if (shrank.isNotEmpty()) {
            fail(
                "Hardcoded UI strings went down (nice). Lower the baseline so it stays tight:\n" +
                    "  UPDATE_I18N_BASELINE=1 ./gradlew testDebugUnitTest --tests \"*NoHardcodedUiStringsTest\" --rerun\n" +
                    shrank.sorted().joinToString("\n") { "  $it: ${baseline[it] ?: 0} -> ${actual[it] ?: 0}" },
            )
        }
    }

    @Test
    fun `prose detection`() {
        val prose = listOf("Cancel", "Save changes?", "No notes yet", "Moved to \$target", "under heading", "Today")
        val notProse = listOf(
            "", "TODO", "SCHEDULED:", "inbox.org", "com.rrajath.grove", "yyyy-MM-dd", "%1\$s", "\$count",
            "https://bloom.rrajath.com", "sync_mode", "SELECT * FROM notes WHERE a = 1", "  AND isDone = 0", "MMM d, yyyy", "h:mm a", "\n", " · ", "content://", "a", "CUSTOM_ID", "OK",
        )
        prose.forEach { assertTrue("should be prose: \"$it\"", isProseLike(it)) }
        notProse.forEach { assertTrue("should not be prose: \"$it\"", !isProseLike(it)) }
    }

    @Test
    fun `i18n markers exempt a file or a line`() {
        val marked = """
            // i18n: org syntax, never translate
            package x
            val a = "Hello there"
        """.trimIndent()
        assertEquals(0, proseLiterals(marked).size)

        val block = """
            package x
            // i18n-begin: report body stays English
            val a = "Hello there"
            val b = "Steps to reproduce"
            // i18n-end
            val c = "Still counted"
        """.trimIndent()
        assertEquals(listOf("Still counted"), proseLiterals(block).map { it.text })

        val lines = """
            package x
            val a = "Hello there" // i18n: log line
            // i18n: written to disk
            val b = "Note taken on"
            val c = "Still counted"
        """.trimIndent()
        assertEquals(listOf("Still counted"), proseLiterals(lines).map { it.text })
    }

    private fun countsByFile(): Map<String, Int> =
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f -> prose(f).size.takeIf { it > 0 }?.let { f.relativeTo(srcDir).invariantSeparatorsPath to it } }
            .toMap()

    private fun prose(file: File): List<KotlinLiteral> = proseLiterals(file.readText())

    private fun render(counts: Map<String, Int>): String = buildString {
        appendLine("# Prose-like string literals per file. Generated by NoHardcodedUiStringsTest; do not edit by hand.")
        appendLine("# total ${counts.values.sum()}")
        counts.toSortedMap().forEach { (path, n) -> appendLine("$path $n") }
    }

    private fun parse(lines: List<String>): Map<String, Int> =
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (path, n) = line.trim().split(' ')
            path to n.toInt()
        }

    companion object {
        private val marker = Regex("""//.*\bi18n:""")
        private val blockBegin = Regex("""^\s*//\s*i18n-begin\b""")
        private val blockEnd = Regex("""^\s*//\s*i18n-end\b""")

        /**
         * Code just before a literal (on its line) that means it isn't UI text:
         * logging, exceptions and preconditions, regexes, persisted keys, test tags.
         */
        private val nonUiContext = Regex(
            """\bLog\.[a-z]\(|\bRegex\(|toRegex\(|\b(require|requireNotNull|check|checkNotNull|error|assert)\(|""" +
                """\bthrow\b|Exception\(|testTag\(|PreferencesKey\(|@Query\(|putExtra\(|getStringExtra\(|""" +
                """\bprintln\(|\bTrace\.|traceSection\(|beginSection\(|\blog\(|requestSync\(|requestReindex\(|syncNow\(|clearAndResync\(""",
        )

        private val template = Regex("""\$\{[^}]*}|\$[A-Za-z_][A-Za-z0-9_]*""")
        private val word = Regex("""[A-Za-z]{2,}""")
        private val capitalizedWord = Regex("""^[^A-Za-z]*[A-Z][a-z]""")
        private val twoWords = Regex("""[A-Za-z]{2,}[,.!?:]? +[A-Za-z(]""")
        private val url = Regex("""^[a-z][a-z0-9+.-]*://""")
        /** java.time / ICU date-time patterns and skeletons: "MMM d, yyyy", "h:mm a", "EEE". */
        private val datePattern = Regex("""^[yMdEHhmsaLkKzG ,.:'/-]+$""")
        private val sql = Regex(
            """^\s*(SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|WITH|UNION|WHERE|AND|OR|ORDER BY|GROUP BY|""" +
                """FROM|LEFT JOIN|JOIN|LIMIT|SET|VALUES)\b""",
        )

        /** A literal that reads like text for people: a capitalized word, or at least two words. */
        fun isProseLike(raw: String): Boolean {
            val text = template.replace(raw, " ").trim()
            if (!word.containsMatchIn(text)) return false
            if (url.containsMatchIn(text) || sql.containsMatchIn(text) || datePattern.matches(text)) return false
            return capitalizedWord.containsMatchIn(text) || twoWords.containsMatchIn(text)
        }

        /** Prose-like literals in [src] that aren't exempt by context or an `i18n:` marker. */
        fun proseLiterals(src: String): List<KotlinLiteral> {
            val lines = src.lines()
            val header = lines.takeWhile { !it.trimStart().startsWith("package ") }
            if (header.any { marker.containsMatchIn(it) }) return emptyList()
            var inBlock = false
            val exemptLine = BooleanArray(lines.size + 1) // 1-based
            lines.forEachIndexed { i, l ->
                if (blockBegin.containsMatchIn(l)) inBlock = true
                if (inBlock) exemptLine[i + 1] = true
                if (blockEnd.containsMatchIn(l)) inBlock = false
            }
            return kotlinStringLiterals(src).filter { lit ->
                val line = lines.getOrElse(lit.line - 1) { "" }
                val above = lines.getOrElse(lit.line - 2) { "" }
                val lineStart = src.lastIndexOf('\n', lit.offset - 1) + 1
                val before = src.substring(lineStart, lit.offset)
                isProseLike(lit.text) &&
                    !exemptLine[lit.line] &&
                    !marker.containsMatchIn(line) &&
                    !(above.trimStart().startsWith("//") && marker.containsMatchIn(above)) &&
                    !nonUiContext.containsMatchIn(before)
            }
        }
    }
}
