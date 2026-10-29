package com.rrajath.grove.ui.theme

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.rrajath.grove.settings.ThemePreference
import com.rrajath.grove.settings.ThemeSelection

/**
 * Android 10 (API 29) added the system-wide dark theme. Below it the System mode
 * is hidden and a stored SYSTEM behaves as Light ([ThemeSelection.effectiveMode]).
 */
val systemDarkThemeSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

fun Configuration.isNightMode(): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Theme on screen right now, for code outside composition (receivers, app-scope flows). */
fun ThemeSelection.resolveFor(configuration: Configuration): ThemePreference =
    resolve(configuration.isNightMode(), systemDarkThemeSupported)

/** Theme on screen right now; recomposes when the phone switches light/dark. */
@Composable
fun ThemeSelection.resolveForSystem(): ThemePreference =
    resolve(isSystemInDarkTheme(), systemDarkThemeSupported)
