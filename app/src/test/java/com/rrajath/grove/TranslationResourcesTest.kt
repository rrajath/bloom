package com.rrajath.grove

import com.rrajath.grove.testing.support.appModuleDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the translated string resources Weblate commits into `res/values-xx/`.
 * Lint catches some of this at release time; this runs on every push, and also
 * covers what lint doesn't (URLs slipped into a translation).
 *
 * The checks live in [TranslationChecker] and run against the real resources
 * here, and against small hand-written fixtures below so each check is proven
 * to fire before any real translation exists.
 */
class TranslationResourcesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `app string resources pass the translation checks`() {
        val problems = TranslationChecker(File(appModuleDir, "src/main/res")).problems()
        assertTrue("Translation resource problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `a clean translation passes`() {
        val res = res(
            base = """
                <string name="saved_to">Saved to %1${'$'}s</string>
                <string name="moved">Moved %1${'$'}d notes to %2${'$'}s</string>
                <plurals name="notes"><item quantity="one">%d note</item><item quantity="other">%d notes</item></plurals>
                <string name="app_name" translatable="false">Bloom</string>
                <string name="help">See https://bloom.rrajath.com</string>
            """,
            "values-de" to """
                <string name="saved_to">Gespeichert in %1${'$'}s</string>
                <string name="moved">Nach %2${'$'}s verschoben: %1${'$'}d Notizen</string>
                <plurals name="notes"><item quantity="one">Eine Notiz</item><item quantity="other">%d Notizen</item></plurals>
                <string name="help">Siehe https://bloom.rrajath.com</string>
            """,
        )
        assertEquals(emptyList<String>(), TranslationChecker(res).problems())
    }

    @Test
    fun `each check fires`() {
        val res = res(
            base = """
                <string name="saved_to">Saved to %1${'$'}s</string>
                <string name="two_unnumbered">%s of %s</string>
                <plurals name="notes"><item quantity="one">%d note</item><item quantity="other">%d notes</item></plurals>
                <string name="app_name" translatable="false">Bloom</string>
                <string name="plain">Open</string>
            """,
            "values-fr" to """
                <string name="saved_to">Enregistré dans %1${'$'}d</string>
                <plurals name="notes"><item quantity="one">%s note</item><item quantity="other">%d notes</item></plurals>
                <string name="app_name">Blume</string>
                <string name="gone">Old key</string>
                <string name="plain">Ouvrir http://spam.example</string>
            """,
            broken = mapOf("values-es" to """<resources><string name="saved_to">Guardado en %1${'$'}s</resources>"""),
        )
        val problems = TranslationChecker(res).problems().joinToString("\n")
        listOf(
            "values/strings.xml: two_unnumbered: 2 placeholders must be numbered",
            "values-fr/strings.xml: saved_to: placeholders [%1\$d] don't match English [%1\$s]",
            "values-fr/strings.xml: notes: placeholders [%1\$s] (quantity one) aren't in English [%1\$d]",
            "values-fr/strings.xml: app_name: marked translatable=\"false\" in English",
            "values-fr/strings.xml: gone: not in English (delete it from every values-xx file)",
            "values-fr/strings.xml: plain: URL not in English: http://spam.example",
            "values-es/strings.xml: doesn't parse",
        ).forEach { assertTrue("expected \"$it\" in:\n$problems", it in problems) }
    }

    private fun res(base: String, vararg translations: Pair<String, String>, broken: Map<String, String> = emptyMap()): File {
        val root = tmp.newFolder("res")
        fun write(dir: String, content: String) = File(root, "$dir/strings.xml").apply {
            parentFile.mkdirs()
            writeText(content)
        }
        fun wrap(body: String) = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>${body.trimIndent()}</resources>\n"
        write("values", wrap(base))
        translations.forEach { (dir, body) -> write(dir, wrap(body)) }
        broken.forEach { (dir, content) -> write(dir, content) }
        return root
    }
}

/** Checks `values-xx/` string resources against the English ones in `values/`. */
class TranslationChecker(private val resDir: File) {

    private class Entry(val name: String, val translatable: Boolean, val texts: Map<String, String>)

    fun problems(): List<String> {
        val out = mutableListOf<String>()
        val parsed = resDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .sortedBy { it.name }
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }.map { dir to it } }
            .mapNotNull { (dir, file) ->
                val label = "${dir.name}/${file.name}"
                val entries = runCatching { parse(file) }.getOrElse {
                    out += "$label: doesn't parse (${it.message})"
                    return@mapNotNull null
                }
                Triple(dir, label, entries)
            }

        val base = parsed.filter { it.first.name == "values" }.flatMap { it.third }.associateBy { it.name }
        for (entry in base.values) {
            entry.texts.forEach { (_, text) ->
                val ph = PLACEHOLDER.findAll(text).map { it.value }.filter { it != "%%" && it != "%n" }.toList()
                if (ph.size > 1 && ph.any { '$' !in it }) {
                    out += "values/${fileOf(parsed, entry.name)}: ${entry.name}: ${ph.size} placeholders must be numbered (%1\$s, %2\$d) so translators can reorder them"
                }
            }
        }

        for ((dir, label, entries) in parsed) {
            if (!LOCALE_DIR.matches(dir.name)) continue
            for (entry in entries) {
                val english = base[entry.name]
                when {
                    english == null -> out += "$label: ${entry.name}: not in English (delete it from every values-xx file)"
                    !english.translatable -> out += "$label: ${entry.name}: marked translatable=\"false\" in English"
                    else -> out += compare(label, english, entry)
                }
            }
        }
        return out
    }

    private fun compare(label: String, english: Entry, translated: Entry): List<String> {
        val out = mutableListOf<String>()
        val englishAll = english.texts.values.flatMap { placeholders(it) }.toSortedSet()
        val englishUrls = english.texts.values.flatMap { urls(it) }.toSet()
        val isPlural = english.texts.keys.any { it.startsWith("quantity:") }
        for ((key, text) in translated.texts) {
            val ph = placeholders(text).toSortedSet()
            if (isPlural) {
                // A plural form may drop the count ("Eine Notiz"), but must not invent a placeholder.
                if (!englishAll.containsAll(ph)) {
                    out += "$label: ${translated.name}: placeholders $ph (${key.replace(':', ' ')}) aren't in English $englishAll"
                }
            } else {
                val englishPh = english.texts[key]?.let { placeholders(it).toSortedSet() } ?: englishAll
                if (ph != englishPh) out += "$label: ${translated.name}: placeholders $ph don't match English $englishPh"
            }
            urls(text).filter { it !in englishUrls }.forEach {
                out += "$label: ${translated.name}: URL not in English: $it"
            }
        }
        return out
    }

    private fun fileOf(parsed: List<Triple<File, String, List<Entry>>>, name: String): String =
        parsed.first { it.first.name == "values" && it.third.any { e -> e.name == name } }.second.substringAfter('/')

    private fun parse(file: File): List<Entry> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val out = mutableListOf<Entry>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val name = el.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
            val translatable = el.getAttribute("translatable") != "false"
            val texts = when (el.tagName) {
                "string" -> mapOf("text" to el.textContent)
                "plurals" -> items(el).associate { "quantity:" + it.getAttribute("quantity") to it.textContent }
                "string-array" -> items(el).withIndex().associate { (n, it) -> "item:$n" to it.textContent }
                else -> continue
            }
            out += Entry(name, translatable, texts)
        }
        return out
    }

    private fun items(el: Element): List<Element> {
        val nodes = el.getElementsByTagName("item")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** Placeholders normalized to positional form, so `%s` and `%1$s` compare equal. */
    private fun placeholders(text: String): List<String> {
        var next = 1
        return PLACEHOLDER.findAll(text).mapNotNull { m ->
            if (m.value == "%%" || m.value == "%n") return@mapNotNull null
            val index = m.groupValues[1].toIntOrNull() ?: next++
            "%$index\$${m.groupValues[2]}"
        }.toList()
    }

    private fun urls(text: String): List<String> = URL.findAll(text).map { it.value.trimEnd('.', ',', ')') }.toList()

    companion object {
        private val PLACEHOLDER = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")
        private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")

        /** `values-de`, `values-pt-rBR`, `values-b+sr+Latn`, optionally followed by more qualifiers. */
        private val LOCALE_DIR = Regex("""^values-(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z0-9+]+)(?:-.+)?$""")
    }
}
