package com.rrajath.grove.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every `{{…}}` marker in tip copy must become a keycap; none may show as raw text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h4000dp")
class SettingsTipsMarkupRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandedTipsShowNoRawMarkers() {
        composeRule.setContent { GroveTheme { SettingsTipsScreen(onBack = {}) } }
        composeRule.onNodeWithContentDescription("Expand all tips").performClick()
        composeRule.waitForIdle()

        val texts = mutableListOf<AnnotatedString>()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.let { texts += it }
            node.children.forEach(::walk)
        }
        walk(composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())

        assertTrue("tips should render", texts.any { it.text.contains("view the whole file") })
        // A keycap keeps its marker as the placeholder's alternate text, so a marker is only
        // "leaked" when no inline-content annotation covers it.
        val marker = Regex("\\{\\{[^}]*\\}\\}")
        val leaked = texts.flatMap { t ->
            val covered = t.getStringAnnotations(INLINE_CONTENT_TAG, 0, t.length)
            marker.findAll(t.text)
                .filter { m -> covered.none { it.start <= m.range.first && it.end > m.range.last } }
                .map { it.value }
                .toList()
        }
        assertTrue("raw markers leaked: $leaked", leaked.isEmpty())
    }
}

private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"
