package com.rrajath.grove.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

// Arrow glyphs drawn as text. Unlike `‹ ›`, the arrows `← →` are not mirrored by the
// bidi algorithm, so under an RTL locale a "back" arrow drawn as "←" would point the
// wrong way. These pick the glyph from the layout direction instead.

/** "Back": points toward the start edge. */
@Composable
@ReadOnlyComposable
fun backArrowGlyph(): String = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "→" else "←"

/** "Forward" / "go to": points toward the end edge. */
@Composable
@ReadOnlyComposable
fun forwardArrowGlyph(): String = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "←" else "→"
