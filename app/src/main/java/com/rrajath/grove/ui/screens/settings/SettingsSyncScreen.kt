package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
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
import com.rrajath.grove.settings.GroveSettings
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
            val vaultUri = settings.vaultTreeUri
            SettingsRow(label = "Folder", onClick = { folderPicker.launch(null) }) {
                Text(
                    settings.vaultTreeUri?.let { uriDisplayName(it) } ?: "tap to choose",
                    fontFamily = PlexMono, fontSize = 12.sp, color = c.accent,
                )
            }
            // Full-width under the row, not in its label column (which the folder id
            // squeezes). Pulled 8dp up into the row's 14dp bottom padding, so it sits
            // 6dp under "Folder" and keeps a 14dp gap before the divider.
            if (vaultUri != null && com.rrajath.grove.vault.VaultProvider.isNonLocal(vaultUri)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 15.dp, end = 15.dp, bottom = 6.dp)
                        .offset(y = (-8).dp),
                ) {
                    com.rrajath.grove.ui.vault.ProviderWarningNote(vaultUri)
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
