package com.rrajath.grove.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.org.InlineToken
import com.rrajath.grove.org.math.MathKind
import com.rrajath.grove.org.math.MathLayout
import com.rrajath.grove.org.math.MathMetrics
import com.rrajath.grove.org.math.MathOp
import com.rrajath.grove.org.math.MathTypesetter
import com.rrajath.grove.org.math.MathVariant
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.PlexSerif
import kotlin.math.max

/**
 * Draws LaTeX typeset by [MathTypesetter]. The layout is platform-free; this file is the
 * thin Compose side: it supplies glyph widths (from a [TextMeasurer]) and paints the
 * resulting display list on a Canvas.
 *
 * Inline fragments (`$x^2$`) become [InlineTextContent] placeholders inside the
 * paragraph's text; display fragments (`$$ … $$`, `\[ … \]`, environments) are
 * standalone blocks ([DisplayMath]).
 */

/** The typeset formulas of one text run: which tokens they replace, and their placeholder content. */
internal class MathRun(
    /** Math token source range to `inlineContent` id, the shape [annotateOrgInline] takes. */
    val ids: Map<IntRange, String>,
    val content: Map<String, InlineTextContent>,
)

internal val NoMath = MathRun(emptyMap(), emptyMap())

private fun mathTextStyle(variant: MathVariant, size: TextUnit, color: Color) = TextStyle(
    fontFamily = when (variant) {
        MathVariant.SANS -> PlexSans
        MathVariant.MONO -> PlexMono
        else -> PlexSerif
    },
    fontSize = size,
    color = color,
    fontStyle = if (variant == MathVariant.ITALIC || variant == MathVariant.BOLD_ITALIC) FontStyle.Italic else FontStyle.Normal,
    fontWeight = if (variant == MathVariant.BOLD || variant == MathVariant.BOLD_ITALIC) FontWeight.SemiBold else FontWeight.Normal,
)

/** Glyph advance widths measured with the real fonts, memoised per formula set. */
private class ComposeMathMetrics(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val color: Color,
) : MathMetrics {
    private val widths = HashMap<Triple<String, Float, MathVariant>, Float>()

    override fun width(text: String, sizePx: Float, variant: MathVariant): Float =
        widths.getOrPut(Triple(text, sizePx, variant)) {
            val style = mathTextStyle(variant, with(density) { sizePx.toSp() }, color)
            val result = measurer.measure(
                text, style,
                softWrap = false, maxLines = 1,
                layoutDirection = LayoutDirection.Ltr, density = density,
            )
            result.getLineRight(0)
        }
}

private fun DrawScope.drawMath(layout: MathLayout, measurer: TextMeasurer, color: Color, baseline: Float) {
    for (op in layout.ops) {
        when (op) {
            is MathOp.Glyphs -> {
                val result = measurer.measure(
                    op.text, mathTextStyle(op.variant, op.sizePx.toSp(), color),
                    softWrap = false, maxLines = 1,
                    layoutDirection = LayoutDirection.Ltr, density = this,
                )
                drawText(result, topLeft = Offset(op.x, baseline + op.y - result.firstBaseline))
            }

            is MathOp.Rule -> drawRect(
                color,
                topLeft = Offset(op.x, baseline + op.y),
                // Keep thin rules (fraction bars, underlines) visible on low-density screens.
                size = Size(max(op.w, 0f), max(op.h, 1f)),
            )

            is MathOp.Poly -> {
                val path = Path()
                path.moveTo(op.pts[0], baseline + op.pts[1])
                var i = 2
                while (i < op.pts.size) {
                    path.lineTo(op.pts[i], baseline + op.pts[i + 1])
                    i += 2
                }
                drawPath(
                    path, color,
                    style = Stroke(width = max(op.strokePx, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/** Resolves the color and font size math should be set in from the surrounding text [style]. */
@Composable
private fun mathColor(style: TextStyle): Color =
    if (style.color == Color.Unspecified) LocalContentColor.current else style.color

private fun mathFontSize(style: TextStyle): TextUnit = if (style.fontSize.isSp) style.fontSize else 16.sp

/**
 * Placeholders for every typesettable math token in [tokens]. A token that can't be typeset
 * has no entry, so the text shows its source instead.
 *
 * Each placeholder is as tall as the formula's ascent and sits on the text baseline
 * (`AboveBaseline`); whatever hangs below the baseline (a fraction's denominator, a
 * subscript) is drawn past the placeholder's bottom edge, which Compose doesn't clip.
 */
@Composable
internal fun rememberInlineMath(
    tokens: List<InlineToken>,
    style: TextStyle,
    enabled: Boolean,
): MathRun {
    if (!enabled || tokens.none { it.math != null }) return NoMath
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val color = mathColor(style)
    val fontSize = mathFontSize(style)
    return remember(tokens, density, measurer, color, fontSize) {
        val sizePx = with(density) { fontSize.toPx() }
        val metrics = ComposeMathMetrics(measurer, density, color)
        val ids = LinkedHashMap<IntRange, String>()
        val content = LinkedHashMap<String, InlineTextContent>()
        for (token in tokens) {
            val fragment = token.math ?: continue
            val layout = MathTypesetter.layout(fragment.body, fragment.kind == MathKind.DISPLAY, sizePx, metrics)
                ?: continue
            val baseline = max(layout.ascent, 1f)
            val id = "math-${token.range.first}"
            ids[token.range] = id
            content[id] = InlineTextContent(
                Placeholder(
                    width = with(density) { max(layout.width, 1f).toSp() },
                    height = with(density) { baseline.toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                ),
            ) {
                MathCanvas(layout, measurer, color, baseline, Modifier)
            }
        }
        MathRun(ids, content)
    }
}

@Composable
private fun MathCanvas(
    layout: MathLayout,
    measurer: TextMeasurer,
    color: Color,
    /** Distance from the canvas top to the formula's baseline, px. */
    baseline: Float,
    modifier: Modifier,
    canvasHeight: Float = baseline,
) {
    val density = LocalDensity.current
    val width = with(density) { max(layout.width, 1f).toDp() }
    val height = with(density) { canvasHeight.toDp() }
    Canvas(modifier.size(width, height)) {
        drawMath(layout, measurer, color, baseline)
    }
}

/**
 * A display formula on its own line, centred, and scrollable sideways when it is wider
 * than the screen. [onDoubleTap] switches to edit mode like a double-tap on text does.
 * If [source] can't be typeset it is shown verbatim.
 */
@Composable
internal fun DisplayMath(
    source: String,
    raw: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onDoubleTap: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val color = mathColor(style)
    val fontSize = mathFontSize(style)
    val layout = remember(source, density, measurer, color, fontSize) {
        MathTypesetter.layout(
            source, display = true, sizePx = with(density) { fontSize.toPx() },
            metrics = ComposeMathMetrics(measurer, density, color),
        )
    }
    val tapModifier = modifier.doubleTapToEdit(
        layoutResult = { null },
        enabled = onDoubleTap != null,
        onDoubleTap = { onDoubleTap?.invoke() },
    )
    if (layout == null) {
        Text(raw, style = style, modifier = tapModifier)
        return
    }
    val scroll = rememberScrollState()
    BoxWithConstraints(tapModifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // The row is at least as wide as the viewport so a narrow formula centres, and wider
        // content scrolls.
        Row(
            Modifier.horizontalScroll(scroll).widthIn(min = maxWidth),
            horizontalArrangement = Arrangement.Center,
        ) {
            MathCanvas(
                layout, measurer, color,
                baseline = max(layout.ascent, 1f),
                modifier = Modifier,
                canvasHeight = max(layout.height, 1f),
            )
        }
    }
}
