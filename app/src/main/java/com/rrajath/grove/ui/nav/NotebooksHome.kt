package com.rrajath.grove.ui.nav

import androidx.navigation.NavController

/**
 * Drops everything above the Notebooks home in one step (Read mode's breadcrumb
 * "Notebooks" segment).
 *
 * A no-op when Notebooks is already on top. `popBackStack` returns false there
 * too (nothing above it to pop), and treating that as "not on the stack" used to
 * run the fallback below, which pops the whole graph; mid-transition (a second tap
 * on the still-composed, exiting Read screen) that left NavHost drawing a blank
 * screen. The fallback is only for a stack that genuinely lacks Notebooks.
 */
fun NavController.popToNotebooksHome() {
    if (currentDestination?.route == Routes.NOTEBOOKS) return
    if (popBackStack(Routes.NOTEBOOKS, inclusive = false)) return
    navigate(Routes.NOTEBOOKS) { popUpTo(graph.id) { inclusive = true } }
}
