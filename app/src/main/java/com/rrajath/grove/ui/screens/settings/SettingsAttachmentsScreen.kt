package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.settings.AttachImageWidth
import com.rrajath.grove.settings.AttachPhotoSize
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Settings § Attachments (experimental, debug builds only), prototype 1o:
 * Photo size for Camera/Photos imports, and the default read-mode image width
 * with a px stepper when Fixed. All three values are in Settings import/export.
 */
@Composable
fun SettingsAttachmentsScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetPhotoSize: (AttachPhotoSize) -> Unit,
    onSetImageWidth: (AttachImageWidth) -> Unit,
    onSetImageWidthPx: (Int) -> Unit,
) {
    val c = MaterialTheme.grove
    SettingsPageScaffold(title = "Attachments", onBack = onBack) {
        Box(Modifier.height(18.dp))
        SectionLabel("ATTACHMENTS")
        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp)) {
                RowTitle("Photo size")
                Text(
                    "Camera and Photos only. Files are copied as-is.",
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 2.dp, bottom = 11.dp),
                )
                SegmentedControl(
                    options = AttachPhotoSize.entries.map { it.label },
                    selectedIndex = settings.attachPhotoSize.ordinal,
                    onSelect = { onSetPhotoSize(AttachPhotoSize.entries[it]) },
                    modifier = Modifier.fillMaxWidth().testTag("attach_photo_size"),
                )
                Text(
                    settings.attachPhotoSize.helper,
                    fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp)) {
                RowTitle("Default image width")
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = PlexMono)) { append("#+ATTR_ORG: :width") }
                        append(" overrides this per image.")
                    },
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 2.dp, bottom = 11.dp),
                )
                SegmentedControl(
                    options = AttachImageWidth.entries.map { it.label },
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
            "Width",
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
                "−", "Narrower", enabled = px > AttachImageWidth.MIN_PX,
                tag = "attach_width_minus",
            ) { onChange(px - AttachImageWidth.STEP_PX) }
            Text(
                "$px px",
                fontFamily = PlexMono, fontSize = 13.sp, color = c.ink,
                modifier = Modifier.padding(horizontal = 10.dp).testTag("attach_width_value"),
            )
            StepButton(
                "+", "Wider", enabled = px < AttachImageWidth.MAX_PX,
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
