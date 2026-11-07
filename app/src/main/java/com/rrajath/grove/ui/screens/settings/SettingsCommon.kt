package com.rrajath.grove.ui.screens.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.TemplateKind
import com.rrajath.grove.capture.TemplateValidator
import com.rrajath.grove.settings.AgendaSwipeAction
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.MonogramTile
import com.rrajath.grove.ui.components.Pill
import com.rrajath.grove.ui.components.monogramLetter
import com.rrajath.grove.ui.components.nameHashPaletteKey
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Shared chrome for every settings sub-page (Settings §11 split into one page
 * per section): back-arrow app bar + the same scrollable, 16dp-gutter column
 * every section used when they all lived on one screen.
 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    /** Drawn right after the title, e.g. the "Experimental" pill. */
    titleBadge: (@Composable () -> Unit)? = null,
    content: @Composable (ScrollState) -> Unit,
) {
    val c = MaterialTheme.grove
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                // A focused field's cursor/selection handle lives in its own Popup, outside this
                // page's exit transition — clear focus before navigating so it doesn't hang in
                // place over the previous screen while this page fades out from under it.
                leading = { IconGlyph("←", onClick = { focusManager.clearFocus(force = true); onBack() }) },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = c.ink,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        if (titleBadge != null) {
                            Box(Modifier.padding(start = 10.dp)) { titleBadge() }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            content(scrollState)
        }
    }
}

@Composable
internal fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.padding(start = 4.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, letterSpacing = 1.sp,
            color = MaterialTheme.grove.accent,
        )
        if (trailing != null) Box(Modifier.padding(start = 8.dp)) { trailing() }
    }
}

@Composable
internal fun SettingsGroup(content: @Composable () -> Unit) {
    val c = MaterialTheme.grove
    Column(
        Modifier
            .fillMaxWidth()
            // Rows disclosed by an `if` (e.g. a toggle revealing a follow-up row)
            // otherwise snap the card's height in one frame; animate the resize so
            // it reads as an expand/collapse rather than a jump.
            .animateContentSize()
            .clip(RoundedCornerShape(15.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(15.dp)),
    ) {
        content()
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.grove.line, modifier = Modifier.padding(horizontal = 15.dp))
}

@Composable
internal fun SettingsRow(
    label: String,
    description: String? = null,
    descriptionContent: (@Composable () -> Unit)? = null,
    labelBadge: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .then(clickMod)
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = MaterialTheme.grove.ink,
                )
                if (labelBadge != null) {
                    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) { labelBadge() }
                }
            }
            if (descriptionContent != null) {
                Box(Modifier.padding(top = 2.dp)) { descriptionContent() }
            } else if (description != null) {
                Text(
                    description,
                    fontFamily = PlexSans, fontSize = 12.sp, color = MaterialTheme.grove.ink2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    description: String? = null,
    labelBadge: (@Composable () -> Unit)? = null,
    onToggle: (Boolean) -> Unit,
) {
    val c = MaterialTheme.grove
    // The `checked` source is a DataStore-backed StateFlow, so it only flips a few
    // frames after the tap (coroutine hop + disk write + emit + recompose). Driving
    // the Switch straight off it makes the thumb snap late instead of sliding. Track
    // the state locally for an instant, smooth animation and let the store catch up.
    var shown by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) { shown = checked }
    val setChecked: (Boolean) -> Unit = { shown = it; onToggle(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { setChecked(!shown) }
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp, color = c.ink,
                )
                if (labelBadge != null) {
                    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) { labelBadge() }
                }
            }
            if (description != null) {
                Text(
                    description,
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = shown,
            onCheckedChange = setChecked,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = c.accent,
                checkedThumbColor = c.accentInk,
                uncheckedTrackColor = c.surface3,
                uncheckedThumbColor = c.surface,
            ),
        )
    }
}

@Composable
internal fun SmallAction(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            glyph,
            fontFamily = PlexMono, fontSize = 13.sp,
            color = if (enabled) c.ink2 else c.line2,
        )
    }
}

@Composable
internal fun TemplateSettingsRow(
    template: CaptureTemplate,
    onEdit: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.grove
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramTile(
            letter = monogramLetter(template.name),
            colorKey = template.color ?: nameHashPaletteKey(template.id),
            size = 30.dp,
            cornerRadius = 9.dp,
            dashedBorder = true,
            addBadge = true,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                template.name,
                fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp, color = c.ink,
            )
            Text(
                if (template.kind == TemplateKind.ROAM_NODE) {
                    val stem = "${template.filenamePattern}.org"
                    if (template.roamDirectory.isBlank()) stem else "${template.roamDirectory}/$stem"
                } else {
                    "${template.targetFile} · ${template.location.describe()}"
                },
                fontFamily = PlexMono, fontSize = 12.sp, color = c.ink2,
            )
        }
        val issues = remember(template) { TemplateValidator.validate(template) }
        if (issues.hasErrors) {
            Pill(text = "⚠ ${issues.count}", fg = c.red, bg = c.redSoft)
            Spacer(Modifier.width(8.dp))
        }
        SmallAction("↑", enabled = onMoveUp != null) { onMoveUp?.invoke() }
        SmallAction("↓", enabled = onMoveDown != null) { onMoveDown?.invoke() }
        SmallAction("✕", enabled = true, onClick = { confirmDelete = true })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.surface,
            title = {
                Text(
                    "Delete “${template.name}”?",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = c.ink,
                )
            },
            text = {
                Text(
                    "This removes the capture template. Notes you already captured with it are not affected.",
                    fontFamily = PlexSans, fontSize = 14.sp, color = c.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = c.red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", color = c.ink2)
                }
            },
        )
    }
}

internal fun swipeActionDescription(action: AgendaSwipeAction): String = when (action) {
    AgendaSwipeAction.SET_SCHEDULED -> "open the scheduled-date picker"
    AgendaSwipeAction.SET_DEADLINE -> "open the deadline picker"
    AgendaSwipeAction.MARK_DONE -> "mark it done"
}

/** Friendly folder name from a SAF tree URI ("content://…/tree/primary%3Aorg" → "org"). */
internal fun uriDisplayName(uri: String): String {
    val last = java.net.URLDecoder.decode(uri.substringAfterLast("/"), "UTF-8")
    return last.substringAfterLast(':').ifEmpty { last }
}

/**
 * Whole-number settings field (Widget "Days ahead", Agenda "Deadline warning"):
 * non-digit keystrokes are filtered as typed, and a value below [minValue] (or
 * blank) reverts to the last committed setting on blur rather than showing an
 * inline error.
 */
@Composable
internal fun IntSettingField(value: Int, minValue: Int, onSet: (Int) -> Unit) {
    val c = MaterialTheme.grove
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(4) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(fontFamily = PlexMono, fontSize = 14.sp, color = c.accent),
        modifier = Modifier
            .width(72.dp)
            .onFocusChanged { state ->
                if (!state.isFocused) {
                    val parsed = text.toIntOrNull()
                    if (parsed != null && parsed >= minValue) onSet(parsed) else text = value.toString()
                }
            },
    )
}
