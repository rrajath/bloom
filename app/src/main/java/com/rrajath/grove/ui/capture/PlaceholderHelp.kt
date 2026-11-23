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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Every capture-template placeholder with a one-line explanation (design spec §7.6/§8).
 * The keys are template syntax and stay as written; the explanations are string resources.
 */
// i18n-begin: placeholder syntax, never translated
val PLACEHOLDER_DOCS: List<Pair<String, Int>> = listOf(
    "%T" to R.string.placeholder_doc_active_datetime,
    "%U" to R.string.placeholder_doc_inactive_datetime,
    "%t" to R.string.placeholder_doc_active_date,
    "%u" to R.string.placeholder_doc_inactive_date,
    "%date" to R.string.placeholder_doc_iso_date,
    "%time" to R.string.placeholder_doc_time,
    "%day" to R.string.placeholder_doc_day,
    "%month" to R.string.placeholder_doc_month,
    "%year" to R.string.placeholder_doc_year,
    "%clipboard" to R.string.placeholder_doc_clipboard,
    "%shared_text" to R.string.placeholder_doc_shared_text,
    "%shared_url" to R.string.placeholder_doc_shared_url,
    "%cursor / %?" to R.string.placeholder_doc_cursor,
    "%^{Prompt}" to R.string.placeholder_doc_prompt,
)
// i18n-end

/** Explains every supported placeholder; opened from the keyboard bar and template settings. */
@Composable
fun PlaceholderInfoDialog(onDismiss: () -> Unit) {
    val c = MaterialTheme.grove
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                stringResource(R.string.placeholder_help_title),
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
                            stringResource(desc),
                            fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok), color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
