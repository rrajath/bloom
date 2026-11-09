package com.rrajath.grove.ui.attach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.attach.AttachDirChange
import com.rrajath.grove.attach.AttachOwner
import com.rrajath.grove.attach.shownTarget
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * After an explicit save: which attach dirs a `:DIR:` edit moved (or orphaned).
 * OK is the only choice; dismissing counts as OK, so the files always follow.
 */
@Composable
fun AttachDirNoticeDialog(changes: List<AttachDirChange>, onOk: () -> Unit) {
    val c = MaterialTheme.grove
    AlertDialog(
        onDismissRequest = onOk,
        containerColor = c.surface,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("attach_dir_notice"),
        title = {
            Text(
                stringResource(R.string.attach_dir_notice_title),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = c.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                changes.forEach { change ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // Which heading (or file) it's about, when there's more than one.
                        if (changes.size > 1) {
                            Text(
                                ownerLabel(change),
                                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink,
                            )
                        }
                        Text(
                            message(change),
                            fontFamily = PlexSans, fontSize = 14.sp, lineHeight = 1.5.em, color = c.ink2,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOk, modifier = Modifier.testTag("attach_dir_notice_ok")) {
                Text("OK", fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.accent)
            }
        },
    )
}

private fun ownerLabel(change: AttachDirChange): String = when (val o = change.owner) {
    is AttachOwner.Heading -> o.title
    is AttachOwner.File -> change.orgFile.substringAfterLast('/')
}

@Composable
private fun message(change: AttachDirChange): String = when (change) {
    is AttachDirChange.Broken -> stringResource(R.string.attach_dir_broken)
    is AttachDirChange.Move -> stringResource(
        when (change.kind) {
            AttachDirChange.Kind.DIR_ADDED -> R.string.attach_dir_added
            AttachDirChange.Kind.DIR_REMOVED -> R.string.attach_dir_removed
            AttachDirChange.Kind.DIR_CHANGED -> R.string.attach_dir_changed
        },
        change.shownTarget(),
    )
}
