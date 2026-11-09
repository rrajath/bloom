package com.rrajath.grove.ui.nav

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rrajath.grove.testing.TestGroveApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class)
class NotebooksHomeRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var nav: NavHostController

    private fun host(start: String = Routes.NOTEBOOKS) {
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = start) {
                composable(Routes.NOTEBOOKS) { Text("home") }
                composable("a") { Text("a") }
                composable("b") { Text("b") }
            }
        }
        composeRule.waitForIdle()
    }

    /** Destination routes on the back stack, bottom first, without the graph entry. */
    private fun stack(): List<String?> =
        nav.currentBackStack.value.map { it.destination.route }.filter { it != null }

    @Test
    fun `pops everything above Notebooks`() {
        host()
        composeRule.runOnIdle { nav.navigate("a"); nav.navigate("b") }
        composeRule.runOnIdle { nav.popToNotebooksHome() }
        composeRule.waitForIdle()

        assertEquals(listOf(Routes.NOTEBOOKS), stack())
    }

    @Test
    fun `a second call while already home keeps the same Notebooks entry`() {
        host()
        composeRule.runOnIdle { nav.navigate("a") }
        composeRule.runOnIdle { nav.popToNotebooksHome() }
        composeRule.waitForIdle()
        val home = nav.currentBackStackEntry

        composeRule.runOnIdle { nav.popToNotebooksHome() }
        composeRule.waitForIdle()

        assertEquals(listOf(Routes.NOTEBOOKS), stack())
        // Same entry: the graph wasn't popped and rebuilt underneath it.
        assertEquals(home, nav.currentBackStackEntry)
    }

    @Test
    fun `starts a fresh stack at Notebooks when it isn't on the stack`() {
        host(start = "a")
        composeRule.runOnIdle { nav.navigate("b") }
        composeRule.runOnIdle { nav.popToNotebooksHome() }
        composeRule.waitForIdle()

        assertEquals(listOf(Routes.NOTEBOOKS), stack())
    }
}
