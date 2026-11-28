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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.UiText
import com.rrajath.grove.ui.util.asString
import com.rrajath.grove.ui.util.uppercaseForUi
import com.rrajath.grove.ui.vault.OutlineSnack
import com.rrajath.grove.ui.vault.OutlineToast

/**
 * Coordinates [GroveToast] between a screen's main window and any open
 * [GroveModalBottomSheet] dialog window. While at least one sheet is open,
 * screen-level [GroveToast]s hide themselves and the sheet renders the active
 * toast above its own content so it never sits behind the scrim.
 */
class SheetToastState {
    var toast by mutableStateOf<OutlineToast?>(null)
    var openSheets by mutableIntStateOf(0)
}

val LocalSheetToast = compositionLocalOf<SheetToastState?> { null }

/**
 * Registers [toast] so any [GroveModalBottomSheet] opened within [content]
 * automatically renders it above the sheet and suppresses the underlying
 * screen's [GroveToast] while the sheet is open.
 */
@Composable
fun ProvideSheetToast(toast: OutlineToast?, content: @Composable () -> Unit) {
    val state = remember { SheetToastState() }
    state.toast = toast
    CompositionLocalProvider(LocalSheetToast provides state, content = content)
}

/**
 * Bloom's shared [ModalBottomSheet] wrapper: applies the standard `surface`
 * fill and 24dp top corners, and automatically renders the active [GroveToast]
 * (from [toast] or [LocalSheetToast]) inside the sheet's own dialog window so
 * toasts triggered while any sheet is open always appear above it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroveModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    containerColor: Color = MaterialTheme.grove.surface,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    toast: OutlineToast? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetToast = LocalSheetToast.current
    DisposableEffect(sheetToast) {
        sheetToast?.let { it.openSheets += 1 }
        onDispose { sheetToast?.let { it.openSheets = (it.openSheets - 1).coerceAtLeast(0) } }
    }
    val activeToast = toast ?: sheetToast?.toast
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
    ) {
        Box {
            Column(Modifier.fillMaxWidth(), content = content)
            GroveToast(
                toast = activeToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                inSheet = true,
            )
        }
    }
}

/**
 * Bottom-center dark pill toast (design spec: ink bg, bg-color text, ~1.9s;
 * the ViewModel owns the timer). Render inside a full-size Box.
 */
@Composable
fun GroveToast(
    toast: OutlineToast?,
    modifier: Modifier = Modifier,
    inSheet: Boolean = false,
) {
    val c = MaterialTheme.grove
    val hiddenBySheet = !inSheet && (LocalSheetToast.current?.openSheets ?: 0) > 0
    // Keep the last message so the exit animation doesn't show an empty pill.
    var lastMessage by remember { mutableStateOf<UiText>(UiText.raw("")) }
    toast?.let { lastMessage = it.message }
    AnimatedVisibility(
        visible = toast != null && !hiddenBySheet,
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
                lastMessage.asString(),
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
    var lastMessage by remember { mutableStateOf<UiText>(UiText.raw("")) }
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
                lastMessage.asString(),
                fontFamily = PlexSans, fontSize = 13.5.sp, color = c.bg,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.action_undo).uppercaseForUi(),
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
                    stringResource(R.string.action_cancel),
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
