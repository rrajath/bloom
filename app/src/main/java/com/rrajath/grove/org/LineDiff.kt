package com.rrajath.grove.org

/**
 * Where a line of [before] sits in [after], for two versions of one file that
 * differ in a single run of lines (what one in-app edit produces). Lines in
 * the unchanged lead and tail carry over, the tail shifted by the change in
 * length. Inside the changed run, a heading is followed only when the run
 * holds the same headings (by level and title) in the same order, as after a
 * keyword, priority, tag or planning change; anything else there maps to null.
 */
class LineDiff(private val before: OrgDocument, private val after: OrgDocument) {
    private val lead: Int
    private val tail: Int

    init {
        val b = before.lines
        val a = after.lines
        val max = minOf(b.size, a.size)
        var p = 0
        while (p < max && b[p] == a[p]) p++
        var s = 0
        while (s < max - p && b[b.size - 1 - s] == a[a.size - 1 - s]) s++
        lead = p
        tail = s
    }

    private val beforeRunEnd get() = before.lines.size - tail
    private val afterRunEnd get() = after.lines.size - tail

    /** Where the heading at [line] of [before] is in [after]; null when it's gone or can't be told apart. */
    fun heading(line: Int): Int? = outsideRun(line) ?: run {
        val was = before.headlines.filter { it.lineIndex in lead until beforeRunEnd }
        val now = after.headlines.filter { it.lineIndex in lead until afterRunEnd }
        val i = was.indexOfFirst { it.lineIndex == line }
        val same = was.size == now.size && was.zip(now).all { (w, n) -> w.level == n.level && w.title == n.title }
        if (i < 0 || !same) null else now[i].lineIndex
    }

    /**
     * Where the body line [line] of [before] is in [after]. A line inside the
     * run is followed only when the run kept its length and that line changed
     * at most in its checkbox (one checklist item toggled).
     */
    fun bodyLine(line: Int): Int? = outsideRun(line) ?: run {
        if (afterRunEnd != beforeRunEnd || line !in before.lines.indices) return null
        val b = before.lines[line]
        val a = after.lines[line]
        if (b.length == a.length && b.replaceFirst(BOX, "[ ]") == a.replaceFirst(BOX, "[ ]")) line else null
    }

    private fun outsideRun(line: Int): Int? = when {
        line < lead -> line
        line >= beforeRunEnd -> line + (afterRunEnd - beforeRunEnd)
        else -> null
    }

    private companion object {
        val BOX = Regex("""\[[ Xx\-]\]""")
    }
}
