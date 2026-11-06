package com.rrajath.grove.ui.editor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import com.rrajath.grove.R
import com.rrajath.grove.attach.AttachKind
import com.rrajath.grove.attach.AttachOwner
import com.rrajath.grove.attach.ScopedAttachment
import com.rrajath.grove.attach.AttachmentCategory
import com.rrajath.grove.attach.CopyProgress
import com.rrajath.grove.attach.attachmentExtension
import com.rrajath.grove.attach.badgeFor
import com.rrajath.grove.attach.categoryOf
import com.rrajath.grove.attach.formatAttachmentSize
import com.rrajath.grove.attach.formatCopyBytes
import com.rrajath.grove.ui.attach.attachmentMemoryCacheKey
import com.rrajath.grove.ui.components.GroveProgressSnackbar
import com.rrajath.grove.ui.components.GroveToast
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.OutlineToast
import com.rrajath.grove.vault.FileEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Everything attach draws over the editor: the sheet, the delete dialog, the
 * progress snackbar and the toasts. Call inside the editor's content Box;
 * [bottomInset] is the height of whatever sits at the Box's bottom edge (the
 * suggestion strip while typing), so the snackbar floats 10dp above it.
 */
@Composable
fun BoxScope.AttachOverlays(controller: AttachController, bottomInset: Dp) {
    val flow = controller.manager?.progress ?: remember { MutableStateFlow<CopyProgress?>(null) }
    val progress by flow.collectAsState()
    val mine = progress?.takeIf { it.orgFile == controller.orgFile }
    // 6.6: only copies that run longer than 400 ms get the snackbar.
    var shownJob by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(mine?.jobId) {
        val p = mine ?: return@LaunchedEffect
        val wait = PROGRESS_DELAY_MILLIS - (System.currentTimeMillis() - p.startedAtMillis)
        if (wait > 0) delay(wait)
        shownJob = p.jobId
    }
    val ui = mine?.progress
    val itemFraction = ui?.bytesTotal?.takeIf { it > 0 }?.let { (ui.bytesDone.toFloat() / it).coerceIn(0f, 1f) }
    GroveProgressSnackbar(
        visible = mine != null && shownJob == mine.jobId,
        title = when {
            ui == null -> ""
            ui.count > 1 -> "Copying ${ui.count} ${if (mine.kind == AttachKind.Files) "files" else "photos"}"
            else -> "Copying ${ui.name}"
        },
        subline = when {
            ui == null -> ""
            ui.count > 1 -> "${ui.index} of ${ui.count}"
            else -> formatCopyBytes(ui.bytesDone, ui.bytesTotal)
        },
        fraction = when {
            ui == null -> null
            ui.count > 1 -> ((ui.index - 1) + (itemFraction ?: 0f)) / ui.count
            else -> itemFraction
        },
        onCancel = { controller.manager?.cancel() },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 10.dp),
    )
    if (controller.sheet == null) {
        GroveToast(
            controller.toast,
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 18.dp),
        )
    }

    controller.sheet?.let { model ->
        AttachSheet(
            model = model,
            cameraAvailable = controller.cameraAvailable,
            toast = controller.toast,
            onDismiss = controller::close,
            onPick = controller::pick,
            onInsert = controller::insertExisting,
            onOpen = controller::openWith,
            onDelete = { controller.confirmDelete = it },
            thumbnailUri = controller::thumbnailUri,
        )
    }
    controller.confirmDelete?.let { file ->
        DeleteAttachmentDialog(
            name = file.name.substringAfterLast('/'),
            fileOwner = controller.sheet?.owner is AttachOwner.File,
            onCancel = { controller.confirmDelete = null },
            onConfirm = { controller.delete(file) },
        )
    }
}

/** The 1c attach sheet: owner header, three source pills, and the owner's attachments. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(
    model: AttachSheetModel,
    cameraAvailable: Boolean,
    toast: OutlineToast?,
    onDismiss: () -> Unit,
    onPick: (AttachKind) -> Unit,
    onInsert: (ScopedAttachment) -> Unit,
    onOpen: (FileEntry) -> Unit,
    onDelete: (FileEntry) -> Unit,
    thumbnailUri: suspend (FileEntry) -> Uri? = { null },
) {
    val c = MaterialTheme.grove
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            // 1c: 10dp sheet top padding + 6dp handle margin, 12dp below.
            Box(
                Modifier
                    .padding(top = 16.dp, bottom = 12.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(c.line2),
            )
        },
    ) {
        Box {
            AttachSheetContent(model, cameraAvailable, onPick, onInsert, onOpen, onDelete, thumbnailUri)
            // The sheet is its own window, so list-action toasts are drawn inside it.
            GroveToast(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttachSheetContent(
    model: AttachSheetModel,
    cameraAvailable: Boolean,
    onPick: (AttachKind) -> Unit,
    onInsert: (ScopedAttachment) -> Unit,
    onOpen: (FileEntry) -> Unit,
    onDelete: (FileEntry) -> Unit,
    thumbnailUri: suspend (FileEntry) -> Uri? = { null },
) {
    val c = MaterialTheme.grove
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 18.dp)
            .testTag("attach_sheet"),
    ) {
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Attach to", fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2)
            Text(
                model.stars,
                fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.synStar,
            )
            Text(
                model.title,
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.ink,
                modifier = Modifier.testTag("attach_owner_title"),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourcePill(
                R.drawable.ic_attach_camera, "Camera", enabled = cameraAvailable,
                disabledDescription = "Camera · No camera app found",
                modifier = Modifier.weight(1f).testTag("attach_camera"),
            ) { onPick(AttachKind.Camera) }
            SourcePill(R.drawable.ic_attach_image, "Photos", modifier = Modifier.weight(1f).testTag("attach_photos")) {
                onPick(AttachKind.Photos)
            }
            SourcePill(R.drawable.ic_attach_folder, "Files", modifier = Modifier.weight(1f).testTag("attach_files")) {
                onPick(AttachKind.Files)
            }
        }
        Column(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 18.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface2)
                .padding(vertical = 6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (model.owner is AttachOwner.File) "In this file" else "In this heading",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = c.ink2,
                )
            }
            val rows = model.rows
            when {
                rows == null -> Spacer(Modifier.height(40.dp))
                rows.isEmpty() -> Text(
                    if (model.owner is AttachOwner.File && model.owner.id == null && model.owner.dirProperty == null) {
                        stringResource(R.string.attach_empty_file_no_id, stringResource(R.string.app_name))
                    } else {
                        "No attachments yet."
                    },
                    fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 1.5.em, color = c.ink2,
                    modifier = Modifier
                        .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp)
                        .testTag("attach_empty"),
                )
                else -> model.visibleRows.forEach { row ->
                    val f = row.file
                    AttachmentRow(
                        f, thumbnailUri,
                        onInsert = { onInsert(row) }, onOpen = { onOpen(f) }, onDelete = { onDelete(f) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcePill(
    icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledDescription: String? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    val shape = RoundedCornerShape(23.dp)
    val tint = if (enabled) c.ink else c.ink3
    val base = modifier.height(46.dp).clip(shape)
    val styled = if (enabled) {
        base.border(1.dp, c.line2, shape).clickable(onClick = onClick)
    } else {
        // 1q "No camera app found": dashed border, muted ink, not tappable.
        val dash = c.line2
        base
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = dash,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(23.dp.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
            .semantics { disabledDescription?.let { contentDescription = it } }
    }
    Row(styled, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = tint)
    }
}

@Composable
private fun AttachmentRow(
    file: FileEntry,
    thumbnailUri: suspend (FileEntry) -> Uri?,
    onInsert: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.grove
    val name = file.name.substringAfterLast('/')
    val category = categoryOf(attachmentExtension(name))
    val (fg, bg) = categoryColors(c, category)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 4.dp, top = 5.dp, bottom = 5.dp)
            .testTag("attach_row_$name"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badgeFor(name),
                fontFamily = PlexMono, fontWeight = FontWeight.Bold, fontSize = 9.5.sp,
                letterSpacing = 0.02.em, color = fg, maxLines = 1,
            )
            if (category == AttachmentCategory.IMAGE) ImageThumbnail(file, thumbnailUri)
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontFamily = PlexSans, fontSize = 13.5.sp, color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(formatAttachmentSize(file.size), fontFamily = PlexMono, fontSize = 11.sp, color = c.ink3)
        }
        RowIconButton(
            R.drawable.ic_attach_insert_link, 17.dp, c.accent, c.surface3, c.accent,
            "Insert link at cursor", "attach_insert_$name", onInsert,
        )
        RowIconButton(
            R.drawable.ic_attach_open_with, 16.dp, c.ink2, c.surface3, c.ink2,
            "Open with…", "attach_open_$name", onOpen,
        )
        RowIconButton(
            R.drawable.ic_attach_delete, 16.dp, c.ink3, c.redSoft, c.red,
            "Delete", "attach_delete_$name", onDelete,
        )
    }
}

/**
 * Image rows draw a center-cropped thumbnail over the badge, which stays
 * visible while it decodes and whenever it can't (HEIC/SVG on devices that
 * lack a decoder, corrupt files). Coil sizes the decode to the 40dp tile, and
 * the `thumb:` key keeps these small bitmaps from evicting or standing in for
 * the full-size read-mode images cached under the plain key.
 */
@Composable
private fun ImageThumbnail(file: FileEntry, thumbnailUri: suspend (FileEntry) -> Uri?) {
    val uri by produceState<Uri?>(null, file) { value = thumbnailUri(file) }
    val u = uri ?: return
    val context = LocalContext.current
    val gif = attachmentExtension(file.name).equals("gif", ignoreCase = true)
    val request = remember(u, file) {
        ImageRequest.Builder(context)
            .data(u)
            .memoryCacheKey("thumb:" + attachmentMemoryCacheKey(file))
            // First frame only, like read mode.
            .apply { if (gif) decoderFactory(BitmapFactoryDecoder.Factory()) }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().testTag("attach_thumb_${file.name.substringAfterLast('/')}"),
    )
}

@Composable
private fun RowIconButton(
    icon: Int,
    iconSize: Dp,
    tint: Color,
    pressedBg: Color,
    pressedTint: Color,
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (pressed) pressedBg else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription = null,
            tint = if (pressed) pressedTint else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Badge colors per category (tech design 4.5). */
internal fun categoryColors(c: GroveColors, category: AttachmentCategory): Pair<Color, Color> = when (category) {
    AttachmentCategory.DOCUMENT -> c.red to c.redSoft
    AttachmentCategory.IMAGE -> c.blue to c.blueSoft
    AttachmentCategory.AUDIO -> c.violet to c.violetSoft
    AttachmentCategory.VIDEO -> c.amber to c.amberSoft
    AttachmentCategory.ARCHIVE -> c.green to c.greenSoft
    AttachmentCategory.GENERIC -> c.ink2 to c.surface3
}

/** 1c delete confirmation. */
@Composable
internal fun DeleteAttachmentDialog(name: String, fileOwner: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = MaterialTheme.grove
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                "Delete $name?",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = c.ink,
            )
        },
        text = {
            Text(
                "The file is removed from this ${if (fileOwner) "file" else "heading"}'s attachment folder. " +
                    "Links to it stay in your notes and will show as missing.",
                fontFamily = PlexSans, fontSize = 14.sp, lineHeight = 1.5.em, color = c.ink2,
            )
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.accent)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("attach_delete_confirm")) {
                Text("Delete", fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.red)
            }
        },
    )
}

private const val PROGRESS_DELAY_MILLIS = 400L
