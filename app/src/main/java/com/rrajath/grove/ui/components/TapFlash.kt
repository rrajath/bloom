package com.rrajath.grove.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * A one-shot "look here" background flash: [trigger] pops in a surface3 chip
 * behind the content, then fades it back out. Paint [color] as a background
 * (it is transparent at rest).
 */
@Stable
class TapFlash internal constructor(
    private val colorState: State<Color>,
    private val start: () -> Unit,
) {
    val color: Color get() = colorState.value
    fun trigger() = start()
}

/**
 * Flash timing shared by the timestamp picker's preview and Read mode's
 * advanced-repeater date chips: fade to surface3 over 180ms, hold, revert after
 * 260ms. The trigger count keys the revert effect, so a fresh trigger restarts
 * (not stacks) the timer and the flash only ever cycles on-then-off once.
 */
@Composable
fun rememberTapFlash(label: String): TapFlash {
    val c = MaterialTheme.grove
    val on = remember { mutableStateOf(false) }
    val tapId = remember { mutableIntStateOf(0) }
    val colorState = animateColorAsState(
        targetValue = if (on.value) c.surface3 else Color.Transparent,
        animationSpec = tween(180),
        label = label,
    )
    LaunchedEffect(tapId.intValue) {
        if (tapId.intValue > 0) {
            delay(260.milliseconds)
            on.value = false
        }
    }
    return remember(colorState) {
        TapFlash(colorState) {
            on.value = true
            tapId.intValue++
        }
    }
}
