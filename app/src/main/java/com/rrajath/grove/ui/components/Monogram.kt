package com.rrajath.grove.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.uppercaseForUi

/**
 * Monogram icons for notebooks and capture templates (design: `Notebook Icons.dc.html`
 * variant 1a). A letter derived from the name, set in the mono face, on a soft-tinted
 * rounded tile. Colour is the only user-picked axis — [ChangeIconColorDialog].
 */

/** Palette keys persisted in settings; resolved against the live theme via [monogramPalette]. */
val MONOGRAM_PALETTE_KEYS: List<String> = listOf("green", "blue", "amber", "red", "accent", "neutral")

/** (foreground, soft background) for a palette [key], falling back to accent for anything unknown. */
fun monogramPalette(c: GroveColors, key: String): Pair<Color, Color> = when (key) {
    "green" -> c.green to c.greenSoft
    "blue" -> c.blue to c.blueSoft
    "amber" -> c.amber to c.amberSoft
    "red" -> c.red to c.redSoft
    "neutral" -> c.ink2 to c.surface2
    else -> c.accent to c.accentSoft
}

/**
 * The uppercase first character of [source] — a notebook's display name (its `#+TITLE:`
 * or file name, per the Notebook display setting) or a template's name. Grapheme-aware
 * for the first code point so an emoji or accented letter survives intact. Falls back to
 * a bullet when [source] is blank (e.g. a template being named for the first time).
 */
fun monogramLetter(source: String): String {
    val trimmed = source.trim()
    if (trimmed.isEmpty()) return "•"
    val codePoint = trimmed.codePointAt(0)
    return String(Character.toChars(codePoint)).uppercase()
}

private fun nameHash(name: String): Int =
    name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }

/** Default palette key for a name that has no user override — keeps the list looking varied. */
fun nameHashPaletteKey(name: String): String =
    MONOGRAM_PALETTE_KEYS[nameHash(name) % MONOGRAM_PALETTE_KEYS.size]

/** A rounded-rect dashed stroke drawn inside the node's bounds, matching CSS `1px dashed`. */
private fun Modifier.dashedBorder(color: Color, cornerRadius: Dp, strokeWidth: Dp = 0.75.dp): Modifier =
    drawWithContent {
        drawContent()
        val w = strokeWidth.toPx()
        val r = cornerRadius.toPx()
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(
                width = w,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 2.5.dp.toPx()), 0f),
            ),
        )
    }

/**
 * A [size]-square rounded tile with [letter] centred in it, tinted by [colorKey].
 * Apply a `combinedClickable`/`clickable` [modifier] at the call site to make it
 * interactive (e.g. long-press to open [ChangeIconColorDialog]).
 *
 * Capture-template tiles set [dashedBorder] and [addBadge] (design: prototype capture
 * picker) — a dashed outline in the palette colour and a small `+` badge overhanging
 * the bottom-right corner. The badge is drawn outside the tile bounds, so callers must
 * not clip the row it sits in.
 */
@Composable
fun MonogramTile(
    letter: String,
    colorKey: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    cornerRadius: Dp = 12.dp,
    dashedBorder: Boolean = false,
    addBadge: Boolean = false,
) {
    val c = MaterialTheme.grove
    val (fg, bg) = monogramPalette(c, colorKey)
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(bg)
                .then(if (dashedBorder) Modifier.dashedBorder(fg, cornerRadius) else Modifier)
                .then(modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter,
                fontFamily = PlexMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.40f).sp,
                color = fg,
            )
        }
        if (addBadge) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = size * 0.10f, y = size * 0.10f)
                    .size(size * 0.36f)
                    .clip(CircleShape)
                    .background(c.surface)
                    .border(0.75.dp, fg, CircleShape),
            ) {
                // A geometry-drawn "+" so it is exactly centred (a text glyph's
                // baseline padding leaves it sitting slightly high in the circle).
                Canvas(Modifier.matchParentSize()) {
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val arm = this.size.minDimension * 0.26f
                    val stroke = this.size.minDimension * 0.11f
                    drawLine(fg, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke, StrokeCap.Round)
                    drawLine(fg, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

/**
 * Colour-only picker for a notebook or template monogram (design 1a "Long press → picker").
 * A preview row (tile + name + hint), then the theme's palette as swatches.
 */
@Composable
fun ChangeIconColorDialog(
    name: String,
    hint: String,
    letter: String,
    currentColorKey: String,
    onPickColor: (String) -> Unit,
    onDismiss: () -> Unit,
    /** When set, the preview tile shows this glyph (e.g. `▪` for a folder) instead of [letter]. */
    glyph: String? = null,
    /** Preview a capture-template tile (dashed outline + `+` badge) rather than a plain one. */
    templateIcon: Boolean = false,
) {
    val c = MaterialTheme.grove
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                stringResource(R.string.monogram_change_color),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, color = c.ink,
            )
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (glyph != null) {
                        val (fg, bg) = monogramPalette(c, currentColorKey)
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(bg)
                                .border(1.dp, fg.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(glyph, fontFamily = PlexMono, fontSize = 15.sp, color = fg)
                        }
                    } else {
                        MonogramTile(
                            letter = letter,
                            colorKey = currentColorKey,
                            size = 42.dp,
                            dashedBorder = templateIcon,
                            addBadge = templateIcon,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            name,
                            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                            fontSize = 14.5.sp, color = c.ink,
                        )
                        Text(hint, fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink3)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.monogram_color_header).uppercaseForUi(),
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 10.5.sp, letterSpacing = 1.sp, color = c.ink3,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MONOGRAM_PALETTE_KEYS.forEach { key ->
                        val (fg, _) = monogramPalette(c, key)
                        val selected = key == currentColorKey
                        // Prototype selection cue: a 2dp gap (surface shows through) then a
                        // 2dp ring in the swatch's own colour.
                        Box(
                            Modifier
                                .size(34.dp)
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, fg, RoundedCornerShape(10.dp))
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(fg)
                                    .clickable { onPickColor(key) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done), color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
