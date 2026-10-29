package com.rrajath.grove.ui.screens.settings

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.ThemeMode
import com.rrajath.grove.ui.support.setGroveContent
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 UI coverage for [SettingsAppearanceScreen] (see
 * internal/test-suite-02-ui-compose.md § SettingsAppearanceScreen). The screen
 * is stateless — it takes a [GroveSettings] plus setter lambdas — so it renders
 * directly with recorded callbacks. The text-size lever is a
 * Small/Medium/Large [com.rrajath.grove.ui.components.SegmentedControl], not the
 * slider the (stale) plan text mentions.
 */
@RunWith(AndroidJUnit4::class)
class SettingsAppearanceScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun content(
        settings: GroveSettings = GroveSettings(),
        onSetThemeMode: (ThemeMode) -> Unit = {},
        onSetSyncAppIconWithTheme: (Boolean) -> Unit = {},
        onSetAppFontSize: (FontSizePreference) -> Unit = {},
    ) {
        composeRule.setGroveContent {
            SettingsAppearanceScreen(
                settings = settings,
                onBack = {},
                onSetThemeMode = onSetThemeMode,
                onSetLightTheme = {},
                onSetDarkTheme = {},
                onSetSyncAppIconWithTheme = onSetSyncAppIconWithTheme,
                onSetAppFontSize = onSetAppFontSize,
            )
        }
    }

    @Test
    fun rendersTheThemeAndTextSizeSections() {
        content()
        composeRule.onNodeWithText("Look and Feel").assertIsDisplayed()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Text size").assertIsDisplayed()
        composeRule.onNodeWithText("Small").assertIsDisplayed()
        composeRule.onNodeWithText("Medium").assertIsDisplayed()
        composeRule.onNodeWithText("Large").assertIsDisplayed()
    }

    @Test
    fun pickingATextSizeReportsTheNewStep() {
        var picked: FontSizePreference? = null
        content(
            settings = GroveSettings(appFontSize = FontSizePreference.MEDIUM),
            onSetAppFontSize = { picked = it },
        )

        composeRule.onNodeWithText("Large").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { picked != null }
        assertEquals(FontSizePreference.LARGE, picked)

        composeRule.onNodeWithText("Small").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { picked == FontSizePreference.SMALL }
    }

    // The row only exists on API 26+ (themed launcher icons need <adaptive-icon>),
    // so each of these two tests runs on one side of that line and skips on the other.
    @Test
    fun togglingSyncAppIconReportsTheNewValue() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        var enabled: Boolean? = null
        content(
            settings = GroveSettings(syncAppIconWithTheme = false),
            onSetSyncAppIconWithTheme = { enabled = it },
        )

        composeRule.onNodeWithText("Sync App Icon with Theme").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { enabled != null }
        assertEquals(true, enabled)
    }

    @Test
    fun syncAppIconRowIsHiddenBelowApi26() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
        content()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Sync App Icon with Theme").assertDoesNotExist()
    }
}
