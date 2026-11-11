package com.rrajath.grove.ui.attach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.rrajath.grove.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.attach.AttachmentInventory
import com.rrajath.grove.attach.AttachConfig
import com.rrajath.grove.attach.attachDirLabel
import com.rrajath.grove.attach.folderLabel
import com.rrajath.grove.attach.inventoryDetail
import com.rrajath.grove.attach.moveSubline
import com.rrajath.grove.ui.components.SyncedCheckIcon
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.pluralCount

/**
 * 1l: moving a notebook that owns attachment folders into another directory.
 * [onMove] gets true for "Move attachments" (the default), false for
 * "Move notebook only".
 */
@Composable
fun NotebookMoveAttachmentsSheet(
    fileName: String,
    newDir: String,
    inventory: AttachmentInventory,
    /** Names the attach folders in the choice cards. */
    config: AttachConfig,
    onMove: (moveAttachments: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    val leaf = fileName.substringAfterLast('/')
    val sourceDir = fileName.substringBeforeLast('/', "")
    val target = newDir.trim('/').let { if (it.isEmpty()) leaf else "$it/$leaf" }
    var moveAttachments by rememberSaveable { mutableStateOf(true) }
    LifecycleSheet(onDismiss, Modifier.testTag("attach_move_sheet")) {
        Text(
            "This notebook has attachments. Move them too?",
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.ink,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            moveSubline(leaf, newDir, inventory),
            fontFamily = PlexMono, fontSize = 12.5.sp, color = c.ink2,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 14.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceCard(
                selected = moveAttachments,
                title = "Move attachments",
                body = moveAttachmentsBody(attachDirLabel(target, config), folderLabel(sourceDir), inventory.sharedFolders),
                modifier = Modifier.testTag("attach_move_choice_move"),
            ) { moveAttachments = true }
            ChoiceCard(
                selected = !moveAttachments,
                title = "Move notebook only",
                body = "Attachments stay in ${attachDirLabel(fileName, config)}. Links in $leaf will show as missing.",
                modifier = Modifier.testTag("attach_move_choice_only"),
            ) { moveAttachments = false }
        }
        if (inventory.dirProperties.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    append("Not moved either way: ")
                    inventory.dirProperties.forEachIndexed { i, dir ->
                        if (i > 0) append("\n")
                        withStyle(SpanStyle(fontFamily = PlexMono)) { append(":DIR: $dir") }
                    }
                },
                fontFamily = PlexSans, fontSize = 12.sp, lineHeight = 18.sp, color = c.ink3,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            )
        }
        SheetButtons(
            confirmLabel = "Move",
            confirmColor = c.accent,
            confirmWeight = 1.4f,
            confirmFontSize = 14.5f,
            confirmTag = "attach_move_confirm",
            onCancel = onDismiss,
        ) { onMove(moveAttachments) }
    }
}

/** `Folders move into Archive/data/ and merge with what's there. 2 folders shared with notes in Notes/ are copied instead.` */
internal fun moveAttachmentsBody(destData: String, sourceFolder: String, shared: Int): String {
    val base = "Folders move into $destData and merge with what's there."
    if (shared == 0) return base
    val verb = if (shared == 1) "is" else "are"
    return "$base ${pluralCount(shared, "folder")} shared with notes in $sourceFolder $verb copied instead."
}

/**
 * 1n: deleting a notebook that owns attachment folders. "Also delete
 * attachments" starts checked; the button label follows the checkbox.
 */
@Composable
fun NotebookDeleteAttachmentsSheet(
    fileName: String,
    inventory: AttachmentInventory,
    onDelete: (deleteAttachments: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    var deleteAttachments by rememberSaveable { mutableStateOf(true) }
    LifecycleSheet(onDismiss, Modifier.testTag("attach_delete_notebook_sheet")) {
        Text(
            buildAnnotatedString {
                append("Delete ")
                withStyle(SpanStyle(fontFamily = PlexMono, fontSize = 17.sp)) { append(fileName.substringAfterLast('/')) }
                append("?")
            },
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = c.ink,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            "Deleting is permanent. ${stringResource(R.string.app_name)} removes files directly " +
                "and there's no trash to restore from.",
            fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 19.5.sp, color = c.ink,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.redSoft)
                .padding(horizontal = 13.dp, vertical = 11.dp),
        )
        DeleteAttachmentsCheckbox(deleteAttachments, deleteDetail(inventory)) { deleteAttachments = it }
        SheetButtons(
            confirmLabel = if (deleteAttachments) "Delete notebook and attachments" else "Delete notebook",
            confirmColor = c.red,
            confirmWeight = 2f,
            confirmFontSize = 14f,
            confirmTag = "attach_delete_confirm",
            onCancel = onDismiss,
        ) { onDelete(deleteAttachments) }
    }
}

/**
 * The Outline's heading delete when the selected subtrees own attachment
 * folders (design 10.4, built from the 1n pieces). No permanence box: the
 * heading delete is undoable. "Also delete attachments" starts unchecked since
 * that part isn't.
 */
@Composable
fun HeadingDeleteAttachmentsSheet(
    title: String?,
    count: Int,
    inventory: AttachmentInventory,
    onDelete: (deleteAttachments: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    var deleteAttachments by rememberSaveable { mutableStateOf(false) }
    LifecycleSheet(onDismiss, Modifier.testTag("attach_delete_heading_sheet")) {
        Text(
            if (count > 1 || title == null) "Delete $count notes?" else "Delete “$title”?",
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = c.ink,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        DeleteAttachmentsCheckbox(deleteAttachments, deleteDetail(inventory)) { deleteAttachments = it }
        SheetButtons(
            confirmLabel = when {
                !deleteAttachments -> "Delete"
                count > 1 -> "Delete notes and attachments"
                else -> "Delete note and attachments"
            },
            confirmColor = c.red,
            confirmWeight = 2f,
            confirmFontSize = 14f,
            confirmTag = "attach_delete_confirm",
            onCancel = onDismiss,
        ) { onDelete(deleteAttachments) }
    }
}

/** `6 folders · 23 files · 41 MB. 2 folders shared with other notes are kept.` */
internal fun deleteDetail(inventory: AttachmentInventory): String {
    val shared = inventory.sharedFolders
    val base = inventoryDetail(inventory)
    if (shared == 0) return base
    val verb = if (shared == 1) "is" else "are"
    return "$base. ${pluralCount(shared, "folder")} shared with other notes $verb kept."
}

/** Shared 1l/1n sheet frame: `surface`, 24dp top corners, 38×4dp `line2` handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LifecycleSheet(onDismiss: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.grove
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            // 10dp sheet top padding + 6dp handle margin, 16dp below.
            Box(
                Modifier
                    .padding(top = 16.dp, bottom = 16.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(c.line2),
            )
        },
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            content = content,
        )
    }
}

/** 1l choice card: radio + 15sp title + 12.5sp body; selected is `accentSoft` with a 1.5dp `accent` border. */
@Composable
private fun ChoiceCard(
    selected: Boolean,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.accentSoft else Color.Transparent)
            .border(1.5.dp, if (selected) c.accent else c.line2, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 20dp ring (2dp border); selected fills it, leaving a 3dp `surface` gap.
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(20.dp)
                .border(2.dp, if (selected) c.accent else c.ink3, CircleShape)
                .padding(5.dp)
                .clip(CircleShape)
                .background(if (selected) c.accent else Color.Transparent),
        )
        Column {
            Text(title, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink)
            Text(
                body,
                fontFamily = PlexSans, fontSize = 12.5.sp, lineHeight = 18.75.sp, color = c.ink2,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** 1n checkbox row: 22dp box (6dp radius, 2dp border); checked is a `red` fill with the Material check icon. */
@Composable
internal fun DeleteAttachmentsCheckbox(checked: Boolean, detail: String, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.grove
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 6.dp)
            .testTag("attach_delete_checkbox"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(shape)
                .background(if (checked) c.red else Color.Transparent)
                .border(2.dp, if (checked) c.red else c.ink3, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                SyncedCheckIcon(contentDescription = null, modifier = Modifier.size(16.dp), tint = c.accentInk)
            }
        }
        Column {
            Text(
                "Also delete attachments",
                fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink,
            )
            Text(
                detail,
                fontFamily = PlexSans, fontSize = 12.5.sp, lineHeight = 18.75.sp, color = c.ink2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Cancel (outlined `line2`) and the filled confirm button, 48dp tall pills.
 * The confirm label may wrap to two lines (1n's long label at 360dp).
 */
@Composable
private fun SheetButtons(
    confirmLabel: String,
    confirmColor: Color,
    confirmWeight: Float,
    confirmFontSize: Float,
    confirmTag: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = MaterialTheme.grove
    val shape = RoundedCornerShape(24.dp)
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetButton(
            "Cancel",
            Modifier
                .clip(shape)
                .border(1.dp, c.line2, shape)
                .clickable(onClick = onCancel),
            c.ink, 14.5f, 1f,
        )
        SheetButton(
            confirmLabel,
            Modifier
                .clip(shape)
                .background(confirmColor)
                .clickable(onClick = onConfirm)
                .testTag(confirmTag),
            c.accentInk, confirmFontSize, confirmWeight,
        )
    }
}

@Composable
private fun RowScope.SheetButton(label: String, modifier: Modifier, color: Color, fontSize: Float, weight: Float) {
    Box(
        Modifier
            .weight(weight)
            .heightIn(min = 48.dp)
            .then(modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = fontSize.sp, color = color,
            textAlign = TextAlign.Center, lineHeight = (fontSize * 1.25f).sp,
        )
    }
}
