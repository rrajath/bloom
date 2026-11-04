package com.rrajath.grove.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * The current time, recomposing on each minute boundary: the cadence a clocked
 * total shown in `H:MM` changes at. Call it only while a clock runs, so nothing
 * ticks otherwise.
 */
@Composable
fun rememberMinuteTicker(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            val t = LocalDateTime.now()
            delay(60_000L - (t.second * 1_000L + t.nano / 1_000_000))
            value = LocalDateTime.now()
        }
    }
    return now
}
