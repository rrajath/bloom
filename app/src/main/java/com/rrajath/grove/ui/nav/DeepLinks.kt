package com.rrajath.grove.ui.nav

import androidx.navigation.NavDeepLink
import androidx.navigation.navDeepLink

/**
 * The app's own deep links (widgets, notifications, launcher shortcuts, a
 * template's capture link). New links use [SCHEME]; [LEGACY_SCHEME] is the
 * pre-rename `grove://` and stays accepted for good, so pinned shortcuts and
 * automations made before the rename keep working.
 *
 * Every deep-link path equals its [Routes] pattern. Build URIs with the helpers
 * here rather than spelling out a scheme.
 */
object DeepLinks {
    const val SCHEME = "bloom"
    const val LEGACY_SCHEME = "grove"
    val SCHEMES = listOf(SCHEME, LEGACY_SCHEME)

    fun isAppScheme(scheme: String?): Boolean = scheme == SCHEME || scheme == LEGACY_SCHEME

    /** One [NavDeepLink] per accepted scheme for a NavHost destination's [routePattern]. */
    fun navDeepLinks(routePattern: String): List<NavDeepLink> =
        SCHEMES.map { scheme -> navDeepLink { uriPattern = "$scheme://$routePattern" } }

    fun capture(): String = "$SCHEME://${Routes.capture()}"

    /** [templateId] is a template id or a name slug (`templateSlug`). */
    fun captureTemplate(templateId: String): String = "$SCHEME://${Routes.capture(templateId)}"

    fun agenda(): String = "$SCHEME://${Routes.AGENDA}"

    /** The Search screen with [query] already run (the Agenda widget's saved-search view). */
    fun search(query: String): String = "$SCHEME://${Routes.search(query)}"

    /** [encodedNoteId] is already [Routes.encode]d. */
    fun note(encodedNoteId: String, mode: String, isNew: Boolean): String =
        "$SCHEME://note/$encodedNoteId?mode=$mode&isNew=$isNew"

    fun reminder(fileName: String, headingPath: String, level: Int): String =
        "$SCHEME://reminder/${Routes.encode(fileName)}" +
            "?headingPath=${Routes.encode(headingPath)}&level=$level"
}
