package com.rrajath.grove.attach

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays org-attach-golden.tsv (generated from real Emacs by
 * scripts/org-attach-interop.sh) against [AttachDirResolver]. The org file lives
 * in `notes/` so relative id dirs and `..` are exercised against a non-root dir.
 */
class AttachDirResolverGoldenTest {

    @Test fun `resolver matches emacs org-attach-dir-from-id for every golden case`() = runBlocking {
        val stream = javaClass.classLoader!!.getResourceAsStream("attach/org-attach-golden.tsv")
            ?: error("golden file missing; run scripts/org-attach-interop.sh --update")
        val rows = stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("expected golden cases", rows.size >= 10)
        val orgDir = "notes"
        for (row in rows) {
            val (name, idDir, id, existing, emacs) = row.split('\t')
            val existingDirs = existing.split(',').filter { it != "-" }.map { "$orgDir/${it.trimEnd('/')}" }.toSet()
            val resolver = AttachDirResolver(DirExists { it in existingDirs }, AttachConfig(idDir = idDir))
            val expected = normalizeVaultPath("$orgDir/$emacs")
            val actual = resolver.writeDir("$orgDir/note.org", AttachOwner.Heading(0, listOf("H"), id, null))
            assertEquals("case $name", expected, actual)
        }
    }
}
