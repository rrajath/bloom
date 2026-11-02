package com.rrajath.grove.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.testing.TestGroveApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #91: an outline heading's tags sit at the row's end on the title's last line
 * when they fit, else on their own line(s) below, wrapping between tags; the title
 * always keeps the full width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class TitleWithTrailingTagsRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(title: String, tags: List<String>, direction: LayoutDirection = LayoutDirection.Ltr) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                TitleWithTrailingTags(
                    tags = tags,
                    tagStyle = TextStyle(fontSize = 11.sp),
                    modifier = Modifier.width(300.dp).testTag("row"),
                ) { onTextLayout ->
                    Text(
                        title,
                        style = TextStyle(fontSize = 14.5.sp),
                        onTextLayout = onTextLayout,
                        modifier = Modifier.testTag("title"),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun tagBounds(): List<Rect> =
        composeRule.onAllNodesWithTag("heading_tag").fetchSemanticsNodes().map { it.boundsInRoot }

    @Test
    fun shortTitleKeepsTagsOnItsLineAtTheEnd() {
        show("Groceries", listOf("home", "errand"))
        val row = bounds("row")
        val title = bounds("title")
        val tags = tagBounds()
        assertEquals(row.height, title.height, 0.5f)
        tags.forEach { assertTrue("tag on the title line", it.top < title.bottom) }
        assertEquals(row.right, tags.maxOf { it.right }, 1f)
    }

    @Test
    fun wrappedTitleKeepsFullWidthAndTagsRideItsLastLine() {
        show("Plan the quarterly review with the whole team and book a room for it", listOf("work"))
        val row = bounds("row")
        val title = bounds("title")
        val tag = tagBounds().single()
        assertEquals("title gets the full width", row.width, title.width, 1f)
        assertEquals(row.height, title.height, 0.5f)
        assertTrue("tag on the last line", tag.top > title.top + title.height / 2)
        assertEquals(row.right, tag.right, 1f)
    }

    @Test
    fun tagsThatDontFitDropBelowTheTitle() {
        show("Weekly planning session", listOf("averyveryverylongtagnamethatkeepsgoing"))
        val row = bounds("row")
        val title = bounds("title")
        val tag = tagBounds().single()
        assertTrue("tag below the title", tag.top >= title.bottom)
        assertEquals(row.right, tag.right, 1f)
    }

    @Test
    fun manyTagsWrapBetweenTagsEachLineFlushToTheEnd() {
        show("Trip", List(10) { "tagname$it" })
        val row = bounds("row")
        val title = bounds("title")
        val lines = tagBounds().groupBy { it.top }
        assertTrue("wraps onto several lines", lines.size > 1)
        lines.values.forEach { line ->
            assertTrue(line.all { it.top >= title.bottom })
            assertEquals(row.right, line.maxOf { it.right }, 1f)
        }
    }

    @Test
    fun rtlRowPutsTagsAtTheLeftEnd() {
        show("خرید از بازار", listOf("home"), LayoutDirection.Rtl)
        val row = bounds("row")
        val title = bounds("title")
        val tag = tagBounds().single()
        assertTrue("tag on the title line", tag.top < title.bottom)
        assertEquals(row.left, tag.left, 1f)
    }
}
