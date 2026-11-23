package com.rrajath.grove.testing.support

import com.rrajath.grove.R
import com.rrajath.grove.ui.util.UiText
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resolves a [UiText] to its English text on the plain JVM (no Robolectric), by
 * reading the base `res/values/strings*.xml`. Lets pure-function tests keep
 * asserting readable text like "Today" after the code under test moved to [UiText].
 * Plurals pick `one` for 1 and `other` for everything else (English rules).
 */
fun UiText.english(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> EnglishStrings.format(EnglishStrings.string(id), args)
    is UiText.Plural -> EnglishStrings.format(EnglishStrings.plural(id, count), args)
}

private object EnglishStrings {
    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val dir = File(appModuleDir, "src/main/res/values")
        dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strs = doc.getElementsByTagName("string")
            for (i in 0 until strs.length) {
                val e = strs.item(i) as Element
                strings[e.getAttribute("name")] = unescape(e.textContent)
            }
            val pls = doc.getElementsByTagName("plurals")
            for (i in 0 until pls.length) {
                val e = pls.item(i) as Element
                val items = e.getElementsByTagName("item")
                plurals[e.getAttribute("name")] = (0 until items.length).associate { j ->
                    val item = items.item(j) as Element
                    item.getAttribute("quantity") to unescape(item.textContent)
                }
            }
        }
    }

    private fun nameOf(id: Int, holder: Class<*>): String =
        holder.fields.first { it.getInt(null) == id }.name

    fun string(id: Int): String {
        val name = nameOf(id, R.string::class.java)
        return strings[name] ?: error("no English string $name")
    }

    fun plural(id: Int, count: Int): String {
        val name = nameOf(id, R.plurals::class.java)
        val forms = plurals[name] ?: error("no English plurals $name")
        return forms[if (count == 1) "one" else "other"] ?: forms.getValue("other")
    }

    fun format(pattern: String, args: List<Any>): String {
        if (args.isEmpty()) return pattern
        val resolved = args.map { if (it is UiText) it.english() else it }.toTypedArray()
        return String.format(Locale.US, pattern, *resolved)
    }

    private fun unescape(s: String): String = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
}
