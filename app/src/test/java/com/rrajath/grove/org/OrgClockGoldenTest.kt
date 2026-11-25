package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Bloom's clock edits against Org's own, byte for byte. Each case in
 * `golden/clock/` names its edits in `# ops:` / `# options:` header comments;
 * `scripts/clock-goldens.el` ran them through Org 9.8.10 (in [ZONE], C locale)
 * to write `NAME.expected.org`, and this test runs them through [OrgMutations].
 */
class OrgClockGoldenTest {

    private val keywords = OrgKeywords.parse("TODO NEXT | DONE")

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/golden/clock/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun header(text: String, key: String): String? =
        Regex("^# $key: (.*)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    private fun time(s: String) = LocalDateTime.parse(s)

    /** The case's ops, with `in` closing whatever runs first, as `org-clock-in` does. */
    private fun run(text: String): String {
        val options = header(text, "options")?.split(' ').orEmpty()
        val removeZero = "remove-zero" in options
        var doc = OrgParser.parse(text, keywords)
        fun running() = doc.headlines.first { doc.runningClock(it) != null }
        fun apply(newText: String?) {
            doc = OrgParser.parse(newText!!, keywords)
        }
        for (op in header(text, "ops")!!.split('|').map { it.trim() }) {
            val parts = op.split(' ')
            when (parts[0]) {
                "in" -> {
                    val at = time(parts[2])
                    doc.headlines.firstOrNull { doc.runningClock(it) != null }
                        ?.let { apply(OrgMutations.clockOut(doc, it, at, removeZero, zone = ZONE)) }
                    apply(OrgMutations.clockIn(doc, doc.headlines.first { it.title == parts[1] }, at))
                }
                "out" -> apply(OrgMutations.clockOut(doc, running(), time(parts[1]), removeZero, zone = ZONE))
                "cancel" -> apply(OrgMutations.cancelClock(doc, running()))
                else -> error("bad op $op")
            }
        }
        return doc.text
    }

    @Test
    fun `clock edits match what Org writes`() {
        for (name in CASES) {
            val input = resource("$name.org")
            assertEquals("golden case $name", resource("$name.expected.org"), run(input))
        }
    }

    @Test
    fun `every golden case still round-trips through the parser`() {
        for (name in CASES) {
            val expected = resource("$name.expected.org")
            assertEquals(name, expected, OrgParser.parse(expected, keywords).serialize())
        }
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/New_York")

        val CASES = listOf(
            "adapt-indentation",
            "cancel-drops-drawer",
            "cancel-keeps-drawer",
            "dst-spring-forward",
            "existing-logbook",
            "long-clock",
            "no-drawer",
            "planning-only",
            "properties",
            "switch-task",
            "zero-kept",
            "zero-removed",
            "zero-removed-drawer-stays",
        )
    }
}
