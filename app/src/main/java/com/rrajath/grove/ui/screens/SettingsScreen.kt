package com.rrajath.grove.ui.screens

import androidx.annotation.StringRes
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
import com.rrajath.grove.ui.util.backArrowGlyph

private data class SettingsPage(
    @StringRes val title: Int,
    @StringRes val description: Int,
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
            R.string.settings_page_appearance_title,
            R.string.settings_page_appearance_summary,
            onClick = onOpenAppearance,
            badge = { NewDot(NewAnchors.SETTINGS_APPEARANCE) },
        ),
        SettingsPage(
            R.string.settings_page_notebooks_title,
            R.string.settings_page_notebooks_summary,
            onClick = onOpenNotebooks,
            badge = { NewDot(NewAnchors.SETTINGS_NOTEBOOKS) },
        ),
        SettingsPage(
            R.string.settings_page_capture_templates_title,
            R.string.settings_page_capture_templates_summary,
            onClick = onOpenCaptureTemplates,
            badge = { NewDot(NewAnchors.SETTINGS_CAPTURE_TEMPLATES) },
        ),
        SettingsPage(R.string.settings_page_sync_title, R.string.settings_page_sync_summary, onOpenSync),
        SettingsPage(
            R.string.settings_page_notes_title,
            R.string.settings_page_notes_summary,
            onClick = onOpenNotes,
            badge = { NewDot(NewAnchors.SETTINGS_NOTES) },
        ),
        SettingsPage(
            R.string.settings_page_agenda_title,
            R.string.settings_page_agenda_summary,
            onOpenAgenda,
            badge = { NewDot(NewAnchors.SETTINGS_AGENDA) },
        ),
        SettingsPage(
            R.string.settings_page_widget_title,
            R.string.settings_page_widget_summary,
            onClick = onOpenWidget,
            badge = { NewDot(NewAnchors.SETTINGS_WIDGET) },
        ),
        SettingsPage(
            R.string.settings_page_reminders_title,
            R.string.settings_page_reminders_summary,
            onClick = onOpenReminders,
            badge = { NewDot(NewAnchors.SETTINGS_REMINDERS) },
        ),
        // Debug and perf builds only, like every Roam surface (see SettingsRepository's load).
        if (!com.rrajath.grove.BuildConfig.ROAM_FEATURES) null else SettingsPage(
            R.string.settings_page_roam_title,
            R.string.settings_page_roam_summary,
            onClick = onOpenRoam,
            badge = {
                Pill(text = stringResource(R.string.settings_badge_experimental), fg = c.amber, bg = c.amberSoft)
                NewDot(NewAnchors.SETTINGS_ROAM, Modifier.padding(start = 6.dp))
            },
        ),
        SettingsPage(
            R.string.settings_page_attachments_title,
            R.string.settings_page_attachments_summary,
            onClick = onOpenAttachments,
            badge = {
                Pill(text = stringResource(R.string.settings_badge_preview), fg = c.amber, bg = c.amberSoft)
                NewDot(NewAnchors.SETTINGS_ATTACHMENTS, Modifier.padding(start = 6.dp))
            },
        ),
        SettingsPage(
            R.string.settings_page_sharing_title,
            R.string.settings_page_sharing_summary,
            onClick = onOpenSharing,
            badge = { NewDot(NewAnchors.SETTINGS_SHARING) },
        ),
        SettingsPage(R.string.settings_page_backup_title, R.string.settings_page_backup_summary, onOpenBackup),
    )

    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                leading = { IconGlyph(backArrowGlyph(), onClick = onBack) },
                title = {
                    Text(
                        stringResource(R.string.nav_settings),
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
                        label = stringResource(page.title),
                        description = stringResource(page.description),
                        labelBadge = page.badge,
                        onClick = page.onClick,
                    ) {
                        Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel(stringResource(R.string.settings_section_help))
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.settings_page_bug_report_title),
                    description = stringResource(R.string.settings_page_bug_report_summary),
                    onClick = onOpenBugReport,
                ) {
                    Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                }
                RowDivider()
                SettingsRow(
                    label = stringResource(R.string.settings_page_tips_title),
                    description = stringResource(R.string.settings_page_tips_summary),
                    labelBadge = { NewDot(NewAnchors.SETTINGS_TIPS) },
                    onClick = onOpenTips,
                ) {
                    Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel(stringResource(R.string.settings_section_about))
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.settings_page_whats_new_title),
                    description = stringResource(R.string.settings_page_whats_new_summary),
                    labelBadge = if (whatsNewHasUnseen) ({ UnseenDot() }) else null,
                    onClick = onOpenWhatsNew,
                ) {
                    Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                }
                RowDivider()
                SettingsRow(
                    label = stringResource(R.string.app_name),
                    description = stringResource(R.string.settings_about_version, com.rrajath.grove.BuildConfig.VERSION_NAME),
                ) {
                    Text(
                        com.rrajath.grove.BuildConfig.APPLICATION_ID,
                        fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
                    )
                }
            }

            if (com.rrajath.grove.BuildConfig.DEBUG) {
                Spacer(Modifier.height(10.dp))
                SectionLabel(stringResource(R.string.settings_section_developer))
                SettingsGroup {
                    SettingsRow(
                        label = stringResource(R.string.settings_page_developer_title),
                        description = stringResource(R.string.settings_page_developer_summary),
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
