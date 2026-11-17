package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.org.NotePlacement
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/** Settings § Sharing. */
@Composable
fun SettingsSharingScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onOpenShareTargetPicker: () -> Unit,
    onSetShareInsertPlacement: (NotePlacement) -> Unit,
    onSetFetchSharedLinkTitles: (Boolean) -> Unit,
) {
    val c = MaterialTheme.grove
    val target = shareTargetBreadcrumb(settings)
    val toHeading = settings.shareTargetHeadingPath.isNotEmpty()

    SettingsPageScaffold(title = "Sharing", onBack = onBack) {
        // Leaving this screen retires these rows' NEW dots from their whole trail.
        MarkNewFeatureSeen(NewAnchors.SETTINGS_SHARING_FETCH_TITLES)
        MarkNewFeatureSeen(NewAnchors.SETTINGS_SHARING_PLACEMENT)

        SettingsGroup {
            SettingsRow(
                label = "Shared content target",
                description = "The file or heading that receives links and text shared into " +
                        stringResource(R.string.app_name),
                onClick = onOpenShareTargetPicker,
            ) {
                Text(
                    target,
                    fontFamily = PlexMono, fontSize = 12.sp, color = c.accent,
                    // Wraps instead of truncating, so a deep heading path shows in full;
                    // start-aligned so wrapped lines line up under the file name.
                    textAlign = TextAlign.Start,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
            RowDivider()
            SettingsRow(
                label = "Add shared items at",
                descriptionContent = {
                    Text(
                        placementDescription(target, toHeading, settings.shareInsertPlacement),
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    )
                },
                labelBadge = { NewDot(NewAnchors.SETTINGS_SHARING_PLACEMENT) },
            ) {
                SegmentedControl(
                    options = listOf("Top", "Bottom"),
                    selectedIndex = PLACEMENT_OPTIONS.indexOf(settings.shareInsertPlacement),
                    onSelect = { onSetShareInsertPlacement(PLACEMENT_OPTIONS[it]) },
                    modifier = Modifier.width(160.dp),
                )
            }
            RowDivider()
            ToggleRow(
                label = "Fetch link titles",
                description = "Looks up a shared link's page title online. Off makes no network " +
                        "request; the link uses any title the sharing app sent, or the URL",
                checked = settings.fetchSharedLinkTitles,
                labelBadge = { NewDot(NewAnchors.SETTINGS_SHARING_FETCH_TITLES) },
                onToggle = onSetFetchSharedLinkTitles,
            )
        }
    }
}

/** Segment order of the "Add shared items at" control. */
private val PLACEMENT_OPTIONS = listOf(NotePlacement.TOP, NotePlacement.BOTTOM)

/** "inbox › Projects › Reading": the share target as [com.rrajath.grove.capture.ShareIntake] resolves it. */
internal fun shareTargetBreadcrumb(settings: GroveSettings): String {
    val file = settings.shareTargetFile.trim().ifBlank { GroveSettings.DEFAULT_SHARE_TARGET }
    val headings = settings.shareTargetHeadingPath.split('/').filter { it.isNotEmpty() }
    return (listOf(file.removeSuffix(".org")) + headings).joinToString(" › ")
}

/**
 * The "Add shared items at" description: where in the target items land, then
 * the target breadcrumb in PlexMono. A heading target's "first"/"last" is SemiBold (PlexSans has no Bold face).
 */
internal fun placementDescription(target: String, toHeading: Boolean, placement: NotePlacement): AnnotatedString {
    val top = placement == NotePlacement.TOP
    return buildAnnotatedString {
        if (toHeading) {
            append("Shared items are added as the ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(if (top) "first" else "last") }
            append(" subheading of ")
        } else {
            append("Shared items are added as a new heading at the ${if (top) "top" else "bottom"} of ")
        }
        withStyle(SpanStyle(fontFamily = PlexMono)) { append(target) }
    }
}
