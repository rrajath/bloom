package com.rrajath.grove.ui.nav

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * [composable] whose content ignores touches unless its back-stack entry is RESUMED
 * (see [TransitionInputGate]). Every route in the app's NavHost is declared with this.
 */
fun NavGraphBuilder.screen(
    route: String,
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, deepLinks = deepLinks) { entry ->
        val scope = this
        TransitionInputGate(entry.lifecycle) { scope.content(entry) }
    }
}

/**
 * Swallows every touch on [content] while [lifecycle] is below RESUMED.
 *
 * NavHost keeps a screen composed, drawn and touchable for its whole 450ms exit
 * animation, and the Back animation shrinks it toward the center, so its top-left
 * Back arrow and breadcrumb drift under a finger that just tapped there. A quick
 * second tap then ran the leaving screen's handlers: its plain popBackStack()
 * popped the screen underneath, and with only Notebooks left that emptied the back
 * stack and NavHost drew nothing (a blank white screen until the app was killed).
 *
 * Navigation keeps an entry below RESUMED while it animates in or out and resumes
 * it once the transition settles, so this blocks input on both screens for the
 * length of a transition and never otherwise. Rendering and accessibility are
 * untouched; only pointer input is intercepted, by an overlay above the content.
 */
@Composable
internal fun TransitionInputGate(lifecycle: Lifecycle, content: @Composable () -> Unit) {
    val state by lifecycle.currentStateFlow.collectAsState()
    Box(propagateMinConstraints = true) {
        content()
        if (!state.isAtLeast(Lifecycle.State.RESUMED)) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
        }
    }
}
