package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.rrajath.grove.R
import com.rrajath.grove.search.QuickStartOverrides
import com.rrajath.grove.settings.Backup
import com.rrajath.grove.settings.BackupSection
import com.rrajath.grove.settings.sections
import com.rrajath.grove.ui.components.SyncedCheckIcon
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/** Settings § Backup. */
@Composable
fun SettingsBackupScreen(
    onBack: () -> Unit,
    onExportSettings: (android.net.Uri) -> Unit,
    onImportSettings: (android.net.Uri) -> Unit,
    /** A parsed backup waiting on the section checklist; null hides the dialog. */
    pendingImport: Backup? = null,
    onConfirmImport: (Set<BackupSection>) -> Unit = {},
    onCancelImport: () -> Unit = {},
) {
    val c = MaterialTheme.grove
    val settingsExporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) onExportSettings(uri) }

    val settingsImporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportSettings(uri) }

    SettingsPageScaffold(title = "Backup", onBack = onBack) {
        SettingsGroup {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    "Import or export your preferences, capture templates, favorites and saved"
                        + " searches as a JSON file. The sync folder isn't included; it stays on this device.",
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            RowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 14.dp),
            ) {
                // "Bloom Debug" -> "bloom-debug-backup.json"
                val backupFileName = stringResource(
                    R.string.backup_file_name,
                    stringResource(R.string.app_name).lowercase(java.util.Locale.ROOT).replace(' ', '-'),
                )
                BackupSecondaryButton(
                    label = stringResource(R.string.backup_export),
                    modifier = Modifier.weight(1f),
                    onClick = { settingsExporter.launch(backupFileName) },
                )
                Spacer(Modifier.width(10.dp))
                BackupSecondaryButton(
                    label = stringResource(R.string.backup_import),
                    modifier = Modifier.weight(1f),
                    // Providers report .json inconsistently; accept text-ish types too.
                    onClick = {
                        settingsImporter.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream")
                        )
                    },
                )
            }
        }
    }

    pendingImport?.let { backup ->
        ImportBackupDialog(backup, onConfirm = onConfirmImport, onDismiss = onCancelImport)
    }
}

/**
 * Which parts of a backup file to import: one checkbox per section the file has, all
 * checked to start. Each checked section replaces the device's copy outright.
 */
@Composable
private fun ImportBackupDialog(
    backup: Backup,
    onConfirm: (Set<BackupSection>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    val sections = remember(backup) { backup.sections() }
    var checked by remember(backup) { mutableStateOf(sections.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                "Import backup",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, color = c.ink,
            )
        },
        text = {
            Column {
                Text(
                    "Each checked item replaces what's on this device.",
                    fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
                )
                for (section in sections) {
                    val (label, detail) = sectionText(section, backup)
                    BackupSectionCheckbox(
                        label = label,
                        detail = detail,
                        checked = section in checked,
                        onChange = { on -> checked = if (on) checked + section else checked - section },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked) }, enabled = checked.isNotEmpty()) {
                Text(
                    "Import",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    color = if (checked.isNotEmpty()) c.accent else c.ink3,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = PlexSans, color = c.ink2)
            }
        },
    )
}

/** Row label and its count / summary line for one [section] of [backup]. */
private fun sectionText(section: BackupSection, backup: Backup): Pair<String, String> = when (section) {
    BackupSection.SETTINGS -> "Settings" to "Look and feel, notes, sync, agenda and other preferences"
    BackupSection.CAPTURE_TEMPLATES ->
        "Capture templates" to count(backup.captureTemplates.orEmpty().size, "template")
    BackupSection.FAVORITES -> "Favorites" to count(backup.favorites.orEmpty().size, "favorite")
    BackupSection.SAVED_SEARCHES -> {
        val all = backup.savedSearches.orEmpty()
        val cards = all.count { it.id in QuickStartOverrides.ids }
        val searches = count(all.size - cards, "saved search", "saved searches")
        "Saved searches & Quick Start cards" to
            if (cards == 0) searches else "$searches, ${count(cards, "customized Quick Start card")}"
    }
}

private fun count(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

/**
 * Checkbox row matching the attachments 1n row (22dp box, 6dp radius, 2dp border; label
 * 15sp Medium, detail 12.5sp `ink2`), in `accent` rather than `red` since nothing is deleted.
 */
@Composable
private fun BackupSectionCheckbox(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.grove
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(top = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(shape)
                .background(if (checked) c.accent else Color.Transparent)
                .border(2.dp, if (checked) c.accent else c.ink3, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) SyncedCheckIcon(contentDescription = null, modifier = Modifier.size(16.dp), tint = c.accentInk)
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink)
            Text(
                detail,
                fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Secondary button matching [com.rrajath.grove.ui.screens.ConflictScreen]'s `SecondaryButton` styling
 * (46dp height, 13dp corner radius, `surface` bg + 1dp `line` border,
 * PlexSans SemiBold 14sp `ink` text) for the paired Export/Import buttons.
 */
@Composable
private fun BackupSecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, color = c.ink,
        )
    }
}
