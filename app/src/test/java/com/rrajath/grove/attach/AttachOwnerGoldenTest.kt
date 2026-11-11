package com.rrajath.grove.attach

import com.rrajath.grove.org.OrgParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays org-attach-owner-golden.tsv (generated from real Emacs by
 * scripts/org-attach-interop.sh) against [resolveOwnerAtLine] plus
 * [AttachDirResolver]: for each fixture in `attach/owner/`, the attach dir at
 * the `# point` line must match Emacs's `(org-attach-dir nil t)` with `:DIR:` /
 * `:ATTACH_DIR:` inherited and `:ID:` not. No dirs exist, as in the script.
 */
class AttachOwnerGoldenTest {

    @Test fun `owner precedence matches emacs org-attach-dir for every fixture`() = runBlocking {
        val rows = resource("attach/org-attach-owner-golden.tsv").lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("expected owner golden cases", rows.size >= 20)
        val orgDir = "notes"
        val resolver = AttachDirResolver(DirExists { false }, AttachConfig.forFolder("data/"))
        for (row in rows) {
            val (name, emacs) = row.split('\t')
            val text = resource("attach/owner/$name.org")
            val doc = OrgParser.parse(text)
            val line = doc.lines.indexOf("# point").also { check(it >= 0) { "$name has no # point line" } }
            val owner = resolveOwnerAtLine(doc, line)
            val expected = if (emacs == "-") null else normalizeVaultPath("$orgDir/$emacs")
            assertEquals("case $name", expected, resolver.writeDir("$orgDir/$name.org", owner))
        }
    }

    private fun resource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("$path missing; run scripts/org-attach-interop.sh --update")
}
