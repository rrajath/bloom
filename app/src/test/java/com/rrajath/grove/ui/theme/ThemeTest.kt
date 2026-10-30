package com.rrajath.grove.ui.theme

import androidx.compose.ui.graphics.Color
import com.rrajath.grove.settings.ThemePreference
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    @Test
    fun `groveColorsFor maps every ThemePreference to its matching palette`() {
        assertEquals(GroveLightColors, groveColorsFor(ThemePreference.LIGHT))
        assertEquals(GroveDarkColors, groveColorsFor(ThemePreference.DARK))
        assertEquals(GroveTokyoNightColors, groveColorsFor(ThemePreference.TOKYONIGHT))
        assertEquals(GroveTokyoDayColors, groveColorsFor(ThemePreference.TOKYODAY))
        assertEquals(GroveCatppuccinColors, groveColorsFor(ThemePreference.CATPPUCCIN))
        assertEquals(GroveCatppuccinLatteColors, groveColorsFor(ThemePreference.CATPPUCCINLATTE))
        assertEquals(GroveRosePineDawnColors, groveColorsFor(ThemePreference.ROSEPINEDAWN))
        assertEquals(GroveRosePineMoonColors, groveColorsFor(ThemePreference.ROSEPINEMOON))
        assertEquals(GroveChalkColors, groveColorsFor(ThemePreference.CHALK))
        assertEquals(GroveCobaltColors, groveColorsFor(ThemePreference.COBALT))
        assertEquals(GroveDelftColors, groveColorsFor(ThemePreference.DELFT))
        assertEquals(GroveSageColors, groveColorsFor(ThemePreference.SAGE))
        assertEquals(GroveAshRoseColors, groveColorsFor(ThemePreference.ASHROSE))
        assertEquals(GroveObsidianColors, groveColorsFor(ThemePreference.OBSIDIAN))
        assertEquals(GroveLanternColors, groveColorsFor(ThemePreference.LANTERN))
        assertEquals(GroveLighthouseColors, groveColorsFor(ThemePreference.LIGHTHOUSE))
        assertEquals(GroveMossColors, groveColorsFor(ThemePreference.MOSS))
        assertEquals(GroveWisteriaColors, groveColorsFor(ThemePreference.WISTERIA))
        assertEquals(GroveBloomLightColors, groveColorsFor(ThemePreference.BLOOMLIGHT))
        assertEquals(GroveBloomDarkColors, groveColorsFor(ThemePreference.BLOOMDARK))
    }

    // Keyword keys (#+title:, :width, drawer keys) render in synKw next to values
    // in ink2/synProp; a near-gray synKw makes the key blend into its value.
    @Test
    fun `every palette's synKw stands apart from key-value text colors`() {
        fun distance(a: Color, b: Color): Float {
            val dr = (a.red - b.red) * 255
            val dg = (a.green - b.green) * 255
            val db = (a.blue - b.blue) * 255
            return sqrt(dr * dr + dg * dg + db * db)
        }
        for (theme in ThemePreference.entries) {
            val c = groveColorsFor(theme)
            assertTrue("$theme synKw too close to ink2", distance(c.synKw, c.ink2) > 30f)
            assertTrue("$theme synKw too close to synProp", distance(c.synKw, c.synProp) > 30f)
        }
    }

    @Test
    fun `ThemePreference isDark matches each palette's isDark`() {
        for (theme in ThemePreference.entries) {
            assertEquals("$theme", groveColorsFor(theme).isDark, theme.isDark)
        }
    }
}
