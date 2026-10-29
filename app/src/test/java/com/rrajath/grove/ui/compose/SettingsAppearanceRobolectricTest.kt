package com.rrajath.grove.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.ThemeMode
import com.rrajath.grove.settings.ThemePreference
import com.rrajath.grove.settings.ThemeSelection
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.screens.settings.SettingsAppearanceScreen
import com.rrajath.grove.ui.theme.GroveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layer-2 smoke slice that runs on the JVM per-push job instead of only the
 * nightly emulator (see internal/test-suite-02-ui-compose.md § "Robolectric
 * subset"). Robolectric + `@GraphicsMode.NATIVE` renders real Compose frames
 * off-device; it covers only the stateless pure-composable screens — anything
 * needing a live vault / ViewModel / SAF launcher stays in `androidTest/`.
 *
 * If this class starts flaking under Robolectric, delete it: the same
 * assertions run for real in `SettingsAppearanceScreenTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class, qualifiers = "w411dp-h891dp")
class SettingsAppearanceRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun content(
        settings: GroveSettings = GroveSettings(),
        onSetAppFontSize: (FontSizePreference) -> Unit = {},
        onSetThemeMode: (ThemeMode) -> Unit = {},
        onSetLightTheme: (ThemePreference) -> Unit = {},
    ) {
        composeRule.setContent {
            GroveTheme {
                SettingsAppearanceScreen(
                    settings = settings,
                    onBack = {},
                    onSetThemeMode = onSetThemeMode,
                    onSetLightTheme = onSetLightTheme,
                    onSetDarkTheme = {},
                    onSetSyncAppIconWithTheme = {},
                    onSetAppFontSize = onSetAppFontSize,
                )
            }
        }
    }

    @Test
    fun rendersTheTextSizeLever() {
        content()
        composeRule.onNodeWithText("Look and Feel").assertExists()
        composeRule.onNodeWithText("Text size").assertExists()
        composeRule.onNodeWithText("Large").assertExists()
    }

    @Test
    fun pickingATextSizeReportsTheNewStep() {
        var picked: FontSizePreference? = null
        content(
            settings = GroveSettings(appFontSize = FontSizePreference.MEDIUM),
            onSetAppFontSize = { picked = it },
        )
        composeRule.onNodeWithText("Large").performClick()
        composeRule.waitForIdle()
        assertEquals(FontSizePreference.LARGE, picked)
    }

    @Test
    fun systemModeShowsBothThemesAndMarksTheOneInUse() {
        content()
        composeRule.onNodeWithText("System").assertExists()
        composeRule.onNodeWithText("Bloom Light").assertExists()
        composeRule.onNodeWithText("Bloom Dark").assertExists()
        // The test runs in light mode, so only the light tile is in use.
        composeRule.onAllNodesWithText("In use").assertCountEquals(1)
    }

    @Test
    fun lightModeListsOnlyLightThemes() {
        var picked: ThemePreference? = null
        content(
            settings = GroveSettings(themeSelection = ThemeSelection(mode = ThemeMode.LIGHT)),
            onSetLightTheme = { picked = it },
        )
        composeRule.onNodeWithText("In use").assertDoesNotExist()
        composeRule.onNodeWithText("Bloom Light").performClick()
        composeRule.onNodeWithText("Chalk").assertExists()
        composeRule.onNodeWithText("Moss").assertDoesNotExist()
        composeRule.onNodeWithText("Chalk").performClick()
        composeRule.waitForIdle()
        assertEquals(ThemePreference.CHALK, picked)
    }

    @Test
    fun pickingAModeReportsIt() {
        var picked: ThemeMode? = null
        content(
            settings = GroveSettings(themeSelection = ThemeSelection(mode = ThemeMode.LIGHT)),
            onSetThemeMode = { picked = it },
        )
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.waitForIdle()
        assertEquals(ThemeMode.DARK, picked)
    }

    @Test
    @Config(sdk = [28])
    fun systemModeIsHiddenBelowApi29AndAStoredSystemShowsAsLight() {
        content()
        composeRule.onNodeWithText("System").assertDoesNotExist()
        composeRule.onNodeWithText("In use").assertDoesNotExist()
        composeRule.onNodeWithText("Bloom Light").assertExists()
        composeRule.onNodeWithText("Bloom Dark").assertDoesNotExist()
    }
}
