package com.rrajath.grove.ui.screens.settings

import androidx.compose.ui.res.pluralStringResource
import androidx.annotation.PluralsRes
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/** What Settings > Backup shows for the automatic backup; built from [com.rrajath.grove.settings.GroveSettings]. */
data class AutoBackupUiState(
    val enabled: Boolean = false,
    val treeUri: String? = null,
    val lastSuccessAt: Long? = null,
    val error: Boolean = false,
    /** Whether `bloom-settings.json` exists in [treeUri]; gates Restore. */
    val fileExists: Boolean = false,
)

internal const val AUTO_BACKUP_STATUS_TAG = "auto_backup_status"
internal const val AUTO_BACKUP_RESTORE_TAG = "auto_backup_restore"

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
    autoBackup: AutoBackupUiState = AutoBackupUiState(),
    /** The vault folder, where the backup-folder picker opens when no backup folder is set yet. */
    vaultTreeUri: String? = null,
    onSetAutoBackupEnabled: (Boolean) -> Unit = {},
    /** A folder the user picked, its read/write grant already persisted. Turns the backup on. */
    onAutoBackupFolderPicked: (String) -> Unit = {},
    onRestoreAutoBackup: () -> Unit = {},
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val c = MaterialTheme.grove
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsExporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) onExportSettings(uri) }

    val settingsImporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportSettings(uri) }

    // Bumped when the picker comes back empty: re-keys the toggle so a switch flipped on
    // by the tap that opened the picker slides back off.
    var pickerCancels by remember { mutableIntStateOf(0) }
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pickerCancels++
        } else {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onAutoBackupFolderPicked(uri.toString())
        }
    }
    val openPicker = { folderPicker.launch(pickerStart(autoBackup.treeUri ?: vaultTreeUri)) }

    SettingsPageScaffold(title = stringResource(R.string.settings_page_backup_title), onBack = onBack) {
        SectionLabel(stringResource(R.string.settings_backup_section_auto))
        SettingsGroup {
            key(pickerCancels) {
                ToggleRow(spec = SettingSpecs.BackupAuto, checked = autoBackup.enabled) { on ->
                    if (on && autoBackup.treeUri == null) openPicker() else onSetAutoBackupEnabled(on)
                }
            }
            RowDivider()
            SettingsRow(
                spec = SettingSpecs.BackupFolder,
                onClick = openPicker,
                enabled = autoBackup.enabled,
            ) {
                Text(
                    autoBackup.treeUri?.let { uriDisplayName(it) } ?: stringResource(R.string.settings_auto_backup_folder_none),
                    fontFamily = PlexMono, fontSize = 12.sp,
                    color = if (autoBackup.enabled) c.accent else c.ink3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
            RowDivider()
            AutoBackupStatusLine(autoBackup, nowMillis, onErrorTap = openPicker)
            RowDivider()
            Box(
                Modifier
                    .settingAnchor(SettingSpecs.BackupRestore)
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 14.dp),
            ) {
                BackupSecondaryButton(
                    label = stringResource(R.string.settings_auto_backup_restore),
                    enabled = autoBackup.enabled && autoBackup.treeUri != null && autoBackup.fileExists,
                    modifier = Modifier.fillMaxWidth().testTag(AUTO_BACKUP_RESTORE_TAG),
                    onClick = onRestoreAutoBackup,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.settings_backup_section_manual))
        SettingsGroup {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.settings_backup_intro),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            RowDivider()
            Row(
                Modifier
                    .settingAnchor(SettingSpecs.BackupFile)
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

/** Where the folder picker opens: [treeUri]'s own folder, or the picker's default when null. */
private fun pickerStart(treeUri: String?): android.net.Uri? = treeUri?.let {
    runCatching {
        val tree = android.net.Uri.parse(it)
        android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
        )
    }.getOrNull()
}

/**
 * The automatic backup's last outcome: a check glyph + "Last backed up 2 minutes ago",
 * "Not backed up yet", or the `red` "can't write" line that re-opens the picker. All `ink3`
 * (and not tappable) while the toggle is off.
 */
@Composable
private fun AutoBackupStatusLine(state: AutoBackupUiState, nowMillis: () -> Long, onErrorTap: () -> Unit) {
    val c = MaterialTheme.grove
    val tappable = state.error && state.enabled
    val text = when {
        state.error -> stringResource(R.string.settings_auto_backup_error, stringResource(R.string.app_name))
        state.lastSuccessAt != null -> lastBackedUp(state.lastSuccessAt, nowMillis())
        else -> stringResource(R.string.settings_auto_backup_never)
    }
    Row(
        Modifier
            .testTag(AUTO_BACKUP_STATUS_TAG)
            .fillMaxWidth()
            .then(if (tappable) Modifier.clickable(onClick = onErrorTap) else Modifier)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.error && state.lastSuccessAt != null) {
            SyncedCheckIcon(contentDescription = null, modifier = Modifier.size(14.dp), tint = c.ink3)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            fontFamily = PlexSans, fontSize = 12.sp,
            color = if (tappable) c.red else c.ink3,
        )
    }
}

@Composable
private fun lastBackedUp(at: Long, now: Long): String =
    if (now - at < android.text.format.DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.settings_auto_backup_last_just_now)
    } else {
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(
            at, now, android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        stringResource(R.string.settings_auto_backup_last, ago)
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
                stringResource(R.string.settings_backup_import_title),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, color = c.ink,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_backup_import_body),
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
                    stringResource(R.string.backup_import),
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    color = if (checked.isNotEmpty()) c.accent else c.ink3,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), fontFamily = PlexSans, color = c.ink2)
            }
        },
    )
}

/** Row label and its count / summary line for one [section] of [backup]. */
@Composable
private fun sectionText(section: BackupSection, backup: Backup): Pair<String, String> = when (section) {
    BackupSection.SETTINGS ->
        stringResource(R.string.settings_backup_section_settings) to stringResource(R.string.settings_backup_section_settings_detail)
    BackupSection.CAPTURE_TEMPLATES ->
        stringResource(R.string.settings_backup_section_templates) to
            count(R.plurals.settings_backup_count_templates, backup.captureTemplates.orEmpty().size)
    BackupSection.FAVORITES ->
        stringResource(R.string.settings_backup_section_favorites) to
            count(R.plurals.settings_backup_count_favorites, backup.favorites.orEmpty().size)
    BackupSection.SAVED_SEARCHES -> {
        val all = backup.savedSearches.orEmpty()
        val cards = all.count { it.id in QuickStartOverrides.ids }
        val searches = count(R.plurals.settings_backup_count_searches, all.size - cards)
        stringResource(R.string.settings_backup_section_searches) to
            if (cards == 0) searches
            else stringResource(R.string.settings_backup_searches_and_cards, searches, count(R.plurals.settings_backup_count_cards, cards))
    }
}

@Composable
private fun count(@PluralsRes id: Int, n: Int): String = pluralStringResource(id, n, n)

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
 * PlexSans SemiBold 14sp `ink` text) for the paired Export/Import buttons and the full-width Restore.
 */
@Composable
private fun BackupSecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    /** False: label in `ink3`, taps ignored. */
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, color = if (enabled) c.ink else c.ink3,
        )
    }
}
