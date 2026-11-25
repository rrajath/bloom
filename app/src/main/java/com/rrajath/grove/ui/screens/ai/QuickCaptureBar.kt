// i18n: all user-visible text comes from string resources
package com.rrajath.grove.ui.screens.ai

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ai.CaptureGranularity
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.asString

/**
 * Compact AI capture strip shown above the notebook list on the home screen.
 * Type a sentence in your own words, optionally pick the tree shape, generate
 * an editable org preview, then insert it into inbox.org.
 */
@Composable
fun QuickCaptureBar(
    state: QuickCaptureViewModel.UiState,
    onTextChange: (String) -> Unit,
    onGranularityChange: (CaptureGranularity) -> Unit,
    onGenerate: () -> Unit,
    onOrgTextChange: (String) -> Unit,
    onInsert: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = MaterialTheme.grove
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ai_quick_label),
                fontFamily = PlexSans,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontSize = 12.5.sp,
                color = c.accent,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.ai_open_settings),
                fontFamily = PlexSans,
                fontSize = 11.5.sp,
                color = c.ink2,
                modifier = Modifier.clickable(onClick = onOpenSettings),
            )
        }

        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            enabled = !state.busy,
            minLines = 1,
            maxLines = 4,
            placeholder = {
                Text(
                    stringResource(R.string.ai_quick_hint),
                    fontFamily = PlexSans,
                    fontSize = 12.5.sp,
                )
            },
            textStyle = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, color = c.ink),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CaptureGranularity.values().forEach { mode ->
                    val selected = state.granularity == mode
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) c.accentSoft else c.surface)
                            .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(8.dp))
                            .clickable(enabled = !state.busy) { onGranularityChange(mode) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            granularityLabel(mode),
                            fontFamily = PlexSans,
                            fontSize = 11.5.sp,
                            color = if (selected) c.accent else c.ink2,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (!state.busy && state.text.isNotBlank()) c.accent else c.surface)
                    .clickable(enabled = !state.busy && state.text.isNotBlank(), onClick = onGenerate)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.ai_quick_generate),
                    fontFamily = PlexSans,
                    fontSize = 12.5.sp,
                    color = if (!state.busy && state.text.isNotBlank()) c.accentInk else c.ink3,
                )
            }
        }

        if (state.orgText.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.ai_quick_preview),
                fontFamily = PlexSans,
                fontSize = 12.sp,
                color = c.ink2,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.orgText,
                onValueChange = onOrgTextChange,
                enabled = !state.busy,
                minLines = 4,
                textStyle = TextStyle(fontFamily = PlexMono, fontSize = 12.sp, color = c.ink),
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (!state.busy && !state.inserted) c.accent else c.surface)
                        .clickable(enabled = !state.busy && !state.inserted, onClick = onInsert)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.ai_quick_insert),
                        fontFamily = PlexSans,
                        fontSize = 12.5.sp,
                        color = if (!state.busy && !state.inserted) c.accentInk else c.ink3,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.ai_quick_clear),
                    fontFamily = PlexSans,
                    fontSize = 12.sp,
                    color = c.ink2,
                    modifier = Modifier.clickable(enabled = !state.busy, onClick = onClear),
                )
            }
        }

        state.status?.let { status ->
            Spacer(Modifier.height(8.dp))
            Text(status.asString(), fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2)
        }
    }
}

@Composable
private fun granularityLabel(mode: CaptureGranularity): String = stringResource(
    when (mode) {
        CaptureGranularity.AUTO -> R.string.ai_quick_mode_auto
        CaptureGranularity.SINGLE -> R.string.ai_quick_mode_single
        CaptureGranularity.MULTI -> R.string.ai_quick_mode_multi
    },
)
