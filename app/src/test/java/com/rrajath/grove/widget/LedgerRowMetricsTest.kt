package com.rrajath.grove.widget

import com.rrajath.grove.settings.ThemePreference
import com.rrajath.grove.ui.theme.groveColorsFor
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerRowMetricsTest {
    // Roughly Roboto (13sp) and Droid Sans Mono (9.5sp) at density 3, sizes in px.
    private val density = 3f
    private val title = LedgerRowMetrics.Font(top = -41.2f, bottom = 10.6f, capTop = -27.7f)
    private val chip = LedgerRowMetrics.Font(top = -30.4f, bottom = 7.8f, capTop = -20.8f)

    @Test fun pillHasEqualVisibleGapAboveCapsAndBelowBaseline() {
        val m = LedgerRowMetrics.compute(title, chip, density)
        val gapAbove = m.chipPaddingTop * density + (chip.capTop - chip.top)
        val gapBelow = m.chipPaddingBottom * density + chip.bottom
        assertEquals(gapAbove, gapBelow, 0.001f)
        assertEquals(2f, minOf(m.chipPaddingTop, m.chipPaddingBottom), 0.001f)
    }

    @Test fun pillKeywordSharesTheTitleBaseline() {
        val m = LedgerRowMetrics.compute(title, chip, density)
        val chipBaseline = (m.chipOffset + m.chipPaddingTop) * density - chip.top
        val titleBaseline = m.titleOffset * density - title.top
        assertEquals(titleBaseline, chipBaseline, 0.001f)
    }

    @Test fun titleMovesDownWhenThePillIsTallerAboveItsBaseline() {
        val bigChip = LedgerRowMetrics.Font(top = -60f, bottom = 15f, capTop = -40f)
        val m = LedgerRowMetrics.compute(title, bigChip, density)
        assertEquals(0f, m.chipOffset, 0f)
        val chipBaseline = m.chipPaddingTop * density - bigChip.top
        assertEquals(m.titleOffset * density - title.top, chipBaseline, 0.001f)
    }

    @Test fun circleCentersOnThePillCapitals() {
        val m = LedgerRowMetrics.compute(title, chip, density)
        val chipBaseline = (m.chipOffset + m.chipPaddingTop) * density - chip.top
        assertEquals((chipBaseline + chip.capTop / 2f) / density, m.centerY, 0.001f)
    }

    @Test fun doneKeywordGetsTheGreenPillLikeTheApp() {
        val colors = groveColorsFor(ThemePreference.DARK)
        assertEquals(colors.green to colors.greenSoft, stateChipColors(colors, "DONE", isDone = true))
        assertEquals(colors.synTodo to colors.amberSoft, stateChipColors(colors, "TODO", isDone = false))
    }
}
