package com.rrajath.grove.ui.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.R
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.TemplateKind
import com.rrajath.grove.capture.TemplateValidator
import com.rrajath.grove.ui.components.MonogramTile
import com.rrajath.grove.ui.components.Pill
import com.rrajath.grove.ui.components.monogramLetter
import com.rrajath.grove.ui.components.nameHashPaletteKey
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.asString

/** Capture template picker bottom sheet (design spec §7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturePickerSheet(
    onDismiss: () -> Unit,
    onPickTemplate: (CaptureTemplate) -> Unit,
    onManage: () -> Unit,
    viewModel: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory),
) {
    val templates by viewModel.pickerTemplates.collectAsStateWithLifecycle()

    // `templates` starts as emptyList() until TemplatesRepository's DataStore
    // flow delivers its first emission (async, via stateIn/WhileSubscribed) —
    // cold every time in a freshly-launched app. Composing ModalBottomSheet
    // before that arrives means it calculates its swipe anchors against a
    // near-empty first frame (just the header row); Compose Material3 does not
    // reliably re-anchor once the real rows grow the content a frame later, so
    // the sheet gets stuck at its empty-state height. Waiting here means the
    // sheet is never composed until it can open already sized for its real
    // content.
    if (templates.isEmpty()) return

    // PRD §7.2: with a single template the picker is skipped entirely.
    if (templates.size == 1) {
        androidx.compose.runtime.LaunchedEffect(templates) {
            onPickTemplate(templates.first())
        }
        return
    }

    val c = MaterialTheme.grove
    val sheetState = rememberModalBottomSheetState()

    com.rrajath.grove.ui.components.GroveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // Cap the scrim at 80% instead of Material3's fully-opaque default
        // scrim token, so the notebooks list stays faintly visible behind it.
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f),
    ) {
        Column(Modifier.padding(bottom = 26.dp).testTag("capture_sheet")) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.capture_picker_title),
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp, color = c.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.capture_picker_manage),
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp, color = c.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onManage)
                        .padding(6.dp),
                )
            }
            templates.forEach { template ->
                TemplateRow(template, onClick = { onPickTemplate(template) })
            }
        }
    }
}

@Composable
private fun TemplateRow(template: CaptureTemplate, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("capture_template_row")
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramTile(
            letter = monogramLetter(template.name),
            colorKey = template.color ?: nameHashPaletteKey(template.id),
            size = 44.dp,
            cornerRadius = 13.dp,
            dashedBorder = true,
            addBadge = true,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                template.name,
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 15.5.sp, color = c.ink,
            )
            Text(
                if (template.kind == TemplateKind.ROAM_NODE) {
                    val stem = "${template.filenamePattern}.org"
                    if (template.roamDirectory.isBlank()) stem else "${template.roamDirectory}/$stem"
                } else {
                    "${template.targetFile} · ${template.location.describe().asString()}"
                },
                fontFamily = PlexMono, fontSize = 12.5.sp, color = c.ink2,
            )
        }
        val issues = remember(template) { TemplateValidator.validate(template) }
        if (issues.hasErrors) {
            Pill(text = "⚠ ${issues.count}", fg = c.red, bg = c.redSoft)
            Spacer(Modifier.width(8.dp))
        }
        Text("›", fontFamily = PlexMono, fontSize = 16.sp, color = c.ink3)
    }
}
