package com.rrajath.grove.org

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineEditingTest {

    /** Simulate typing Enter at [cursor] in [text] and running the helper. */
    private fun pressEnter(text: String, cursor: Int): TextEdit? {
        val typed = text.substring(0, cursor) + "\n" + text.substring(cursor)
        return LineEditing.continueListOnEnter(typed, cursor + 1)
    }

    @Test
    fun `dash item continues with a dash`() {
        val edit = pressEnter("- milk", 6)!!
        assertEquals("- milk\n- ", edit.text)
        assertEquals(edit.text.length, edit.cursor)
    }

    @Test
    fun `plus item continues with a plus`() {
        val edit = pressEnter("+ eggs", 6)!!
        assertEquals("+ eggs\n+ ", edit.text)
    }

    @Test
    fun `indentation is preserved`() {
        val edit = pressEnter("  - nested", 10)!!
        assertEquals("  - nested\n  - ", edit.text)
    }

    @Test
    fun `numbered item increments`() {
        val edit = pressEnter("3. third", 8)!!
        assertEquals("3. third\n4. ", edit.text)
        val paren = pressEnter("1) first", 8)!!
        assertEquals("1) first\n2) ", paren.text)
    }

    @Test
    fun `enter on an empty item removes the bullet`() {
        val edit = pressEnter("- a\n- ", 6)!!
        assertEquals("- a\n", edit.text)
        assertEquals(4, edit.cursor)
    }

    @Test
    fun `enter on an empty numbered item removes the bullet`() {
        val edit = pressEnter("1. a\n2. ", 8)!!
        assertEquals("1. a\n", edit.text)
        assertEquals(5, edit.cursor)
    }

    @Test
    fun `enter mid-list keeps the following text after the new bullet`() {
        val edit = pressEnter("- one\n- three", 5)!!
        assertEquals("- one\n- \n- three", edit.text)
        assertEquals(8, edit.cursor)
    }

    // --- checklist items ---

    @Test
    fun `checklist item continues with a fresh unchecked box`() {
        val edit = pressEnter("- [ ] milk", 10)!!
        assertEquals("- [ ] milk\n- [ ] ", edit.text)
        assertEquals(edit.text.length, edit.cursor)
    }

    @Test
    fun `checked item continues unchecked, not carrying the done state forward`() {
        val edit = pressEnter("- [X] milk", 10)!!
        assertEquals("- [X] milk\n- [ ] ", edit.text)
    }

    @Test
    fun `in-progress item continues unchecked`() {
        val edit = pressEnter("- [-] milk", 10)!!
        assertEquals("- [-] milk\n- [ ] ", edit.text)
    }

    @Test
    fun `numbered checklist item increments and keeps the checkbox`() {
        val edit = pressEnter("1. [ ] first", 12)!!
        assertEquals("1. [ ] first\n2. [ ] ", edit.text)
    }

    @Test
    fun `checklist indentation is preserved`() {
        val edit = pressEnter("  - [ ] nested", 14)!!
        assertEquals("  - [ ] nested\n  - [ ] ", edit.text)
    }

    @Test
    fun `enter on an empty checklist item removes it`() {
        val edit = pressEnter("- [ ] a\n- [ ] ", 14)!!
        assertEquals("- [ ] a\n", edit.text)
        assertEquals(8, edit.cursor)
    }

    @Test
    fun `enter on an empty checked item removes it`() {
        val edit = pressEnter("- [X] a\n- [X] ", 14)!!
        assertEquals("- [X] a\n", edit.text)
    }

    @Test
    fun `checklist indent preserves the box state`() {
        val edit = LineEditing.changeListIndent("- [ ] one\n- [X] two", 13, +1)!!
        assertEquals("- [ ] one\n  - [X] two", edit.text)
    }

    @Test
    fun `checklist outdent preserves the box state`() {
        val edit = LineEditing.changeListIndent("- [ ] one\n  - [-] two", 15, -1)!!
        assertEquals("- [ ] one\n- [-] two", edit.text)
    }

    @Test
    fun `indenting a numbered checklist item restarts numbering and keeps the box`() {
        val edit = LineEditing.changeListIndent("1. [ ] one\n2. [X] two", 17, +1)!!
        assertEquals("1. [ ] one\n  1. [X] two", edit.text)
    }

    @Test
    fun `non-list lines are untouched`() {
        assertNull(pressEnter("plain text", 10))
        assertNull(pressEnter("* heading", 9))
    }

    // Distinguishing a genuine Enter press from a paste/drop that merely ends in a
    // newline is `OrgInputTransformation`'s job now (it reads the field's change
    // list), not this pure function's — see its `insertedSoloNewlineAt`. Given a
    // cursor sitting right after a "\n", this helper always looks at whatever line
    // precedes it.

    @Test
    fun `heading button starts a new heading from normal text`() {
        val edit = LineEditing.insertHeadingStar("some text", 9)
        assertEquals("some text\n* ", edit.text)
        assertEquals(edit.text.length, edit.cursor)
    }

    @Test
    fun `heading button on a heading line inserts a subheading`() {
        val edit = LineEditing.insertHeadingStar("* title", 7)
        assertEquals("* title\n** ", edit.text)
        assertEquals(edit.text.length, edit.cursor)
    }

    @Test
    fun `heading button in body text inserts a subheading of the owning heading`() {
        val text = "* a\n** b\n*** c\nsome body"
        val edit = LineEditing.insertHeadingStar(text, text.length)
        assertEquals("$text\n**** ", edit.text)
    }

    @Test
    fun `heading button cycles subheading, same level, up to one star, then wraps`() {
        val base = "*** c"
        var edit = LineEditing.insertHeadingStar(base, base.length)
        val seen = mutableListOf(edit.text.removePrefix("$base\n"))
        repeat(4) {
            edit = LineEditing.insertHeadingStar(edit.text, edit.cursor)
            seen += edit.text.removePrefix("$base\n")
            assertEquals(edit.text.length, edit.cursor)
        }
        assertEquals(listOf("**** ", "*** ", "** ", "* ", "**** "), seen)
    }

    @Test
    fun `heading cycle without trailing space`() {
        val edit = LineEditing.insertHeadingStar("** c\n***", 8)
        assertEquals("** c\n** ", edit.text)
    }

    @Test
    fun `heading cycle works on a middle line and skips empty headings for context`() {
        val edit = LineEditing.insertHeadingStar("** top\n* \nbody", 9)
        assertEquals("** top\n*** \nbody", edit.text)
        assertEquals(11, edit.cursor)
    }

    @Test
    fun `heading button with no heading above stays at one star`() {
        val first = LineEditing.insertHeadingStar("some text", 9)
        assertEquals("some text\n* ", first.text)
        val again = LineEditing.insertHeadingStar(first.text, first.cursor)
        assertEquals("some text\n* ", again.text)
    }

    // --- new-note caret ---

    @Test
    fun `new-note caret body mode parks at the end and leaves text alone`() {
        val edit = LineEditing.newNoteCaret("* \n", heading = false)
        assertEquals("* \n", edit.text)
        assertEquals(3, edit.cursor)
    }

    @Test
    fun `new-note caret heading mode lands just after the star and space`() {
        val edit = LineEditing.newNoteCaret("* \n", heading = true)
        assertEquals("* \n", edit.text)
        assertEquals(2, edit.cursor)
    }

    @Test
    fun `new-note caret heading mode inserts the missing space`() {
        val edit = LineEditing.newNoteCaret("*\nbody", heading = true)
        assertEquals("* \nbody", edit.text)
        assertEquals(2, edit.cursor)
    }

    @Test
    fun `new-note caret heading mode skips past a property drawer below the heading`() {
        val edit = LineEditing.newNoteCaret("* \n:PROPERTIES:\n:ID: x\n:END:\n", heading = true)
        assertEquals(2, edit.cursor)
        assertEquals("* \n:PROPERTIES:\n:ID: x\n:END:\n", edit.text)
    }

    // --- list indent buttons ---

    @Test
    fun `indent turns an item into a sub-list item`() {
        val edit = LineEditing.changeListIndent("- one\n- two", 9, +1)!!
        assertEquals("- one\n  - two", edit.text)
        assertEquals(11, edit.cursor)
    }

    @Test
    fun `outdent promotes a sub-list item`() {
        val edit = LineEditing.changeListIndent("- one\n  - two", 11, -1)!!
        assertEquals("- one\n- two", edit.text)
        assertEquals(9, edit.cursor)
    }

    @Test
    fun `indenting an ordered item restarts numbering at 1`() {
        val edit = LineEditing.changeListIndent("1. one\n2. two", 13, +1)!!
        assertEquals("1. one\n  1. two", edit.text)
    }

    @Test
    fun `indenting a paren-numbered item restarts at 1 keeping the suffix`() {
        val edit = LineEditing.changeListIndent("1) one\n3) three", 15, +1)!!
        assertEquals("1) one\n  1) three", edit.text)
    }

    @Test
    fun `outdenting an ordered sub-item keeps its number`() {
        val edit = LineEditing.changeListIndent("1. one\n  1. sub", 15, -1)!!
        assertEquals("1. one\n1. sub", edit.text)
    }

    @Test
    fun `outdent at column zero does nothing`() {
        assertNull(LineEditing.changeListIndent("- one", 5, -1))
    }

    @Test
    fun `indent on a non-list line does nothing`() {
        assertNull(LineEditing.changeListIndent("plain text", 5, +1))
        assertNull(LineEditing.changeListIndent("* heading", 5, +1))
    }

    @Test
    fun `outdent of a single-space indent removes just that space`() {
        val edit = LineEditing.changeListIndent(" - one", 6, -1)!!
        assertEquals("- one", edit.text)
        assertEquals(5, edit.cursor)
    }

    @Test
    fun `cursor at line start stays at line start on outdent`() {
        val edit = LineEditing.changeListIndent("- one\n  - two", 6, -1)!!
        assertEquals("- one\n- two", edit.text)
        assertEquals(6, edit.cursor)
    }

    @Test
    fun `indent only affects the cursor line`() {
        val edit = LineEditing.changeListIndent("- one\n- two\n- three", 8, +1)!!
        assertEquals("- one\n  - two\n- three", edit.text)
    }

    @Test
    fun `single key press capitalizes the first heading letter`() {
        val edit = LineEditing.capitalizeHeadingOnType("* ", "* h", 3)!!
        assertEquals("* H", edit.text)
        assertEquals(3, edit.cursor)
    }

    @Test
    fun `swipe-typed word capitalizes its first letter`() {
        // Gboard delivers a swiped word as one multi-character commitText, not
        // one key event per letter, so newText grows by more than 1 char here.
        val edit = LineEditing.capitalizeHeadingOnType("* ", "* hello", 7)!!
        assertEquals("* Hello", edit.text)
        assertEquals(7, edit.cursor)
    }

    @Test
    fun `swipe-typed word on a sub-heading capitalizes too`() {
        val edit = LineEditing.capitalizeHeadingOnType("** ", "** buy milk", 11)!!
        assertEquals("** Buy milk", edit.text)
    }

    @Test
    fun `already-uppercase first letter is left alone`() {
        assertNull(LineEditing.capitalizeHeadingOnType("* ", "* Hello", 7))
    }

    @Test
    fun `typing past the first character is not touched`() {
        assertNull(LineEditing.capitalizeHeadingOnType("* h", "* he", 4))
    }

    @Test
    fun `typing into a non-heading line does nothing`() {
        assertNull(LineEditing.capitalizeHeadingOnType("", "hello", 5))
    }

    @Test
    fun `a plain deletion is not treated as an insertion`() {
        assertNull(LineEditing.capitalizeHeadingOnType("* hello", "* hell", 6))
    }

    @Test
    fun `single key press capitalizes the first letter after a TODO keyword`() {
        val edit = LineEditing.capitalizeHeadingOnType("* TODO ", "* TODO b", 8)!!
        assertEquals("* TODO B", edit.text)
        assertEquals(8, edit.cursor)
    }

    @Test
    fun `swipe-typed word after a TODO keyword capitalizes too`() {
        val edit = LineEditing.capitalizeHeadingOnType("* TODO ", "* TODO buy milk", 15)!!
        assertEquals("* TODO Buy milk", edit.text)
    }

    @Test
    fun `a custom keyword configuration is honored`() {
        val keywords = OrgKeywords.parse("NEXT | DONE")
        val edit = LineEditing.capitalizeHeadingOnType("* NEXT ", "* NEXT b", 8, keywords)!!
        assertEquals("* NEXT B", edit.text)
    }

    @Test
    fun `a keyword not in the configured list is not treated as empty heading content`() {
        assertNull(LineEditing.capitalizeHeadingOnType("* NEXT ", "* NEXT b", 8))
    }

    // --- numbered list item capitalization ---

    @Test
    fun `single key press capitalizes the first letter of a numbered item`() {
        val edit = LineEditing.capitalizeListItemOnType("1. ", "1. o", 4)!!
        assertEquals("1. O", edit.text)
        assertEquals(4, edit.cursor)
    }

    @Test
    fun `paren-style numbered bullet also capitalizes`() {
        val edit = LineEditing.capitalizeListItemOnType("2) ", "2) o", 4)!!
        assertEquals("2) O", edit.text)
    }

    @Test
    fun `swipe-typed word on a numbered item capitalizes its first letter`() {
        val edit = LineEditing.capitalizeListItemOnType("1. ", "1. open a note", 14)!!
        assertEquals("1. Open a note", edit.text)
    }

    @Test
    fun `numbered item capitalization works mid-document`() {
        val edit = LineEditing.capitalizeListItemOnType("1. first\n2. ", "1. first\n2. t", 13)!!
        assertEquals("1. first\n2. T", edit.text)
    }

    @Test
    fun `already-uppercase first letter on a numbered item is left alone`() {
        assertNull(LineEditing.capitalizeListItemOnType("1. ", "1. O", 4))
    }

    @Test
    fun `typing past the first character of a numbered item is not touched`() {
        assertNull(LineEditing.capitalizeListItemOnType("1. o", "1. op", 5))
    }

    @Test
    fun `dash bullets are not capitalized as numbered items`() {
        assertNull(LineEditing.capitalizeListItemOnType("- ", "- o", 3))
    }
}
