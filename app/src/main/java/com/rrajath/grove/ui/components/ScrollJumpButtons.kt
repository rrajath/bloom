package com.rrajath.grove.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rrajath.grove.R
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A vertically-stacked pair of small round jump buttons (scroll-to-top above
 * scroll-to-bottom) anchored to the bottom-right of the screen. Operates on
 * the caller's own [ScrollState] or [LazyListState]; no new scroll state is
 * created.
 *
 * Visibility behavior:
 * - The pair is hidden entirely when the underlying content has no
 *   scrollable overflow (content fits on screen).
 * - The pair appears once scroll activity moves the position by more than
 *   [minScrollDeltaPx] pixels (from wherever it last settled), and
 *   disappears again after 3 seconds of scroll inactivity. Scrolling again
 *   at any point while visible keeps it up and resets the idle timer. The
 *   threshold exists so that the small scroll nudges caused by the cursor
 *   auto-following typed text (e.g. in an editor) don't repeatedly flash
 *   the buttons; only a deliberate scroll of real distance does.
 * - Each button additionally hides itself once already at that edge (the
 *   top button disappears at the top, the bottom button disappears at the
 *   bottom), even while the pair is otherwise visible.
 *
 * Callers should place this inside a `Box` and align it with
 * `Modifier.align(Alignment.BottomEnd)`, adding extra bottom padding to
 * clear a FAB when the screen has one.
 */
@Composable
fun ScrollJumpButtons(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    minScrollDeltaPx: Float = 0f,
) {
    // Read scroll position through derivedStateOf so this composable only
    // recomposes when one of these booleans actually flips, not on every
    // scrolled pixel (Android Lint: FrequentlyChangingValue).
    val hasOverflow by remember(scrollState) { derivedStateOf { scrollState.maxValue > 0 } }
    val atTop by remember(scrollState) { derivedStateOf { scrollState.value <= 0 } }
    val atBottom by remember(scrollState) { derivedStateOf { scrollState.value >= scrollState.maxValue } }
    val visible = rememberScrollOffsetActivityVisible(scrollState, minScrollDeltaPx)
    val scope = rememberCoroutineScope()

    ScrollJumpButtonsRow(
        visible = visible && hasOverflow,
        showTop = !atTop,
        showBottom = !atBottom,
        onScrollToTop = { scope.launch { scrollState.animateScrollTo(0) } },
        onScrollToBottom = { scope.launch { scrollState.animateScrollTo(scrollState.maxValue) } },
        modifier = modifier,
    )
}

/** [LazyListState] overload; see [ScrollJumpButtons] for behavior details. */
@Composable
fun ScrollJumpButtons(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val hasOverflow = listState.canScrollForward || listState.canScrollBackward
    val visible = rememberScrollActivityVisible(listState)
    val atTop = !listState.canScrollBackward
    val atBottom = !listState.canScrollForward
    val scope = rememberCoroutineScope()

    ScrollJumpButtonsRow(
        visible = visible && hasOverflow,
        showTop = !atTop,
        showBottom = !atBottom,
        onScrollToTop = { scope.launch { listState.animateScrollToItem(0) } },
        onScrollToBottom = {
            scope.launch {
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
            }
        },
        modifier = modifier,
    )
}

/**
 * Tracks whether jump buttons should be visible based on scroll activity: becomes
 * true the moment [listState]'s first-visible position changes (i.e. the user
 * scrolled), then flips back to false after 3 seconds without further changes.
 * Does not show on first composition; only once actual scroll movement is
 * observed. The position is watched via [snapshotFlow] inside the effect rather
 * than read in composition, so a scroll doesn't recompose the caller every frame
 * (Android Lint: FrequentlyChangingValue).
 */
@Composable
private fun rememberScrollActivityVisible(listState: LazyListState): Boolean {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        var previous: Pair<Int, Int>? = null
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { positionKey ->
                val prior = previous
                if (prior != null && prior != positionKey) {
                    visible = true
                }
                previous = positionKey
                delay(3000)
                visible = false
            }
    }

    return visible
}

/**
 * Like [rememberScrollActivityVisible], but only flips to visible once
 * [scrollState]'s offset has drifted more than [minDeltaPx] away from wherever
 * it last settled: small back-and-forth jitter (e.g. the cursor-follow scroll
 * while typing) never crosses that threshold, so it doesn't flash the buttons.
 * Once visible, any further movement keeps it up and resets the idle timer,
 * same as the plain scroll-activity tracker; the baseline resets to the
 * current offset each time it goes idle again. The offset is watched via
 * [snapshotFlow] inside the effect rather than read in composition, so a scroll
 * doesn't recompose the caller every frame (Android Lint: FrequentlyChangingValue).
 */
@Composable
private fun rememberScrollOffsetActivityVisible(scrollState: ScrollState, minDeltaPx: Float): Boolean {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(scrollState, minDeltaPx) {
        var baseline: Int? = null
        snapshotFlow { scrollState.value }.collectLatest { offsetPx ->
            val base = baseline
            if (base == null) {
                baseline = offsetPx
            } else if (!visible && kotlin.math.abs(offsetPx - base) > minDeltaPx) {
                visible = true
            }
            if (visible) {
                delay(3000)
                visible = false
                baseline = offsetPx
            }
        }
    }

    return visible
}

@Composable
private fun ScrollJumpButtonsRow(
    visible: Boolean,
    showTop: Boolean,
    showBottom: Boolean,
    onScrollToTop: () -> Unit,
    onScrollToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showTop) {
            ScrollJumpButton(
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.scroll_to_top),
                onClick = onScrollToTop,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (showBottom) {
            ScrollJumpButton(
                icon = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.scroll_to_bottom),
                onClick = onScrollToBottom,
            )
        }
    }
}

@Composable
private fun ScrollJumpButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .size(40.dp)
            .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(c.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = c.ink2)
    }
}
