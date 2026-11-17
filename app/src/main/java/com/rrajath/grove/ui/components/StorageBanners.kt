package com.rrajath.grove.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Amber notice about the vault's storage provider: a short [message] and an
 * optional trailing [actionLabel]. Same look as the other amber banners
 * ([ReminderPermissionBanner], the editor's stale-file banner): `amberSoft`
 * fill, 1dp `amber` border, 11dp corners, 10dp content padding, 14/6dp outer.
 * The Row mirrors in right-to-left layouts, so the action stays at the end.
 */
@Composable
fun StorageNoticeBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = MaterialTheme.grove
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(c.amberSoft)
            .border(1.dp, c.amber, RoundedCornerShape(11.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            Text(
                actionLabel,
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                color = c.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onAction)
                    .padding(6.dp),
            )
        }
    }
}
