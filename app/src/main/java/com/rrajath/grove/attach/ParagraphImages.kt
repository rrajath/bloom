package com.rrajath.grove.attach

import com.rrajath.grove.org.InlineTokenizer
import com.rrajath.grove.org.InlineType

/** One piece of a read-mode paragraph once its image links are pulled out (tech design 7.1). */
sealed interface ParagraphSegment {
    /** Text lines rendered as one run; [lines] are already trimmed. */
    data class Text(val lines: List<String>) : ParagraphSegment

    /**
     * An inline image. [line] is the paragraph-relative line it came from (for
     * owner resolution), [rawTarget] the link target as written, [widthAttr]
     * the `#+ATTR_ORG: :width` that applies to it, if any.
     */
    data class Image(
        val link: LocalAttachment,
        val rawTarget: String,
        val line: Int,
        val widthAttr: Int? = null,
    ) : ParagraphSegment
}

/**
 * Splits a paragraph around its image links: an image link alone on its line
 * becomes an [ParagraphSegment.Image]; one inside a line splits the line, text
 * before above and text after below. [attrWidth] goes to the image only when the
 * paragraph opens with it (an `#+ATTR_ORG` keyword sits right above it).
 * A paragraph with no image link comes back as a single [ParagraphSegment.Text].
 */
fun splitParagraphAtImages(lines: List<String>, attrWidth: Int? = null): List<ParagraphSegment> {
    val out = mutableListOf<ParagraphSegment>()
    val pending = mutableListOf<String>()
    fun flush() {
        if (pending.isNotEmpty()) out += ParagraphSegment.Text(pending.toList())
        pending.clear()
    }
    var sawContent = false
    lines.forEachIndexed { lineIdx, raw ->
        val line = raw.trim()
        val images = imageLinksIn(line)
        if (images.isEmpty()) {
            pending += line
            if (line.isNotEmpty()) sawContent = true
            return@forEachIndexed
        }
        var pos = 0
        for (img in images) {
            val before = line.substring(pos, img.range.first).trim()
            if (before.isNotEmpty()) {
                pending += before
                sawContent = true
            }
            flush()
            val width = if (!sawContent) attrWidth else null
            out += ParagraphSegment.Image(img.link, img.rawTarget, lineIdx, width)
            sawContent = true
            pos = img.range.last + 1
        }
        val after = line.substring(pos).trim()
        if (after.isNotEmpty()) pending += after
    }
    flush()
    return out
}

/** True when [line] holds at least one link that renders as an inline image. */
fun hasImageLink(line: String): Boolean = imageLinksIn(line).isNotEmpty()

private data class ImageHit(val range: IntRange, val link: LocalAttachment, val rawTarget: String)

private fun imageLinksIn(line: String): List<ImageHit> {
    if (!line.contains("[[") && !line.contains("file:")) return emptyList()
    return InlineTokenizer.tokenize(line).mapNotNull { token ->
        if (token.type != InlineType.LINK) return@mapNotNull null
        val target = token.target ?: return@mapNotNull null
        val link = AttachmentLink.classify(target, linkDescription(line, token.range, token.text))
        if (link?.isImage == true) ImageHit(token.range, link, target) else null
    }
}

/**
 * The description of the link token at [range] in [line]: its display [text]
 * when the source is the bracketed `[[target][description]]` form, else null.
 */
fun linkDescription(line: String, range: IntRange, text: String): String? {
    val src = line.substring(range)
    return if (src.startsWith("[[") && src.contains("][")) text else null
}

private val ATTR_LINE = Regex("""^\s*#\+ATTR_[A-Za-z0-9_-]+:.*$""", RegexOption.IGNORE_CASE)
private val ATTR_ORG_WIDTH = Regex("""^\s*#\+ATTR_ORG:.*?:width\s+(\d+)(?:px)?(?![\w%.])""", RegexOption.IGNORE_CASE)

/** True when every line of a keyword run is an `#+ATTR_*:` line. */
fun isAttrOnlyRun(lines: List<String>): Boolean = lines.isNotEmpty() && lines.all { ATTR_LINE.matches(it) }

/** The `:width N` of the run's `#+ATTR_ORG:` line, in px, or null. */
fun attrOrgWidth(lines: List<String>): Int? =
    lines.firstNotNullOfOrNull { ATTR_ORG_WIDTH.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it > 0 }
