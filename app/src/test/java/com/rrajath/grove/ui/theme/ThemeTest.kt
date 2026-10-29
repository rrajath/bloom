package com.rrajath.grove.ui.theme

import com.rrajath.grove.settings.ThemePreference
import org.junit.Assert.assertEquals
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

    @Test
    fun `ThemePreference isDark matches each palette's isDark`() {
        for (theme in ThemePreference.entries) {
            assertEquals("$theme", groveColorsFor(theme).isDark, theme.isDark)
        }
    }
}
