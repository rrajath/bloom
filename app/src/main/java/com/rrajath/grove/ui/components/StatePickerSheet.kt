package com.rrajath.grove.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * TODO-state bottom sheet: every configured keyword (plus "none") as a tappable
 * chip. Shared by the Outline's and Search's swipe-to-cycle-state actions so
 * both read as the same control as the metadata sheet's own state chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatePickerSheet(
    title: String,
    keywords: OrgKeywords,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    GroveModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Text(
                "STATE",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp, letterSpacing = 1.sp, color = c.accent,
            )
            Text(
                title,
                fontFamily = PlexSans, fontSize = 13.5.sp, color = c.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StateChip("none", active = current == null, fg = c.ink2, bg = c.surface2) {
                    onPick(null)
                }
                keywords.all.forEach { kw ->
                    val done = keywords.isDone(kw)
                    StateChip(
                        label = kw,
                        active = current == kw,
                        fg = if (done) c.green else c.amber,
                        bg = if (done) c.greenSoft else c.amberSoft,
                    ) { onPick(kw) }
                }
            }
        }
    }
}

/**
 * Matches the metadata sheet's state chips so every picker reads as one control.
 * [fg] always paints the label, active or not, so a done-type keyword (green)
 * reads the same as its badge everywhere else in the app (Outline rows, Search
 * results, Agenda rows); the active chip additionally gets the filled [bg] and
 * bold weight to mark it as the current selection.
 */
@Composable
fun StateChip(label: String, active: Boolean, fg: Color, bg: Color, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) bg else c.surface2.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            fontFamily = PlexMono,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            color = fg,
            // FlowRow wraps chips; a long keyword must not wrap inside its own chip.
            maxLines = 1,
            softWrap = false,
        )
    }
}
