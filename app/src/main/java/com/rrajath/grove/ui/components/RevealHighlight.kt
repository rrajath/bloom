package com.rrajath.grove.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay

/** How long a revealed row holds its full `accentSoft` wash before fading. */
const val REVEAL_HOLD_MS = 300L

/** How long the wash takes to fade back to the row's own background. */
const val REVEAL_FADE_MS = 1200

/**
 * The "reveal" wash shared by Outline (Search's "show in notebook") and Settings search:
 * snap to 1, hold, then fade to 0. Callers draw `lerp(rowBackground, c.accentSoft, value)`.
 */
suspend fun Animatable<Float, AnimationVector1D>.playRevealHighlight() {
    snapTo(1f)
    delay(REVEAL_HOLD_MS)
    animateTo(0f, tween(REVEAL_FADE_MS))
}
