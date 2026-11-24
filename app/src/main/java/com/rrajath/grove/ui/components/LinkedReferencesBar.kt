package com.rrajath.grove.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.countLabel

/**
 * Collapsed "Backlinks" bar (design: Roam Links.dc.html, variant 1a):
 * a persistent row above the note's own bottom chrome. Tapping it opens the
 * full [LinkedReferencesSheet] (variant 1b). Roam files only (file-level :ID:):
 * the Outline, the whole-file Read view (`ReadFileScreen`), the whole-file
 * editor and Dailies. Never on a single heading opened from the Outline.
 */
/**
 * [LinkedReferencesBar] for the editors, where it gives its row up to the
 * formatting toolbar while the keyboard is up. When the keyboard closes it
 * rises from the bottom edge instead of popping in; it leaves instantly, since
 * the toolbar is taking its place in the same frame.
 */
@Composable
fun KeyboardHiddenLinkedReferencesBar(
    visible: Boolean,
    linkedCount: Int,
    unlinkedCount: Int,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(220), expandFrom = Alignment.Top) +
            slideInVertically(tween(220)) { it },
        exit = ExitTransition.None,
    ) {
        LinkedReferencesBar(linkedCount = linkedCount, unlinkedCount = unlinkedCount, onClick = onClick)
    }
}

@Composable
fun LinkedReferencesBar(
    linkedCount: Int,
    unlinkedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface)
            .border(1.dp, c.line)
            .padding(bottom = 15.dp)
            .clickable(onClick = onClick),
    ) {
        // Centered chevron at the top edge, replacing the old trailing caret --
        // reads as a "pull up to expand" affordance instead of a stray glyph
        // competing with the unlinked-count pill on the right.
        Box(
            Modifier.fillMaxWidth().padding(top = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp, contentDescription = null,
                tint = c.ink3, modifier = Modifier.size(16.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 0.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ArrowOutward, contentDescription = null,
                    tint = c.accent, modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Text(
                countLabel(R.plurals.count_backlinks, linkedCount),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 14.5.sp, color = c.ink,
                modifier = Modifier.weight(1f),
            )
            if (unlinkedCount > 0) {
                Pill(text = pluralStringResource(R.plurals.links_unlinked_count, unlinkedCount, unlinkedCount), fg = c.ink2, bg = c.surface2)
            }
        }
    }
}
