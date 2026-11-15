package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rrajath.grove.R
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.vault.storage.VaultCapabilities
import com.rrajath.grove.settings.SyncMode
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/** Settings § Sync. */
@Composable
fun SettingsSyncScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetSyncMode: (SyncMode) -> Unit,
    onSetPeriodicMinutes: (Int) -> Unit,
    onOpenSyncLog: () -> Unit,
    onSetVaultUri: (String) -> Unit,
    /** The vault's provider and what it allows; null hides the Provider row (no vault yet). */
    provider: VaultCapabilities? = null,
) {
    val c = MaterialTheme.grove
    val context = androidx.compose.ui.platform.LocalContext.current
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            onSetVaultUri(uri.toString())
        }
    }

    SettingsPageScaffold(title = "Sync", onBack = onBack) {
        SettingsGroup {
            SettingsRow(label = "Folder", onClick = { folderPicker.launch(null) }) {
                Text(
                    settings.vaultTreeUri?.let { uriDisplayName(it) } ?: "tap to choose",
                    fontFamily = PlexMono, fontSize = 12.sp, color = c.accent,
                )
            }
            if (provider != null && settings.vaultTreeUri != null) {
                RowDivider()
                // Read-only: what the provider allows is discovered, never a setting.
                SettingsRow(
                    label = stringResource(R.string.settings_sync_provider),
                    description = providerSummary(LocalContext.current, provider),
                ) {
                    Text(
                        stringResource(provider.profile.displayName),
                        fontFamily = PlexMono, fontSize = 12.sp, color = c.ink2,
                    )
                }
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(
                    "Auto-sync",
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SyncMode.entries.forEach { mode ->
                    val active = settings.syncMode == mode
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) c.accentSoft else c.surface)
                            .clickable { onSetSyncMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(
                            mode.label,
                            fontFamily = PlexSans,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.5.sp,
                            color = if (active) c.accent else c.ink,
                        )
                    }
                }
            }
            if (settings.syncMode == SyncMode.PERIODIC) {
                RowDivider()
                SettingsRow(label = "Interval") {
                    SegmentedControl(
                        options = listOf("15m", "30m", "60m"),
                        selectedIndex = when (settings.periodicSyncMinutes) {
                            15 -> 0
                            60 -> 2
                            else -> 1
                        },
                        onSelect = { onSetPeriodicMinutes(listOf(15, 30, 60)[it]) },
                        modifier = Modifier.width(180.dp),
                    )
                }
            }
            RowDivider()
            SettingsRow(label = "View sync log", onClick = onOpenSyncLog) {
                Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
            }
        }
    }
}

/**
 * One line on what the vault's provider allows across its files: "Rename,
 * move and delete all work", or which of them it rules out. Move counts as
 * working when the file can be copied and then deleted.
 */
internal fun providerSummary(context: Context, provider: VaultCapabilities): String {
    val files = provider.allFiles
    val missing = buildList {
        if (!files.rename) add(context.getString(R.string.storage_verb_rename))
        if (!files.canRelocate) add(context.getString(R.string.storage_verb_move))
        if (!files.delete) add(context.getString(R.string.storage_verb_delete))
    }
    if (missing.isEmpty()) return context.getString(R.string.settings_sync_provider_full)
    val verbs = if (missing.size == 1) {
        missing.single()
    } else {
        missing.dropLast(1).joinToString(", ") + " " + context.getString(R.string.storage_verb_or) + " " + missing.last()
    }
    return context.getString(R.string.settings_sync_provider_limited, verbs)
}

