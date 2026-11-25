package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.settings.AttachAutoTag
import com.rrajath.grove.settings.AttachFolder
import com.rrajath.grove.settings.AttachImageWidth
import com.rrajath.grove.settings.AttachPhotoSize
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.attach.StyledArg
import com.rrajath.grove.ui.attach.withStyledArgs
import com.rrajath.grove.ui.components.NameDialog
import com.rrajath.grove.ui.components.Pill
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Settings § Attachments (preview), prototype 1o:
 * Photo size for Camera/Photos imports, and the default read-mode image width
 * with a px stepper when Fixed. Below them, the org-attach compatibility group:
 * the attachment folder (`org-attach-id-dir`) and the attachment tag
 * (`org-attach-auto-tag`), each edited in [NameDialog]. All values are in
 * Settings import/export.
 */
@Composable
fun SettingsAttachmentsScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetPhotoSize: (AttachPhotoSize) -> Unit,
    onSetImageWidth: (AttachImageWidth) -> Unit,
    onSetImageWidthPx: (Int) -> Unit,
    onSetAttachFolder: (String) -> Unit,
    onSetAttachAutoTag: (String) -> Unit,
) {
    val c = MaterialTheme.grove
    var editingFolder by rememberSaveable { mutableStateOf(false) }
    var editingTag by rememberSaveable { mutableStateOf(false) }
    MarkNewFeatureSeen(NewAnchors.SETTINGS_ATTACHMENTS_PAGE)
    MarkNewFeatureSeen(NewAnchors.SETTINGS_ATTACHMENTS_AUTO_TAG)
    SettingsPageScaffold(
        title = stringResource(R.string.settings_page_attachments_title),
        onBack = onBack,
        titleBadge = { Pill(text = stringResource(R.string.settings_badge_preview), fg = c.amber, bg = c.amberSoft) },
    ) {
        // Same note style as Settings § Roam Features.
        Text(
            stringResource(R.string.settings_attach_preview_note),
            fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink2,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 20.dp),
        )
        SectionLabel(stringResource(R.string.settings_attach_section_attachments))
        SettingsGroup {
            Column(Modifier.settingAnchor(SettingSpecs.AttachPhotoSize).fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp)) {
                RowTitle(SettingSpecs.AttachPhotoSize.labelText())
                Text(
                    SettingSpecs.AttachPhotoSize.descriptionText().orEmpty(),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 2.dp, bottom = 11.dp),
                )
                SegmentedControl(
                    options = AttachPhotoSize.entries.map { stringResource(it.labelRes) },
                    selectedIndex = settings.attachPhotoSize.ordinal,
                    onSelect = { onSetPhotoSize(AttachPhotoSize.entries[it]) },
                    modifier = Modifier.fillMaxWidth().testTag("attach_photo_size"),
                )
                Text(
                    settings.attachPhotoSize.longEdge
                        ?.let { stringResource(R.string.settings_attach_photo_size_long_edge, it) }
                        ?: stringResource(R.string.settings_attach_photo_size_original_helper),
                    fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
            RowDivider()
            Column(Modifier.settingAnchor(SettingSpecs.AttachImageWidth).fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp)) {
                RowTitle(SettingSpecs.AttachImageWidth.labelText())
                Text(
                    withStyledArgs(
                        stringResource(R.string.settings_attach_image_width_hint),
                        StyledArg("#+ATTR_ORG: :width", SpanStyle(fontFamily = PlexMono)), // i18n: org syntax
                    ),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 2.dp, bottom = 11.dp),
                )
                SegmentedControl(
                    options = AttachImageWidth.entries.map { stringResource(it.labelRes) },
                    selectedIndex = settings.attachImageWidth.ordinal,
                    onSelect = { onSetImageWidth(AttachImageWidth.entries[it]) },
                    modifier = Modifier.fillMaxWidth().testTag("attach_image_width"),
                )
                if (settings.attachImageWidth == AttachImageWidth.FIXED) {
                    WidthStepper(
                        px = settings.attachImageWidthPx,
                        onChange = { onSetImageWidthPx(AttachImageWidth.clampPx(it)) },
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.settings_attach_section_org_attach))
        SettingsGroup {
            SettingsRow(
                spec = SettingSpecs.AttachFolder,
                descriptionContent = {
                    Text(
                        withStyledArgs(
                            stringResource(R.string.settings_attach_folder_hint),
                            StyledArg("org-attach-id-dir", SpanStyle(fontFamily = PlexMono)),
                        ),
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    )
                },
                onClick = { editingFolder = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        settings.attachFolder,
                        fontFamily = PlexMono, fontSize = 13.sp, color = c.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp).testTag("attach_folder_value"),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                }
            }
            RowDivider()
            SettingsRow(
                spec = SettingSpecs.AttachTag,
                descriptionContent = {
                    val tag = settings.attachAutoTag
                    Text(
                        if (tag.isEmpty()) {
                            withStyledArgs(
                                stringResource(R.string.settings_attach_tag_none_hint),
                                StyledArg("org-attach-auto-tag", SpanStyle(fontFamily = PlexMono)),
                            )
                        } else {
                            withStyledArgs(
                                stringResource(R.string.settings_attach_tag_hint),
                                StyledArg(":$tag:", SpanStyle(fontFamily = PlexMono)),
                                StyledArg("org-attach-auto-tag", SpanStyle(fontFamily = PlexMono)),
                            )
                        },
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                        modifier = Modifier.testTag("attach_auto_tag_description"),
                    )
                },
                labelBadge = { NewDot(NewAnchors.SETTINGS_ATTACHMENTS_AUTO_TAG) },
                onClick = { editingTag = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        settings.attachAutoTag.ifEmpty { stringResource(R.string.settings_attach_tag_value_none) },
                        fontFamily = if (settings.attachAutoTag.isEmpty()) PlexSans else PlexMono,
                        fontSize = 13.sp,
                        color = if (settings.attachAutoTag.isEmpty()) c.ink3 else c.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp).testTag("attach_auto_tag_value"),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
                }
            }
        }
    }
    if (editingFolder) {
        NameDialog(
            title = stringResource(R.string.settings_attach_folder),
            initial = settings.attachFolder,
            confirmLabel = stringResource(R.string.action_save),
            placeholder = AttachFolder.DEFAULT,
            validate = { AttachFolder.error(it) },
            styledHelperText = withStyledArgs(
                stringResource(R.string.settings_attach_folder_helper),
                StyledArg("attach/", SpanStyle(fontFamily = PlexMono)), // i18n: example folder value
                StyledArg("/attach/", SpanStyle(fontFamily = PlexMono)), // i18n: example folder value
                StyledArg(AttachFolder.DEFAULT, SpanStyle(fontFamily = PlexMono)),
            ),
            onDismiss = { editingFolder = false },
            onConfirm = { raw ->
                editingFolder = false
                val next = AttachFolder.normalize(raw) ?: return@NameDialog
                if (next != settings.attachFolder) onSetAttachFolder(next)
            },
        )
    }
    if (editingTag) {
        NameDialog(
            title = stringResource(R.string.settings_attach_tag),
            initial = settings.attachAutoTag,
            confirmLabel = stringResource(R.string.action_save),
            placeholder = null,
            validate = { AttachAutoTag.error(it) },
            helperText = stringResource(R.string.settings_attach_tag_helper),
            allowBlank = true,
            onDismiss = { editingTag = false },
            onConfirm = { raw ->
                editingTag = false
                val next = AttachAutoTag.normalize(raw) ?: return@NameDialog
                if (next != settings.attachAutoTag) onSetAttachAutoTag(next)
            },
        )
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(
        text,
        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp, color = MaterialTheme.grove.ink,
    )
}

/** 1o "Width" row: outlined `− 320 px +` stepper, 40 px steps within 120..1200. */
@Composable
private fun WidthStepper(px: Int, onChange: (Int) -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.settings_attach_width),
            fontFamily = PlexSans, fontSize = 13.sp, color = c.ink2,
            modifier = Modifier.weight(1f),
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, c.line2, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(
                "−", stringResource(R.string.settings_attach_width_narrower), enabled = px > AttachImageWidth.MIN_PX,
                tag = "attach_width_minus",
            ) { onChange(px - AttachImageWidth.STEP_PX) }
            Text(
                stringResource(R.string.settings_attach_width_px, px),
                fontFamily = PlexMono, fontSize = 13.sp, color = c.ink,
                modifier = Modifier.padding(horizontal = 10.dp).testTag("attach_width_value"),
            )
            StepButton(
                "+", stringResource(R.string.settings_attach_width_wider), enabled = px < AttachImageWidth.MAX_PX,
                tag = "attach_width_plus",
            ) { onChange(px + AttachImageWidth.STEP_PX) }
        }
    }
}

@Composable
private fun StepButton(glyph: String, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .size(width = 36.dp, height = 34.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontFamily = PlexSans, fontSize = 15.sp, color = if (enabled) c.ink2 else c.ink3)
    }
}
