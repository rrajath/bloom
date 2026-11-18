package com.rrajath.grove.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.rrajath.grove.testing.TestGroveApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen leaving through the app's 450ms transition must not take taps: its Back
 * used to pop the screen underneath, and with only the start screen left that
 * emptied the back stack (blank NavHost).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, qualifiers = "w384dp-h832dp")
class TransitionInputGateRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var nav: NavHostController

    private fun stack(): List<String?> =
        nav.currentBackStack.value.mapNotNull { it.destination.route }

    private fun host() {
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(
                nav,
                startDestination = "home",
                enterTransition = navEnterTransition,
                exitTransition = navExitTransition,
                popEnterTransition = navPopEnterTransition,
                popExitTransition = navPopExitTransition,
            ) {
                screen("home") { Box(Modifier.fillMaxSize()) { Text("home") } }
                screen("detail") {
                    Box(Modifier.fillMaxSize()) {
                        // Covers the whole screen so the tap lands on it however far the
                        // exit animation has scaled the leaving screen.
                        Text("back", Modifier.fillMaxSize().testTag("detail_back").clickable { nav.popBackStack() })
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a tap on a screen mid-exit is ignored and the stack stays intact`() {
        host()
        composeRule.runOnIdle { nav.navigate("detail") }
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("detail_back").performClick() // first Back: starts the exit
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.onNodeWithTag("detail_back").performClick() // second tap, mid-transition
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(listOf("home"), stack())
    }

    @Test
    fun `lifecycle steps recompose the overlay, not the screen content`() {
        // LifecycleRegistry holds its owner weakly; keep a strong reference for the test.
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val lifecycle = owner.registry
        lifecycle.currentState = Lifecycle.State.CREATED
        var contentCompositions = 0
        composeRule.setContent {
            TransitionInputGate(lifecycle) {
                SideEffect { contentCompositions++ }
                Text("content")
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, contentCompositions)

        for (step in listOf(Lifecycle.State.STARTED, Lifecycle.State.RESUMED, Lifecycle.State.STARTED, Lifecycle.State.CREATED)) {
            composeRule.runOnIdle { lifecycle.currentState = step }
            composeRule.waitForIdle()
        }

        assertEquals(1, contentCompositions)
    }

    @Test
    fun `the settled screen still takes taps`() {
        host()
        composeRule.runOnIdle { nav.navigate("detail") }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("detail_back").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("home"), stack())
    }
}
