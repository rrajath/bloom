package com.rrajath.grove.ui.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/** Every capture-template placeholder with a one-line explanation (design spec §7.6/§8). */
val PLACEHOLDER_DOCS = listOf(
    "%T" to "Active datetime stamp: <date time>",
    "%U" to "Inactive datetime stamp: [date time]",
    "%t" to "Active date stamp: <date>",
    "%u" to "Inactive date stamp: [date]",
    "%date" to "ISO date: YYYY-MM-DD",
    "%time" to "Current time: HH:MM",
    "%day" to "Day of week: Monday, …",
    "%month" to "Month name: January, …",
    "%year" to "4-digit year",
    "%clipboard" to "Clipboard contents",
    "%shared_text" to "Shared text",
    "%shared_url" to "Shared URL",
    "%cursor / %?" to "Place cursor here",
    "%^{Prompt}" to "Ask for user input",
)

/** Explains every supported placeholder; opened from the keyboard bar and template settings. */
@Composable
fun PlaceholderInfoDialog(onDismiss: () -> Unit) {
    val c = MaterialTheme.grove
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                "Placeholders",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, color = c.ink,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PLACEHOLDER_DOCS.forEach { (key, desc) ->
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            key,
                            fontFamily = PlexMono, fontSize = 12.sp, color = c.accent,
                            modifier = Modifier.width(120.dp),
                        )
                        Text(
                            desc,
                            fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
