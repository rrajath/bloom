package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrgTagsTest {

    @Test
    fun `accepted tags parse back as tags on a headline`() {
        for (tag in listOf("ATTACH", "receipt", "work_2026", "@home", "#x", "50%", "a-b", "ملاحظة", " padded ")) {
            assertNull(tag, OrgTags.error(tag))
            val h = OrgParser.parse(OrgMutations.headlineLine(1, null, null, "T", listOf(tag.trim()))).headlines.single()
            assertEquals(tag, listOf(tag.trim()), h.tags)
        }
    }

    @Test
    fun `rejected tags`() {
        for (tag in listOf("", "  ", "a b", "a:b", ":a:", "a.b", "a/b", "a+b")) {
            assertNotNull(tag, OrgTags.error(tag))
        }
    }
}
