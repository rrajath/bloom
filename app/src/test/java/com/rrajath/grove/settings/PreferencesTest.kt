package com.rrajath.grove.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesTest {

    @Test
    fun `theme preference round-trips through storage key`() {
        for (pref in ThemePreference.entries) {
            assertEquals(pref, ThemePreference.fromStorage(pref.storageKey))
        }
    }

    @Test
    fun `unknown or missing theme falls back to light`() {
        assertEquals(ThemePreference.LIGHT, ThemePreference.fromStorage(null))
        assertEquals(ThemePreference.LIGHT, ThemePreference.fromStorage("sepia"))
    }

    @Test
    fun `unset theme follows the phone with the Bloom pair for fresh installs and stays Grove Light for onboarded ones`() {
        assertEquals(
            ThemeSelection(ThemeMode.SYSTEM, ThemePreference.BLOOMLIGHT, ThemePreference.BLOOMDARK),
            ThemeSelection.defaultFor(onboardingDone = false),
        )
        assertEquals(
            ThemeSelection(ThemeMode.LIGHT, ThemePreference.LIGHT, ThemePreference.BLOOMDARK),
            ThemeSelection.defaultFor(onboardingDone = true),
        )
    }

    @Test
    fun `resolve picks the slot for the mode and follows the phone only in System`() {
        val s = ThemeSelection(light = ThemePreference.SAGE, dark = ThemePreference.MOSS)
        for (night in listOf(false, true)) {
            assertEquals(ThemePreference.SAGE, s.copy(mode = ThemeMode.LIGHT).resolve(night, systemSupported = true))
            assertEquals(ThemePreference.MOSS, s.copy(mode = ThemeMode.DARK).resolve(night, systemSupported = true))
        }
        assertEquals(ThemePreference.SAGE, s.copy(mode = ThemeMode.SYSTEM).resolve(systemIsDark = false, systemSupported = true))
        assertEquals(ThemePreference.MOSS, s.copy(mode = ThemeMode.SYSTEM).resolve(systemIsDark = true, systemSupported = true))
    }

    @Test
    fun `System behaves as Light where the OS has no dark theme`() {
        val s = ThemeSelection(ThemeMode.SYSTEM, ThemePreference.SAGE, ThemePreference.MOSS)
        assertEquals(ThemeMode.LIGHT, s.effectiveMode(systemSupported = false))
        assertEquals(ThemeMode.SYSTEM, s.effectiveMode(systemSupported = true))
        assertEquals(ThemePreference.SAGE, s.resolve(systemIsDark = true, systemSupported = false))
        assertEquals(ThemeMode.DARK, s.copy(mode = ThemeMode.DARK).effectiveMode(systemSupported = false))
    }

    @Test
    fun `a legacy single theme keeps its look`() {
        assertEquals(
            ThemeSelection(ThemeMode.DARK, ThemePreference.BLOOMLIGHT, ThemePreference.TOKYONIGHT),
            ThemeSelection.fromLegacy(ThemePreference.TOKYONIGHT),
        )
        assertEquals(
            ThemeSelection(ThemeMode.LIGHT, ThemePreference.CHALK, ThemePreference.BLOOMDARK),
            ThemeSelection.fromLegacy(ThemePreference.CHALK),
        )
    }

    @Test
    fun `stored keys migrate a legacy theme and win over it once written`() {
        fun read(mode: String?, legacy: String?, onboarded: Boolean) =
            ThemeSelection.fromStorage(mode, "chalk", "moss", legacyTheme = legacy, onboardingDone = onboarded)
        // Mode keys present: the legacy key is ignored.
        assertEquals(ThemeSelection(ThemeMode.SYSTEM, ThemePreference.CHALK, ThemePreference.MOSS), read("system", "tokyonight", true))
        // Upgrade from a single theme: same look, Bloom default in the other slot.
        assertEquals(ThemeSelection.fromLegacy(ThemePreference.TOKYONIGHT), read(null, "tokyonight", true))
        // Nothing stored.
        assertEquals(ThemeSelection.defaultFor(onboardingDone = true), read(null, null, true))
        assertEquals(ThemeSelection.defaultFor(onboardingDone = false), read(null, null, false))
    }

    @Test
    fun `stored selection parses, falls back per slot, and rejects wrong-family themes`() {
        assertEquals(null, ThemeSelection.fromStorage(null, "sage", "moss"))
        assertEquals(
            ThemeSelection(ThemeMode.SYSTEM, ThemePreference.SAGE, ThemePreference.MOSS),
            ThemeSelection.fromStorage("system", "sage", "moss"),
        )
        assertEquals(
            ThemeSelection(ThemeMode.DARK, ThemePreference.BLOOMLIGHT, ThemePreference.BLOOMDARK),
            ThemeSelection.fromStorage("dark", "sepia", null),
        )
        // A dark theme in the light slot (and vice versa) is replaced, not kept.
        assertEquals(
            ThemeSelection(ThemeMode.LIGHT, ThemePreference.BLOOMLIGHT, ThemePreference.BLOOMDARK),
            ThemeSelection.fromStorage("light", "moss", "sage"),
        )
    }

    @Test
    fun `font size round-trips and defaults to medium`() {
        for (pref in FontSizePreference.entries) {
            assertEquals(pref, FontSizePreference.fromStorage(pref.storageKey))
        }
        assertEquals(FontSizePreference.MEDIUM, FontSizePreference.fromStorage(null))
        assertEquals(FontSizePreference.MEDIUM, FontSizePreference.fromStorage("huge"))
    }

    @Test
    fun `font size scales are ordered`() {
        assert(FontSizePreference.SMALL.scale < FontSizePreference.MEDIUM.scale)
        assert(FontSizePreference.MEDIUM.scale < FontSizePreference.LARGE.scale)
    }

    @Test
    fun `notebook sort key round-trips and defaults to alphabetical`() {
        for (pref in NotebookSortKey.entries) {
            assertEquals(pref, NotebookSortKey.fromStorage(pref.storageKey))
        }
        assertEquals(NotebookSortKey.ALPHABETICAL, NotebookSortKey.fromStorage(null))
        assertEquals(NotebookSortKey.ALPHABETICAL, NotebookSortKey.fromStorage("size"))
    }

    @Test
    fun `note open mode round-trips and defaults to read`() {
        for (pref in NoteOpenMode.entries) {
            assertEquals(pref, NoteOpenMode.fromStorage(pref.storageKey))
        }
        assertEquals(NoteOpenMode.READ, NoteOpenMode.fromStorage(null))
    }

    @Test
    fun `new note cursor round-trips and defaults to body`() {
        for (pref in NewNoteCursor.entries) {
            assertEquals(pref, NewNoteCursor.fromStorage(pref.storageKey))
        }
        assertEquals(NewNoteCursor.BODY, NewNoteCursor.fromStorage(null))
        assertEquals(NewNoteCursor.BODY, NewNoteCursor.fromStorage("footer"))
    }
}
