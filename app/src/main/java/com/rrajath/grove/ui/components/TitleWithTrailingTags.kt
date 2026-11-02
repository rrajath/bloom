package com.rrajath.grove.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A heading title followed by a trailing cluster (an optional [leading] item such as
 * the collapsed `… N` count, the org-style `:a:b:` tag run, an optional [trailing]
 * item such as the favorite star), kept flush to the row's end: right in an LTR row,
 * left in an RTL one.
 *
 * The title always gets the full width. The cluster sits on the title's last line,
 * baseline-aligned, when it fits there with [minGap] to spare; otherwise it drops
 * below the title. A cluster wider than the row wraps between tags, each line flush to
 * the end and opening with its own `:`. A single tag wider than the row wraps inside.
 *
 * [title] must forward the [TextLayoutResult] it is handed to its `onTextLayout`: the
 * layout reads the last line's extent during the same measure pass, so no second
 * composition is needed.
 */
@Composable
fun TitleWithTrailingTags(
    tags: List<String>,
    tagStyle: TextStyle,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    gap: Dp = 6.dp,
    minGap: Dp = 12.dp,
    lineSpacing: Dp = 2.dp,
    title: @Composable (onTextLayout: (TextLayoutResult) -> Unit) -> Unit,
) {
    // Written by the title's onTextLayout, which runs inside its measure; a plain
    // holder, not state, so reading it doesn't schedule another pass.
    val titleLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(
        contents = listOf(
            { title { titleLayout[0] = it } },
            { leading?.invoke() },
            { trailing?.invoke() },
            {
                // One opening ":" per tag; only those that start a run get placed.
                tags.forEach { _ ->
                    Text(":", style = tagStyle, modifier = Modifier.clearAndSetSemantics {})
                }
            },
            { tags.forEach { Text("$it:", style = tagStyle, modifier = Modifier.testTag("heading_tag")) } },
        ),
        modifier = modifier,
    ) { (titleM, leadM, trailM, colonM, tagM), constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val gapPx = gap.roundToPx()
        val titleP = titleM.first().measure(loose)
        val leadP = leadM.firstOrNull()?.measure(loose)
        val trailP = trailM.firstOrNull()?.measure(loose)
        val colonPs = colonM.map { it.measure(loose) }
        val colonW = colonPs.firstOrNull()?.width ?: 0
        val tagPs = tagM.map { it.measure(Constraints(maxWidth = (width - colonW).coerceAtLeast(0))) }

        // Pack the cluster at full width: lead, tags (a run shares its colons), trail.
        val lines = mutableListOf<ClusterLine>()
        var line = ClusterLine()
        // A tag item is [colon, tag]; it drops its colon when it continues a run.
        fun add(kind: ItemKind, placeables: List<Placeable>) {
            val joinsRun = kind == ItemKind.TAG && line.items.lastOrNull()?.kind == ItemKind.TAG
            val alone = placeables.sumOf { it.width }
            val before = if (line.items.isEmpty() || joinsRun) 0 else gapPx
            val w = before + if (joinsRun) placeables.last().width else alone
            if (line.items.isNotEmpty() && line.width + w > width) {
                lines += line
                line = ClusterLine()
                line.items += ClusterItem(kind, placeables, 0)
                line.width = alone
            } else {
                line.items += ClusterItem(kind, if (joinsRun) placeables.takeLast(1) else placeables, line.width + before)
                line.width += w
            }
        }
        leadP?.let { add(ItemKind.OTHER, listOf(it)) }
        tagPs.forEachIndexed { i, p -> add(ItemKind.TAG, listOf(colonPs[i], p)) }
        trailP?.let { add(ItemKind.OTHER, listOf(it)) }
        if (line.items.isNotEmpty()) lines += line
        lines.forEach { it.arrange() }

        // Free space past the title's last line, on the row's end side.
        val ltr = layoutDirection == LayoutDirection.Ltr
        val tl = titleLayout[0]
        val free = if (tl == null || lines.isEmpty()) 0 else {
            val last = tl.lineCount - 1
            val boxLeft = if (ltr) 0 else width - titleP.width
            if (ltr) width - (boxLeft + ceil(tl.getLineRight(last)).toInt())
            else boxLeft + floor(tl.getLineLeft(last)).toInt()
        }
        val inline = tl != null && lines.size == 1 && lines[0].width + minGap.roundToPx() <= free

        var titleTop = 0
        val lineTops = IntArray(lines.size)
        val height: Int
        if (inline) {
            val last = tl!!.lineCount - 1
            val only = lines[0]
            var top = if (only.baseline != AlignmentLine.Unspecified) {
                tl.getLineBaseline(last).toInt() - only.baseline
            } else {
                floor(tl.getLineTop(last)).toInt()
            }
            if (top < 0) {
                titleTop = -top
                top = 0
            }
            lineTops[0] = top
            height = maxOf(titleTop + titleP.height, top + only.height)
        } else {
            var y = titleP.height
            lines.forEachIndexed { i, l ->
                y += lineSpacing.roundToPx()
                lineTops[i] = y
                y += l.height
            }
            height = y
        }

        val titleBaseline = titleP[FirstBaseline]
        val alignment = if (titleBaseline != AlignmentLine.Unspecified) {
            mapOf<AlignmentLine, Int>(FirstBaseline to titleTop + titleBaseline)
        } else {
            emptyMap()
        }
        layout(width, height, alignment) {
            titleP.placeRelative(0, titleTop)
            lines.forEachIndexed { i, l ->
                val start = width - l.width
                l.items.forEach { item ->
                    var x = start + item.x
                    item.placeables.forEachIndexed { j, p ->
                        p.placeRelative(x, lineTops[i] + item.ys[j])
                        x += p.width
                    }
                }
            }
        }
    }
}

private enum class ItemKind { TAG, OTHER }

private class ClusterItem(val kind: ItemKind, val placeables: List<Placeable>, val x: Int) {
    var ys = IntArray(placeables.size)
}

private class ClusterLine {
    val items = mutableListOf<ClusterItem>()
    var width = 0
    var baseline = AlignmentLine.Unspecified
    var height = 0

    /** Baseline-aligns the line's text; an item without a baseline (an icon) sits at the top, as in a Row. */
    fun arrange() {
        baseline = items.flatMap { it.placeables }.map { it[FirstBaseline] }
            .filter { it != AlignmentLine.Unspecified }.maxOrNull() ?: AlignmentLine.Unspecified
        items.forEach { item ->
            item.ys = IntArray(item.placeables.size) { j ->
                val b = item.placeables[j][FirstBaseline]
                if (b == AlignmentLine.Unspecified || baseline == AlignmentLine.Unspecified) 0 else baseline - b
            }
            item.placeables.forEachIndexed { j, p -> height = maxOf(height, item.ys[j] + p.height) }
        }
    }
}
