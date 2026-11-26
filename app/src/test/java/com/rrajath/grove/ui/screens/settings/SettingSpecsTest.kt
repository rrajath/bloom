package com.rrajath.grove.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingSpecsTest {

    @Test
    fun idsAreUnique() {
        val dupes = SettingSpecs.all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate spec ids: $dupes", dupes.isEmpty())
    }

    @Test
    fun inlineSpecsAreExactlyTheBoundOnes() {
        val inline = SettingSpecs.all.filter { it.inline }.map { it.id }.toSet()
        assertEquals(inline, INLINE_BINDINGS.keys)
    }

    @Test
    fun onlyTogglesAndChoicesAreInline() {
        val wrong = SettingSpecs.all.filter { it.inline && it.kind != SettingKind.TOGGLE && it.kind != SettingKind.CHOICE }
        assertTrue("inline specs must be toggles or choices: ${wrong.map { it.id }}", wrong.isEmpty())
    }

    @Test
    fun revealFallbacksPointAtASettingOnTheSamePage() {
        SettingSpecs.all.filter { it.revealFallback != null }.forEach { spec ->
            val fallback = SettingSpecs.byId(spec.revealFallback!!)
            assertTrue("${spec.id} falls back to unknown ${spec.revealFallback}", fallback != null)
            assertEquals("${spec.id} falls back off its page", spec.page, fallback!!.page)
        }
    }

    @Test
    fun matchingIsCaseInsensitiveOnLabelAndDescription() {
        val a = SettingSpecs.NotesAutoSave
        val b = SettingSpecs.NotesCapitalize
        val text = mapOf(a to ("Auto-save" to "Save notes as you type"), b to ("Capitalize headings" to null))
        val resolve = { s: SettingSpec -> text.getValue(s) }

        assertEquals(listOf(a), matchSettingSpecs("auto", listOf(a, b), resolve))
        assertEquals(listOf(a), matchSettingSpecs("AS YOU", listOf(a, b), resolve))
        assertEquals(listOf(b), matchSettingSpecs(" headings ", listOf(a, b), resolve))
        assertEquals(emptyList<SettingSpec>(), matchSettingSpecs("   ", listOf(a, b), resolve))
        assertEquals(emptyList<SettingSpec>(), matchSettingSpecs("nothing", listOf(a, b), resolve))
    }
}
