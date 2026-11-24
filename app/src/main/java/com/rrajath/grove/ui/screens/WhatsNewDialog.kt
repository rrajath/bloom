package com.rrajath.grove.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.whatsnew.ChangelogVersion

/**
 * Shown once on launch after an update, listing what changed since the version last seen.
 * Formatted the same way as CHANGELOG.md itself: a version label, then its Added/Fixed/etc.
 * subsections as bullet lists.
 */
@Composable
fun WhatsNewDialog(versions: List<ChangelogVersion>, onDismiss: () -> Unit) {
    val c = MaterialTheme.grove
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(stringResource(R.string.settings_page_whats_new_title), fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.ink)
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                versions.forEachIndexed { index, version ->
                    if (index > 0) Spacer(Modifier.height(16.dp))
                    Text(
                        if (version.versionCode != null) "v${version.title}" else stringResource(R.string.whats_new_latest_changes),
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.accent,
                    )
                    version.subsections.forEach { section ->
                        if (section.heading.isNotBlank()) {
                            Text(
                                section.heading,
                                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = c.ink2,
                                modifier = Modifier.padding(top = 10.dp, bottom = 3.dp),
                            )
                        } else {
                            Spacer(Modifier.height(7.dp))
                        }
                        section.items.forEach { item ->
                            Text(
                                "•  $item",
                                fontFamily = PlexSans, fontSize = 13.sp, color = c.ink,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK", color = c.accent, fontWeight = FontWeight.SemiBold) }
        },
    )
}
