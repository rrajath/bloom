package com.rrajath.grove.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.ui.support.ScreenTestEnv
import com.rrajath.grove.ui.support.setGroveContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 UI coverage for [NotebooksScreen].
 */
@RunWith(AndroidJUnit4::class)
class NotebooksScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var env: ScreenTestEnv
    private val vm by lazy { env.notebooksViewModel() }

    @Before
    fun setUp() {
        env = ScreenTestEnv()
    }

    @After
    fun tearDown() {
        env.close()
    }

    private fun content(
        onOpenNotebook: (String) -> Unit = {},
        onOpenSearch: () -> Unit = {},
        onOpenCapture: () -> Unit = {},
    ) {
        composeRule.setGroveContent {
            NotebooksScreen(
                onOpenDrawer = {},
                onOpenSearch = onOpenSearch,
                onOpenCapture = onOpenCapture,
                onOpenNotebook = onOpenNotebook,
                onOpenConflict = {},
                viewModel = vm,
            )
        }
    }

    @Test
    fun populatedVaultRendersOneRowPerNotebook() {
        content()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size ==
                OrgFixtures.all.size
        }
    }

    @Test
    fun tappingARowOpensThatNotebook() {
        var opened: String? = null
        content(onOpenNotebook = { opened = it })

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("notebook_row")[0].performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) { opened != null }
        assertNotNull(opened)
        assertEquals(true, opened!!.endsWith(".org"))
    }

    @Test
    fun theDockAffordancesFireTheirCallbacks() {
        var captureOpened = false
        var searchOpened = false
        content(onOpenCapture = { captureOpened = true }, onOpenSearch = { searchOpened = true })

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("notebooks_capture").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("notebooks_capture").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { captureOpened }

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { searchOpened }
    }

    @Test
    fun noVaultShowsTheEmptyState() {
        env.vaultFlow.value = null
        content()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("notebooks_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("notebooks_empty_state").assertIsDisplayed()
    }
}
