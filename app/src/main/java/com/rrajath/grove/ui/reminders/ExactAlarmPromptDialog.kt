package com.rrajath.grove.ui.reminders

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * One-time "Get reminders on time" dialog asking for exact-alarm access (see
 * [com.rrajath.grove.reminders.ExactAlarmPrompt] for when it shows). The app name is
 * never hardcoded: it comes from `R.string.app_name`, so debug builds name themselves.
 * Back / outside tap counts as [onNotNow].
 */
@Composable
fun ExactAlarmPromptDialog(onAllow: () -> Unit, onNotNow: () -> Unit) {
    val c = MaterialTheme.grove
    AlertDialog(
        onDismissRequest = onNotNow,
        containerColor = c.surface,
        title = {
            Text(
                stringResource(R.string.reminders_exact_prompt_title),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, color = c.ink,
            )
        },
        text = {
            Text(
                stringResource(R.string.exact_alarm_prompt_body, stringResource(R.string.app_name)),
                fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.reminders_exact_prompt_allow), fontFamily = PlexSans, color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text(stringResource(R.string.reminders_exact_prompt_not_now), fontFamily = PlexSans, color = c.ink2)
            }
        },
    )
}
