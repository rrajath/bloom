package com.rrajath.grove.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One action cell behind a swipeable row (design spec Gestures screen). Glyph is a plain
 * text character (e.g. "◷"); pass [icon] instead when the action needs to match a Material
 * icon used elsewhere in the app (e.g. the "mark done" checkmark); [icon] takes precedence.
 */
data class SwipeAction(
    val glyph: String? = null,
    val label: String,
    val fg: Color,
    val bg: Color,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

// Design-spec swipe physics: 46dp cells (a panel is as wide as its cells: 4 or 5),
// 66dp open threshold, rubber band past the panel width, 340ms settle with the
// prototype's cubic-bezier ease.
private val CellWidth = 46.dp
private val OpenThreshold = 66.dp
private const val RubberBandFactor = 0.18f
private const val SettleMillis = 340
private val SettleEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * A row that swipes right to reveal [leftActions] (anchored at the left edge)
 * or left to reveal [rightActions]. Tapping the open card closes it; tapping a
 * cell closes and fires. The parent keeps at most one row open by flipping
 * [forceClose] from [onOpenChanged] callbacks.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeRevealRow(
    leftActions: List<SwipeAction>,
    rightActions: List<SwipeAction>,
    enabled: Boolean,
    forceClose: Boolean,
    onOpenChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** Clip applied to the foreground card and the action panels (e.g. rounded row ends). */
    shape: Shape = RectangleShape,
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.grove
    val density = LocalDensity.current
    // Each side opens exactly as wide as its own cells.
    val leftPanelPx = with(density) { (CellWidth * leftActions.size).toPx() }
    val rightPanelPx = with(density) { (CellWidth * rightActions.size).toPx() }
    val thresholdPx = with(density) { OpenThreshold.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Raw finger travel this gesture; the visible offset rubber-bands past the panel.
    val dragRaw = remember { mutableFloatStateOf(0f) }
    // derivedStateOf so reading these booleans in composition only invalidates on a sign/open
    // flip, not on every drag-delta/settle-frame write to offset.value.
    val isOpen by remember { derivedStateOf { offset.value != 0f } }
    val showLeftPanel by remember { derivedStateOf { offset.value > 0f } }
    val showRightPanel by remember { derivedStateOf { offset.value < 0f } }

    fun rubberBand(x: Float): Float = when {
        x > leftPanelPx -> leftPanelPx + (x - leftPanelPx) * RubberBandFactor
        x < -rightPanelPx -> -rightPanelPx + (x + rightPanelPx) * RubberBandFactor
        else -> x
    }

    fun animateTo(target: Float) {
        scope.launch {
            if (target != 0f) onOpenChanged(true)
            offset.animateTo(target, tween(SettleMillis, easing = SettleEasing))
            if (target == 0f) onOpenChanged(false)
        }
    }

    fun settle() = animateTo(
        when {
            offset.value >= thresholdPx -> leftPanelPx
            offset.value <= -thresholdPx -> -rightPanelPx
            else -> 0f
        }
    )

    fun close() = animateTo(0f)

    LaunchedEffect(forceClose) {
        if (forceClose && offset.value != 0f) {
            offset.animateTo(0f, tween(SettleMillis, easing = SettleEasing))
        }
    }

    Box(modifier.clipToBounds()) {
        if (showLeftPanel) {
            ActionPanel(leftActions, anchorEnd = false, onAction = ::close, shape = shape)
        }
        if (showRightPanel) {
            ActionPanel(rightActions, anchorEnd = true, onAction = ::close, shape = shape)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .clip(shape)
                .background(c.bg)
                .combinedClickable(
                    onClick = { if (isOpen) close() else onTap() },
                    onLongClick = if (!isOpen && enabled) onLongPress else null,
                )
                .draggable(
                    state = rememberDraggableState { delta ->
                        dragRaw.floatValue += delta
                        scope.launch { offset.snapTo(rubberBand(dragRaw.floatValue)) }
                    },
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStarted = { dragRaw.floatValue = offset.value },
                    onDragStopped = { settle() },
                ),
        ) {
            content()
        }
    }
}

/**
 * A row that swipes left or right to commit a configured primary action.
 * Plain (no secondary): fires immediately on release past the open threshold,
 * unlike [SwipeRevealRow]'s reveal-then-tap panel, and always springs back to
 * its resting position whether or not it fired (Gmail/Reminders-style "swipe
 * to complete"). If a side is also given a [leftSecondaryAction] /
 * [rightSecondaryAction] (e.g. Agenda's Done + Add-note), that side instead
 * behaves like [SwipeRevealRow] at a partial drag: past the open threshold it
 * settles open showing both cells (primary nearest the edge, so it's revealed
 * first) for a tap, and the parent keeps at most one row open the same way,
 * via [forceClose]/[onOpenChanged]. Dragging further, past [CommitThreshold],
 * commits the primary action immediately without needing a second tap; on
 * the way there the secondary cell fades out and the whole side floods with
 * the primary's colour ([DualCommitPanel]). A null action on a side disables
 * dragging that direction.
 */
@Composable
fun SwipeCommitRow(
    leftAction: SwipeAction?,
    rightAction: SwipeAction?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    leftSecondaryAction: SwipeAction? = null,
    rightSecondaryAction: SwipeAction? = null,
    forceClose: Boolean = false,
    onOpenChanged: (Boolean) -> Unit = {},
    /** Clip applied to the foreground card (e.g. a rounded ripple boundary). */
    shape: Shape = RectangleShape,
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.grove
    val density = LocalDensity.current
    val thresholdPx = with(density) { OpenThreshold.toPx() }
    val singleCapPx = with(density) { CommitCap.toPx() }
    val dualOpenPx = with(density) { DualPanelWidth.toPx() }
    val dualCommitPx = with(density) { CommitThreshold.toPx() }
    val dualCapPx = with(density) { DualCommitCap.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dragRaw = remember { mutableFloatStateOf(0f) }
    val isOpen by remember { derivedStateOf { offset.value != 0f } }
    val showLeftPanel by remember { derivedStateOf { offset.value > 0f && leftAction != null } }
    val showRightPanel by remember { derivedStateOf { offset.value < 0f && rightAction != null } }

    fun capFor(x: Float): Float = when {
        x > 0f && leftSecondaryAction != null -> dualCapPx
        x < 0f && rightSecondaryAction != null -> dualCapPx
        else -> singleCapPx
    }

    fun rubberBand(x: Float): Float {
        val cap = capFor(x)
        return when {
            x > cap -> cap + (x - cap) * RubberBandFactor
            x < -cap -> -cap + (x + cap) * RubberBandFactor
            else -> x
        }
    }

    fun animateTo(target: Float) {
        scope.launch {
            if (target != 0f) onOpenChanged(true)
            offset.animateTo(target, tween(SettleMillis, easing = SettleEasing))
            if (target == 0f) onOpenChanged(false)
        }
    }

    fun close() = animateTo(0f)

    LaunchedEffect(forceClose) {
        if (forceClose && offset.value != 0f) {
            offset.animateTo(0f, tween(SettleMillis, easing = SettleEasing))
        }
    }

    fun release() {
        val v = offset.value
        when {
            v >= dualCommitPx && leftSecondaryAction != null -> {
                close()
                leftAction?.onClick?.invoke()
            }
            v <= -dualCommitPx && rightSecondaryAction != null -> {
                close()
                rightAction?.onClick?.invoke()
            }
            v >= thresholdPx && leftSecondaryAction != null -> animateTo(dualOpenPx)
            v <= -thresholdPx && rightSecondaryAction != null -> animateTo(-dualOpenPx)
            v >= thresholdPx -> {
                close()
                leftAction?.onClick?.invoke()
            }
            v <= -thresholdPx -> {
                close()
                rightAction?.onClick?.invoke()
            }
            else -> close()
        }
    }

    Box(modifier.clipToBounds()) {
        if (showLeftPanel && leftAction != null) {
            if (leftSecondaryAction != null) {
                DualCommitPanel(leftAction, leftSecondaryAction, anchorEnd = false, shape = shape, offset = { offset.value }, onAction = ::close)
            } else {
                CommitUnderlay(leftAction, anchorEnd = false, shape = shape)
            }
        }
        if (showRightPanel && rightAction != null) {
            if (rightSecondaryAction != null) {
                DualCommitPanel(rightAction, rightSecondaryAction, anchorEnd = true, shape = shape, offset = { offset.value }, onAction = ::close)
            } else {
                CommitUnderlay(rightAction, anchorEnd = true, shape = shape)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .clip(shape)
                .background(c.bg)
                .clickable(onClick = { if (isOpen) close() else onTap() })
                .draggable(
                    state = rememberDraggableState { delta ->
                        val next = dragRaw.floatValue + delta
                        val clamped = when {
                            next > 0f && leftAction == null -> 0f
                            next < 0f && rightAction == null -> 0f
                            else -> next
                        }
                        dragRaw.floatValue = clamped
                        scope.launch { offset.snapTo(rubberBand(clamped)) }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { dragRaw.floatValue = offset.value },
                    onDragStopped = { release() },
                ),
        ) {
            content()
        }
    }
}

// Cap for [SwipeCommitRow]'s single-action rubber-band: shorter than
// SwipeRevealRow's full panel width since there's no persistent open state to
// travel toward, just enough drag room past the threshold to feel deliberate.
private val CommitCap = 96.dp

// Physics for a [SwipeCommitRow] side that also has a secondary action: the
// panel settles open at two cells' width, and a drag past CommitThreshold
// (far beyond that open width, so a long swipe is a deliberate gesture)
// commits the primary action without a second tap.
private val DualPanelWidth = CellWidth * 2
private val CommitThreshold = 300.dp
private val DualCommitCap = 320.dp

// How strong the primary's flood gets by the commit point, as an alpha on its
// solid colour; crossing commit jumps it the rest of the way to solid.
private const val PreCommitFloodAlpha = 0.45f

/**
 * A dual [SwipeCommitRow] side: [primary] at the edge, [secondary] beside it.
 * From the open width to [CommitThreshold] the secondary cell fades out and
 * the whole side (both cells and the gap behind the card) floods with the
 * primary's colour, growing bolder; past commit, over the stretch to
 * [DualCommitCap], it deepens to solid and the primary's icon and label turn
 * [com.rrajath.grove.ui.theme.GroveColors.accentInk] to stay readable.
 * [offset] is read in the draw phase, so drag frames before the commit point
 * redraw without recomposing.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DualCommitPanel(
    primary: SwipeAction,
    secondary: SwipeAction,
    anchorEnd: Boolean,
    shape: Shape,
    offset: () -> Float,
    onAction: () -> Unit,
) {
    val c = MaterialTheme.grove
    val density = LocalDensity.current
    val openPx = with(density) { DualPanelWidth.toPx() }
    val commitPx = with(density) { CommitThreshold.toPx() }
    val capPx = with(density) { DualCommitCap.toPx() }
    // 0 at the open width, 1 at the commit point.
    fun progress() = ((abs(offset()) - openPx) / (commitPx - openPx)).coerceIn(0f, 1f)
    // 0 at the commit point, 1 at the cap.
    fun deepen() = ((abs(offset()) - commitPx) / (capPx - commitPx)).coerceIn(0f, 1f)
    val primaryInk by remember(primary.fg, c.accentInk) { derivedStateOf { lerp(primary.fg, c.accentInk, deepen()) } }

    Row(
        Modifier.matchParentSize().clip(shape).drawBehind {
            val alpha = progress() * PreCommitFloodAlpha + deepen() * (1f - PreCommitFloodAlpha)
            if (alpha > 0f) drawRect(primary.fg.copy(alpha = primary.fg.alpha * alpha))
        },
        horizontalArrangement = if (anchorEnd) Arrangement.End else Arrangement.Start,
    ) {
        val cells = if (anchorEnd) listOf(secondary, primary) else listOf(primary, secondary)
        cells.forEach { action ->
            val isPrimary = action === primary
            Column(
                Modifier
                    .width(CellWidth)
                    .fillMaxHeight()
                    // The cell's own soft fill hands over to the flood as it grows.
                    .drawBehind { drawRect(action.bg.copy(alpha = action.bg.alpha * (1f - progress()))) }
                    .graphicsLayer { if (!isPrimary) alpha = 1f - progress() }
                    .clickable {
                        onAction()
                        action.onClick()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val ink = if (isPrimary) primaryInk else action.fg
                ActionMark(action.copy(fg = ink), iconSize = 17.dp, glyphSize = 16.sp)
                Text(
                    action.label,
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 9.sp, color = ink,
                )
            }
        }
    }
}

/** Single full-bleed action underlay for [SwipeCommitRow], icon+label anchored near the near edge. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CommitUnderlay(action: SwipeAction, anchorEnd: Boolean, shape: Shape) {
    Box(
        Modifier.matchParentSize().clip(shape).background(action.bg),
        contentAlignment = if (anchorEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ActionMark(action, iconSize = 18.dp, glyphSize = 17.sp)
            Text(
                action.label,
                fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                fontSize = 9.sp, color = action.fg,
            )
        }
    }
}

/**
 * A cell's [SwipeAction.icon] or [SwipeAction.glyph], centered in a fixed-height
 * slot. The height is pinned because a Material `Icon` measures exactly
 * [iconSize] while a text glyph measures its font's line height; left to their
 * intrinsic sizes, icon cells and glyph cells push their labels to different
 * baselines and the panel's labels no longer line up.
 */
@Composable
private fun ActionMark(action: SwipeAction, iconSize: Dp, glyphSize: TextUnit) {
    Box(Modifier.height(MarkSlotHeight), contentAlignment = Alignment.Center) {
        if (action.icon != null) {
            Icon(action.icon, contentDescription = null, tint = action.fg, modifier = Modifier.size(iconSize))
        } else {
            Text(
                action.glyph.orEmpty(),
                fontFamily = PlexSans,
                fontSize = glyphSize,
                lineHeight = glyphSize,
                color = action.fg,
            )
        }
    }
}

/** Tall enough for the largest mark either panel draws (18dp icon / 17sp glyph). */
private val MarkSlotHeight = 22.dp

/** The 46dp action cells (four or five per [SwipeRevealRow] side, two for a dual [SwipeCommitRow] side), anchored to one edge behind the card. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ActionPanel(
    actions: List<SwipeAction>,
    anchorEnd: Boolean,
    onAction: () -> Unit,
    shape: Shape = RectangleShape,
) {
    Row(
        Modifier.matchParentSize().clip(shape),
        horizontalArrangement = if (anchorEnd) Arrangement.End else Arrangement.Start,
    ) {
        actions.forEach { action ->
            Column(
                Modifier
                    .width(CellWidth)
                    .fillMaxHeight()
                    .background(action.bg)
                    .clickable {
                        onAction()
                        action.onClick()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ActionMark(action, iconSize = 17.dp, glyphSize = 16.sp)
                Text(
                    action.label,
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 9.sp, color = action.fg,
                )
            }
        }
    }
}
