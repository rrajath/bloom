package com.rrajath.grove.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether read mode typesets LaTeX fragments (`$x^2$`, `\[ … \]`, `\begin{align}`) instead of
 * showing their source. Set from Settings § Notes → Render math and provided once at the
 * composition root, so the text renderer reads it without every screen between threading a
 * parameter. Defaults to on for previews and tests.
 */
val LocalRenderMath = staticCompositionLocalOf { true }
