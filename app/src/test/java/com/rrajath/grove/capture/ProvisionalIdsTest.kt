package com.rrajath.grove.capture

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class ProvisionalIdsTest {

    @Test
    fun `only ids the allocator changed become swaps`() {
        assertEquals(mapOf("B" to "Z"), idSwaps(listOf("A", "B", "C"), listOf("A", "Z", "C")))
        assertEquals(emptyMap<String, String>(), idSwaps(listOf("A"), listOf("A")))
    }

    @Test
    fun `a context swaps its own id and extra ids`() {
        val context = CaptureContext(now = LocalDateTime.of(2026, 11, 26, 9, 0), id = "A", extraIds = listOf("B", "C"))
        val swapped = context.withIdSwaps(mapOf("A" to "X", "C" to "Z"))
        assertEquals("X", swapped.id)
        assertEquals(listOf("B", "Z"), swapped.extraIds)
        assertEquals(context, context.withIdSwaps(emptyMap()))
    }

    @Test
    fun `every occurrence is swapped in text`() {
        assertEquals(":ID: NEW\n[[id:NEW]]", ":ID: OLD\n[[id:OLD]]".withIdSwaps(mapOf("OLD" to "NEW")))
    }

    @Test
    fun `the cursor follows the text around a swap of a different length`() {
        val text = "a OLD b"
        val swaps = mapOf("OLD" to "LONGER")
        // Before the ID: unmoved. After it: shifted by the length change.
        assertEquals("a LONGER b" to TextRange(1), swapIdsKeepingSelection(text, TextRange(1), swaps))
        assertEquals("a LONGER b" to TextRange(10), swapIdsKeepingSelection(text, TextRange(7), swaps))
        // At the ID's start: unmoved. Inside or at its end: the replacement's end.
        assertEquals("a LONGER b" to TextRange(2), swapIdsKeepingSelection(text, TextRange(2), swaps))
        assertEquals("a LONGER b" to TextRange(8), swapIdsKeepingSelection(text, TextRange(3), swaps))
        assertEquals("a LONGER b" to TextRange(8), swapIdsKeepingSelection(text, TextRange(5), swaps))
        // A selection's two ends move independently.
        assertEquals("a LONGER b" to TextRange(0, 10), swapIdsKeepingSelection(text, TextRange(0, 7), swaps))
    }

    @Test
    fun `nothing to swap gives null`() {
        assertNull(swapIdsKeepingSelection("no ids here", TextRange(0), mapOf("OLD" to "NEW")))
        assertNull(swapIdsKeepingSelection("OLD", TextRange(0), emptyMap()))
    }
}
