package com.rrajath.grove.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.ui.components.monogramPalette
import com.rrajath.grove.ui.components.nameHashPaletteKey
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Selection-triggered counterpart to [AutoLinkSuggestionStrip]: docked in the
 * same slot, offering one chip per eligible Roam-kind capture template
 * ([com.rrajath.grove.capture.hasUserDefinedTitle]) to turn the current
 * non-collapsed selection into a link to a new or existing roam node. Each
 * chip is outlined and labelled in its template's colour.
 */
@Composable
fun RoamNodeSuggestionStrip(
    templates: List<CaptureTemplate>,
    selectedText: String,
    /** Whether [selectedText] already names an existing roam node (see [AutoLinkSuggestion.titleLower]) -- flips the prompt from offering to create one to offering to link it. */
    matchesExistingNode: Boolean,
    expandedKeys: Set<String>,
    onToggleExpand: (String) -> Unit,
    onPick: (CaptureTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    LazyRow(
        modifier.fadingTrailingEdge(SuggestionFadeWidth),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = SuggestionStripPadding,
    ) {
        item {
            Text(
                stringResource(if (matchesExistingNode) R.string.editor_roam_link_prompt else R.string.editor_roam_create_prompt),
                fontFamily = PlexSans, fontSize = 13.5.sp, color = c.ink2,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        items(templates, key = { it.id }) { template ->
            val expanded = template.id in expandedKeys
            val truncated = selectedText.length >= 10
            val (fg, _) = monogramPalette(c, template.color ?: nameHashPaletteKey(template.id))
            SuggestionChip(
                label = "${template.name}: " + ellipsizeChipLabel(selectedText, expanded),
                borderColor = fg,
                labelColor = fg,
                onClick = {
                    if (truncated && !expanded) onToggleExpand(template.id) else onPick(template)
                },
            )
        }
    }
}
