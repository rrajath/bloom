package com.rrajath.grove.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.OutlineSnack
import com.rrajath.grove.ui.vault.OutlineToast

/**
 * Bottom-center dark pill toast (design spec: ink bg, bg-color text, ~1.9s;
 * the ViewModel owns the timer). Render inside a full-size Box.
 */
@Composable
fun GroveToast(toast: OutlineToast?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.grove
    // Keep the last message so the exit animation doesn't show an empty pill.
    var lastMessage by remember { mutableStateOf("") }
    toast?.let { lastMessage = it.message }
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Box(
            Modifier
                .testTag("grove_toast")
                .clip(RoundedCornerShape(20.dp))
                .background(c.ink)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                lastMessage,
                fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                fontSize = 13.sp, color = c.bg,
            )
        }
    }
}

/**
 * Bottom undo snackbar for structural ops (design spec: ink bg, 12dp radius,
 * 14dp side margins, UNDO in bold accent, ~4.2s; ViewModel owns the timer).
 */
@Composable
fun GroveUndoSnackbar(
    snack: OutlineSnack?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    var lastMessage by remember { mutableStateOf("") }
    snack?.let { lastMessage = it.message }
    AnimatedVisibility(
        visible = snack != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.ink)
                // The whole bar intercepts taps (and triggers undo), not just the
                // "UNDO" glyph; otherwise a tap that misses the small text falls
                // through to the list item rendered underneath this overlay.
                .clickable(onClick = onUndo)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lastMessage,
                fontFamily = PlexSans, fontSize = 13.5.sp, color = c.bg,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "UNDO",
                fontFamily = PlexSans, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, color = c.accent,
            )
        }
    }
}

/**
 * Bottom action snackbar for a one-shot confirmation with a follow-up action
 * (design spec: same ink bg/12dp radius/14dp margins as [GroveUndoSnackbar],
 * but the action is a distinct button rather than a whole-bar tap target,
 * since dismissing this one is not itself a meaningful action to guard
 * against a stray tap on).
 */
@Composable
fun GroveActionSnackbar(
    visible: Boolean,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    var lastMessage by remember { mutableStateOf("") }
    if (visible) lastMessage = message
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.ink)
                .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lastMessage,
                fontFamily = PlexSans, fontSize = 13.5.sp, color = c.bg,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                actionLabel,
                fontFamily = PlexSans, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, color = c.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Progress snackbar for a running attach copy (attachments 1c): inverse `ink`
 * pill with a title, a mono subline, a Cancel action in the UNDO color, and a
 * 3dp progress track. [fraction] null draws an indeterminate track.
 */
@Composable
fun GroveProgressSnackbar(
    visible: Boolean,
    title: String,
    subline: String,
    fraction: Float?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    var lastTitle by remember { mutableStateOf("") }
    var lastSub by remember { mutableStateOf("") }
    if (visible) {
        lastTitle = title
        lastSub = subline
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.ink)
                .padding(start = 16.dp, end = 8.dp, top = 11.dp, bottom = 11.dp)
                .testTag("attach_progress"),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        lastTitle,
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 13.5.sp, color = c.bg,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        lastSub,
                        fontFamily = PlexMono, fontSize = 11.5.sp,
                        color = c.bg.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Cancel",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp, color = c.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("attach_progress_cancel"),
                )
            }
            val trackColor = c.ink3.copy(alpha = 0.35f)
            val fillColor = c.accent
            val track = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = track,
                    color = fillColor,
                    trackColor = trackColor,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(
                    modifier = track,
                    color = fillColor,
                    trackColor = trackColor,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                )
            }
        }
    }
}
