package com.rrajath.grove.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.Pill
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.screens.settings.RowDivider
import com.rrajath.grove.ui.screens.settings.SectionLabel
import com.rrajath.grove.ui.screens.settings.SettingsGroup
import com.rrajath.grove.ui.screens.settings.SettingsRow
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.grove

private data class SettingsPage(
    val title: String,
    val description: String,
    val onClick: () -> Unit,
    val badge: (@Composable () -> Unit)? = null,
)

/**
 * Settings hub (design spec §11, split into one page per section): a plain
 * list of pages rather than one long scroll, so each destination (Look and
 * Feel, Capture Templates, etc.) is its own back-stack entry.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNotebooks: () -> Unit,
    onOpenCaptureTemplates: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenAgenda: () -> Unit,
    onOpenWidget: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenRoam: () -> Unit,
    onOpenAttachments: () -> Unit,
    onOpenSharing: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenBugReport: () -> Unit,
    onOpenTips: () -> Unit,
    onOpenWhatsNew: () -> Unit,
    onOpenDeveloper: () -> Unit,
    whatsNewHasUnseen: Boolean = false,
) {
    val c = MaterialTheme.grove
    val pages = listOfNotNull(
        SettingsPage(
            "Look and Feel",
            "Theme, app icon, text size",
            onClick = onOpenAppearance,
            badge = { NewDot(NewAnchors.SETTINGS_APPEARANCE) },
        ),
        SettingsPage(
            "Notebooks",
            "File icons, folder flattening, display names, sort order",
            onClick = onOpenNotebooks,
            badge = { NewDot(NewAnchors.SETTINGS_NOTEBOOKS) },
        ),
        SettingsPage(
            "Capture Templates",
            "Quick-capture targets and shortcuts",
            onClick = onOpenCaptureTemplates,
            badge = { NewDot(NewAnchors.SETTINGS_CAPTURE_TEMPLATES) },
        ),
        SettingsPage("Sync", "Folder, auto-sync, sync log", onOpenSync),
        SettingsPage(
            "Notes",
            "TODO keywords, priorities, note display",
            onClick = onOpenNotes,
            badge = { NewDot(NewAnchors.SETTINGS_NOTES) },
        ),
        SettingsPage(
            "Agenda",
            "Swipe actions on agenda rows, deadline warnings",
            onOpenAgenda,
            badge = { NewDot(NewAnchors.SETTINGS_AGENDA) },
        ),
        SettingsPage(
            "Widget",
            "Home-screen Agenda widget: content, transparency, size",
            onClick = onOpenWidget,
            badge = { NewDot(NewAnchors.SETTINGS_WIDGET) },
        ),
        SettingsPage(
            "Reminders",
            "Notifications for SCHEDULED/DEADLINE",
            onClick = onOpenReminders,
            badge = { NewDot(NewAnchors.SETTINGS_REMINDERS) },
        ),
        // Debug builds only, like every Roam surface (see SettingsRepository's load).
        if (!com.rrajath.grove.BuildConfig.DEBUG) null else SettingsPage(
            "Roam Features",
            "Backlinks and link suggestions while typing",
            onClick = onOpenRoam,
            badge = {
                Pill(text = "Experimental", fg = c.amber, bg = c.amberSoft)
                NewDot(NewAnchors.SETTINGS_ROAM, Modifier.padding(start = 6.dp))
            },
        ),
        SettingsPage(
            "Attachments",
            "Photo size, default image width",
            onClick = onOpenAttachments,
            badge = {
                Pill(text = "Preview", fg = c.amber, bg = c.amberSoft)
                NewDot(NewAnchors.SETTINGS_ATTACHMENTS, Modifier.padding(start = 6.dp))
            },
        ),
        SettingsPage(
            "Sharing",
            "Where shared content lands, link titles",
            onClick = onOpenSharing,
            badge = { NewDot(NewAnchors.SETTINGS_SHARING) },
        ),
        SettingsPage("Backup", "Export/import your preferences", onOpenBackup),
    )

    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                leading = { IconGlyph("←", onClick = onBack) },
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.ink,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SettingsGroup {
                pages.forEachIndexed { i, page ->
                    if (i > 0) RowDivider()
                    SettingsRow(
                        label = page.title,
                        description = page.description,
                        labelBadge = page.badge,
                        onClick = page.onClick,
                    ) {
                        Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel("HELP")
            SettingsGroup {
                SettingsRow(
                    label = "Report a bug",
                    description = "Send an email or create an issue on GitHub",
                    onClick = onOpenBugReport,
                ) {
                    Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                }
                RowDivider()
                SettingsRow(
                    label = "Tips & Tricks",
                    description = "Shortcuts and gestures hiding in the app",
                    labelBadge = { NewDot(NewAnchors.SETTINGS_TIPS) },
                    onClick = onOpenTips,
                ) {
                    Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel("ABOUT")
            SettingsGroup {
                SettingsRow(
                    label = "What's New",
                    description = "Changes in this and earlier releases",
                    labelBadge = if (whatsNewHasUnseen) ({ UnseenDot() }) else null,
                    onClick = onOpenWhatsNew,
                ) {
                    Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                }
                RowDivider()
                SettingsRow(
                    label = stringResource(R.string.app_name),
                    description = "Version ${com.rrajath.grove.BuildConfig.VERSION_NAME}",
                ) {
                    Text(
                        com.rrajath.grove.BuildConfig.APPLICATION_ID,
                        fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
                    )
                }
            }

            if (com.rrajath.grove.BuildConfig.DEBUG) {
                Spacer(Modifier.height(10.dp))
                SectionLabel("DEVELOPER")
                SettingsGroup {
                    SettingsRow(
                        label = "Developer tools",
                        description = "Reset New badges and other debug actions",
                        onClick = onOpenDeveloper,
                    ) {
                        Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The 7dp accent dot marking the What's New row while a release the user hasn't opened
 *  the screen for has shipped. Mirrors `ui/newbadge`'s NewDot, but is driven by the
 *  last-seen-changelog build rather than the NEW-feature registry. */
@Composable
private fun UnseenDot() {
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(MaterialTheme.grove.accent),
    )
}
