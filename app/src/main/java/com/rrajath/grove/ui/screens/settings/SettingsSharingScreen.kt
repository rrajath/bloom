package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
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
            RowDivider()
            SettingsRow(
                label = "Add shared items at",
                description = placementDescription(target, toHeading, settings.shareInsertPlacement),
                labelBadge = { NewDot(NewAnchors.SETTINGS_SHARING_PLACEMENT) },
            ) {
                SegmentedControl(
                    options = listOf("Top", "Bottom"),
                    selectedIndex = PLACEMENT_OPTIONS.indexOf(settings.shareInsertPlacement),
                    onSelect = { onSetShareInsertPlacement(PLACEMENT_OPTIONS[it]) },
                    modifier = Modifier.width(160.dp),
                )
            }
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

/** The "Add shared items at" description: names the target and where in it items land. */
internal fun placementDescription(target: String, toHeading: Boolean, placement: NotePlacement): String {
    val top = placement == NotePlacement.TOP
    return if (toHeading) {
        "Shared items are added as the ${if (top) "first" else "last"} subheading of $target"
    } else {
        "Shared items are added as a new heading at the ${if (top) "top" else "bottom"} of $target"
    }
}
