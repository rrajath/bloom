package com.rrajath.grove.ui.screens.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import com.rrajath.grove.ui.components.playRevealHighlight
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** The `highlight` route argument of the settings page being shown: a [SettingSpec.id] or null. */
val LocalSettingsHighlight = staticCompositionLocalOf<String?> { null }

private val LocalSettingsReveal = staticCompositionLocalOf<SettingsRevealState?> { null }

/** Where the target row (and its fallback) sit, and which one is washed right now. */
@Stable
internal class SettingsRevealState(val targetId: String?, val fallbackId: String?) {
    /** The scrolled column's content, so a row's offset is measured independently of scroll. */
    var content: LayoutCoordinates? = null
    var viewportHeight: Int = 0
    val anchors = mutableStateMapOf<String, LayoutCoordinates>()
    var activeId by mutableStateOf<String?>(null)
    val highlight = Animatable(0f)

    fun wants(id: String) = id == targetId || id == fallbackId

    fun report(id: String, coords: LayoutCoordinates) {
        if (wants(id) && anchors[id] !== coords) anchors[id] = coords
    }
}

/** The reveal for the page being shown, keyed on its `highlight` argument. */
@Composable
internal fun rememberSettingsReveal(): SettingsRevealState {
    val highlightId = LocalSettingsHighlight.current
    val target = remember(highlightId) { highlightId?.let(SettingSpecs::byId) }
    return remember(target) { SettingsRevealState(target?.id, target?.revealFallback) }
}

/**
 * Scrolls the page to the row named by the `highlight` argument and fades an
 * `accentSoft` wash off it, once per back-stack entry (returning from a sub-screen,
 * such as the sync log, doesn't replay it). A row hidden behind another setting
 * reveals its [SettingSpec.revealFallback]; a row not found within a second is skipped.
 */
@Composable
internal fun SettingsRevealEffect(reveal: SettingsRevealState, scrollState: ScrollState) {
    var revealDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reveal) {
        val targetId = reveal.targetId ?: return@LaunchedEffect
        if (revealDone) return@LaunchedEffect
        revealDone = true
        withTimeoutOrNull(1_000) { snapshotFlow { reveal.anchors.keys.firstOrNull() }.filterNotNull().first() }
            ?: return@LaunchedEffect
        // Both candidates report in the same layout pass; give it a frame to settle.
        withFrameNanos { }
        val id = if (reveal.anchors.containsKey(targetId)) targetId else reveal.fallbackId ?: return@LaunchedEffect
        val anchor = reveal.anchors[id]?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val content = reveal.content?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val y = content.localPositionOf(anchor, Offset.Zero).y
        // Land the row about a quarter of the way down, so the rows above it give context.
        val to = (y - reveal.viewportHeight / 4f).roundToInt().coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(to)
        reveal.activeId = id
        reveal.highlight.playRevealHighlight()
        reveal.activeId = null
    }
}

@Composable
internal fun ProvideSettingsReveal(reveal: SettingsRevealState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsReveal provides reveal, content = content)
}

/**
 * Tags a settings row with its [spec] (Settings search's reveal target and the
 * spec-coverage test's anchor). Apply it first in the row's modifier chain so the wash
 * covers the row's whole padded area. Rows sit inside a `SettingsGroup` card, so the
 * wash fades into `surface`.
 */
fun Modifier.settingAnchor(spec: SettingSpec): Modifier = composed {
    val reveal = LocalSettingsReveal.current
    val tagged = testTag(spec.testTag)
    if (reveal == null || !reveal.wants(spec.id)) return@composed tagged
    val c = MaterialTheme.grove
    tagged
        .onGloballyPositioned { reveal.report(spec.id, it) }
        .drawBehind {
            if (reveal.activeId == spec.id) {
                val h = reveal.highlight.value
                if (h > 0f) drawRect(lerp(c.surface, c.accentSoft, h))
            }
        }
}
