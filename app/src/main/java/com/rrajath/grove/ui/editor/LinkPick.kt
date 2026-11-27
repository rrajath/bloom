package com.rrajath.grove.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgHeadline
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * A picked link target that carries an ID: the "Use the …'s ID?" dialog lets the
 * user commit either [withId] (resilient) or [withPlain]. [subject] drives the dialog copy.
 */
internal data class LinkIdChoice(val withId: String, val withPlain: String, val subject: LinkSubject)

/** What an ID link would point at: a whole file or one heading. */
internal enum class LinkSubject { FILE, HEADING }

/** What a link-picker confirmation resolves to: a link ready to splice, or an ID choice to ask about. */
internal sealed interface LinkPick {
    data class Direct(val link: String) : LinkPick
    data class AskId(val choice: LinkIdChoice) : LinkPick
}

/**
 * Turn the picked [file] (top level, [heading] null) or [heading] into a link
 * from [editingFile]. [selectedText] becomes the description; with nothing
 * selected the link names its target (the heading title, or the file's
 * `#+TITLE:` / base name). Shared by every editor that hosts the link picker.
 */
internal fun resolveLinkPick(
    editingFile: String,
    doc: OrgDocument,
    file: String,
    heading: OrgHeadline?,
    selectedText: String?,
): LinkPick {
    val desc = selectedText ?: if (heading != null) {
        heading.title
    } else {
        doc.fileTitle?.takeIf { it.isNotBlank() }
            ?: file.substringAfterLast('/').removeSuffix(".org")
    }
    if (heading == null) {
        // File-level link. A file:-link is always relative to the editing
        // file's directory (just the name for a link into the same file).
        val relPath = relativeOrgPath(editingFile, file)
        val fileId = doc.fileId ?: return LinkPick.Direct(formatFileLink(relPath, desc))
        return LinkPick.AskId(
            LinkIdChoice(
                withId = formatHeadingLink(HeadingLinkTarget.ById(fileId), desc),
                withPlain = formatFileLink(relPath, desc),
                subject = LinkSubject.FILE,
            ),
        )
    }
    // Heading link. Drop the file: qualifier when the heading is in this note.
    val relPath = if (file == editingFile) null else relativeOrgPath(editingFile, file)
    val plain = formatHeadingLink(HeadingLinkTarget.ByName(heading.title, relPath), desc)
    if (heading.id == null && heading.customId == null) return LinkPick.Direct(plain)
    return LinkPick.AskId(
        LinkIdChoice(
            withId = formatHeadingLink(resilientHeadingTarget(heading.id, heading.customId, relPath), desc),
            withPlain = plain,
            subject = LinkSubject.HEADING,
        ),
    )
}

@Composable
internal fun LinkIdChoiceDialog(
    choice: LinkIdChoice,
    onUseId: () -> Unit,
    onUsePlain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                stringResource(
                    if (choice.subject == LinkSubject.FILE) R.string.editor_link_id_file_title
                    else R.string.editor_link_id_heading_title,
                ),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, color = c.ink,
            )
        },
        text = {
            Text(
                stringResource(
                    if (choice.subject == LinkSubject.FILE) R.string.editor_link_id_file_body
                    else R.string.editor_link_id_heading_body,
                ),
                fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onUseId) {
                Text(stringResource(R.string.editor_link_use_id), color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onUsePlain) {
                Text(
                    stringResource(
                        if (choice.subject == LinkSubject.FILE) R.string.editor_link_use_file_link
                        else R.string.editor_link_use_heading_name,
                    ),
                    color = c.ink2,
                )
            }
        },
    )
}
