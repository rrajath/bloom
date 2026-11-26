package com.rrajath.grove.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.max

/**
 * Vertical layout of a ledger widget row's first line, in dp.
 *
 * Glance can't align by baseline or report text-layout metrics, so the row's
 * pieces (circle, keyword pill, title, priority box) are top-aligned and pushed
 * down by padding computed here from the real font metrics. RemoteViews
 * `TextView`s keep `includeFontPadding`, so a text box runs from the font's
 * `top` to its `bottom` metric and its baseline sits `-top` below its top edge.
 *
 * The goals: the pill's keyword and the title share a baseline, the pill has
 * the same visible gap above and below its capitals, and the circle and
 * priority box are centered on the pill's capitals (also on rows without a pill,
 * so every row's circle sits at the same height relative to its title).
 */
internal data class LedgerRowMetrics(
    /** Top padding inside the pill, above the keyword's text box. */
    val chipPaddingTop: Float,
    /** Bottom padding inside the pill, below the keyword's text box. */
    val chipPaddingBottom: Float,
    /** Offset of the pill from the top of the title line. */
    val chipOffset: Float,
    /** Offset of the title text from the top of the title line. */
    val titleOffset: Float,
    /** Distance from the top of the row content to the pill's visual center. */
    val centerY: Float,
) {
    /** Font measurements of one text style, in px, signs as in [Paint.FontMetrics]. */
    data class Font(val top: Float, val bottom: Float, val capTop: Float)

    companion object {
        /** Visible gap between the keyword's capitals and the pill's top/bottom edges, before font slack. */
        const val CHIP_MIN_PADDING_DP = 2f
        const val TITLE_SIZE_SP = 13f
        const val CHIP_SIZE_SP = 9.5f

        fun compute(title: Font, chip: Font, density: Float): LedgerRowMetrics {
            val minPad = CHIP_MIN_PADDING_DP * density
            // Gaps the text box itself leaves above the capitals and below the baseline.
            val slackTop = chip.capTop - chip.top
            val slackBottom = chip.bottom
            val visualPad = max(slackTop, slackBottom) + minPad
            val padTop = visualPad - slackTop
            val padBottom = visualPad - slackBottom
            // Baselines measured from each element's own top edge.
            val titleBaseline = -title.top
            val chipBaseline = padTop - chip.top
            val shift = titleBaseline - chipBaseline
            val chipOffset = max(0f, shift)
            val titleOffset = max(0f, -shift)
            val capHeight = -chip.capTop
            val centerY = titleOffset + titleBaseline - capHeight / 2f
            return LedgerRowMetrics(
                chipPaddingTop = padTop / density,
                chipPaddingBottom = padBottom / density,
                chipOffset = chipOffset / density,
                titleOffset = titleOffset / density,
                centerY = centerY / density,
            )
        }

        /** Measures the widget's title and pill fonts at [fontScale] (the widget's own text-size setting). */
        fun measure(context: Context, fontScale: Float): LedgerRowMetrics {
            val dm = context.resources.displayMetrics
            fun font(sizeSp: Float, typeface: Typeface): Font {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.typeface = typeface
                    // Same sp conversion a TextView applies, including Android 14's non-linear scaling.
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * fontScale, dm)
                }
                val fm = paint.fontMetrics
                val caps = Rect().also { paint.getTextBounds("H", 0, 1, it) }
                return Font(top = fm.top, bottom = fm.bottom, capTop = caps.top.toFloat())
            }
            return compute(
                title = font(TITLE_SIZE_SP, Typeface.DEFAULT),
                chip = font(CHIP_SIZE_SP, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)),
                density = dm.density,
            )
        }
    }
}
