package com.rrajath.grove.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.ui.vault.unsupportedReason
import com.rrajath.grove.vault.storage.ProviderProfiles
import com.rrajath.grove.vault.storage.StorageOp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Decision 2: an action the provider rules out stays in the menu, disabled, with the reason under it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestGroveApplication::class)
class ProviderGatedMenuItemTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aBlockedActionIsDisabledWithItsReason() {
        val context = ApplicationProvider.getApplicationContext<TestGroveApplication>()
        val reason = unsupportedReason(context, StorageOp.Rename, ProviderProfiles.SEAFILE)
        assertEquals("Seafile doesn't let other apps rename files.", reason)
        var clicks = 0
        composeRule.setContent {
            GroveTheme {
                GroveDropdownMenu(expanded = true, onDismissRequest = {}) {
                    ProviderGatedMenuItem("Rename", reason, onClick = { clicks++ })
                    ProviderGatedMenuItem("Delete", null, onClick = { clicks++ }, destructive = true)
                }
            }
        }

        composeRule.onNode(hasText("Rename") and hasClickAction(), useUnmergedTree = false).assertIsNotEnabled()
        composeRule.onNodeWithText(reason, useUnmergedTree = true).assertExists()
        composeRule.onNode(hasText("Delete") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }
}
