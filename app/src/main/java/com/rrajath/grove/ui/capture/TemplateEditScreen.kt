package com.rrajath.grove.ui.capture

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.capture.CaptureTemplate
import com.rrajath.grove.capture.FilenamePattern
import com.rrajath.grove.capture.FilenameValidation
import com.rrajath.grove.capture.PlaceholderExpander
import com.rrajath.grove.capture.RoamFileId
import com.rrajath.grove.capture.ShortcutSyncer
import com.rrajath.grove.capture.TargetLocation
import com.rrajath.grove.capture.TemplateKind
import com.rrajath.grove.capture.templateSlug
import com.rrajath.grove.ui.components.ChangeIconColorDialog
import com.rrajath.grove.ui.components.DirectoryField
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.MonogramTile
import com.rrajath.grove.ui.components.NotebookFileField
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.components.monogramLetter
import com.rrajath.grove.ui.components.nameHashPaletteKey
import com.rrajath.grove.ui.nav.DeepLinks
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val LOCATION_OPTIONS = listOf(
    "Top of file", "Bottom of file", "Under heading", "Datetree",
)

private fun locationIndex(location: TargetLocation): Int = when (location) {
    is TargetLocation.TopOfFile -> 0
    is TargetLocation.BottomOfFile -> 1
    is TargetLocation.UnderHeading -> 2
    is TargetLocation.Datetree -> 3
}

/** Chips appended to the "File name pattern" field on tap: (label shown, text inserted). */
private val FILENAME_PATTERN_CHIPS = listOf(
    "%<%Y%m%d%H%M%S>" to "%<%Y%m%d%H%M%S>",
    "%<%Y-%m-%d>" to "%<%Y-%m-%d>",
    "%(slug)" to "%(slug)",
    "-" to "-",
)

/** Chips appended to the "New file template" field on tap: (label shown, text inserted). */
private val NEW_FILE_TEMPLATE_CHIPS = listOf(
    "#+title:" to "#+title: ",
    "#+filetags:" to "#+filetags: ",
    "%?" to "%?",
)

/** Template editor (design spec / PRD §7.6). templateId "new" creates one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TemplateEditScreen(
    templateId: String,
    onBack: () -> Unit,
    roamFeaturesEnabled: Boolean = false,
    viewModel: TemplatesViewModel = viewModel(factory = TemplatesViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val focusManager = LocalFocusManager.current
    // A focused OutlinedTextField's cursor/selection handle renders in its own Popup, which
    // isn't part of this screen's exit transition — without clearing focus first, it hangs in
    // place over the previous screen while this composable fades out from under it.
    val leave: () -> Unit = { focusManager.clearFocus(force = true); onBack() }
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val directories by viewModel.directories.collectAsStateWithLifecycle()
    val existing = templates.firstOrNull { it.id == templateId }
    // Fixed for this editor session so the monogram colour stays stable while the
    // name is still being typed, and so a new template's id matches what is saved.
    val editingId = remember(existing) { existing?.id ?: viewModel.newId() }

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var colorKey by remember(existing) { mutableStateOf(existing?.color) }
    var showColorDialog by remember { mutableStateOf(false) }
    // A stale ROAM_NODE template still edits safely with the flag off; new
    // templates always start PLAIN when the switch below is hidden.
    var kind by remember(existing) { mutableStateOf(existing?.kind ?: TemplateKind.PLAIN) }
    val showKindSwitch = roamFeaturesEnabled || kind == TemplateKind.ROAM_NODE
    var targetFile by remember(existing) { mutableStateOf(existing?.targetFile ?: "inbox.org") }
    val targetFileError = FilenameValidation.errorFor(targetFile)
    var locationIdx by remember(existing) {
        mutableIntStateOf(existing?.location?.let { locationIndex(it) } ?: 1)
    }
    var headingTitle by remember(existing) {
        mutableStateOf((existing?.location as? TargetLocation.UnderHeading)?.title ?: "")
    }
    // Seeded from a legacy customId too; saving writes it back as `id`, which
    // still matches a CUSTOM_ID heading, so older templates keep working.
    var headingId by remember(existing) {
        mutableStateOf((existing?.location as? TargetLocation.UnderHeading)?.headingKey ?: "")
    }
    var templateText by remember(existing) { mutableStateOf(existing?.template ?: "* %^{Title}\n%cursor") }
    val invalidPlaceholders = remember(templateText) {
        PlaceholderExpander.findInvalid(templateText).map { it.token }.distinct()
    }
    var roamDirectory by remember(existing) { mutableStateOf(existing?.roamDirectory ?: "") }
    val directoryError = FilenameValidation.errorForDirectory(roamDirectory)
    var filenamePattern by remember(existing) {
        mutableStateOf(existing?.filenamePattern ?: "%<%Y%m%d%H%M%S>-%(slug)")
    }
    val filenamePatternError = FilenamePattern.errorFor(filenamePattern)
    var newFileTemplate by remember(existing) {
        mutableStateOf(TextFieldValue(existing?.newFileTemplate ?: "#+title: %?"))
    }
    // The :ID: drawer is added automatically (RoamFileId); one written here is ignored.
    val roamTemplateDefinesId = remember(newFileTemplate.text) { RoamFileId.templateDefinesId(newFileTemplate.text) }
    val roamInvalidPlaceholders = remember(newFileTemplate.text) {
        PlaceholderExpander.findInvalid(newFileTemplate.text).map { it.token }.distinct()
    }
    var showPlaceholderHelp by remember { mutableStateOf(false) }

    fun buildLocation(): TargetLocation = when (locationIdx) {
        0 -> TargetLocation.TopOfFile
        1 -> TargetLocation.BottomOfFile
        2 -> TargetLocation.UnderHeading(
            title = headingTitle.takeIf { it.isNotBlank() },
            id = headingId.trim().takeIf { it.isNotBlank() },
        )
        else -> TargetLocation.Datetree
    }

    fun currentTemplate() = CaptureTemplate(
        id = editingId,
        name = name.trim(),
        color = colorKey,
        targetFile = targetFile.trim(),
        location = buildLocation(),
        template = templateText,
        kind = kind,
        roamDirectory = roamDirectory.trim(),
        filenamePattern = filenamePattern.trim(),
        newFileTemplate = newFileTemplate.text,
    )
    val canSave = name.isNotBlank() && when (kind) {
        TemplateKind.PLAIN -> targetFileError == null
        TemplateKind.ROAM_NODE -> directoryError == null && filenamePatternError == null
    }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                leading = {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = leave)
                            .padding(12.dp),
                    ) { Text("←", fontFamily = PlexMono, fontSize = 18.sp, color = c.ink) }
                },
                title = {
                    Text(
                        if (existing == null) "New template" else "Edit template",
                        style = MaterialTheme.typography.titleLarge, color = c.ink,
                    )
                },
                actions = {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (canSave) c.accent else c.line)
                            .clickable(enabled = canSave) {
                                viewModel.upsert(currentTemplate())
                                leave()
                            }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        Text(
                            "Save",
                            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, color = if (canSave) c.accentInk else c.ink3,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            FieldLabel("Name")
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonogramTile(
                    letter = monogramLetter(name),
                    colorKey = colorKey ?: nameHashPaletteKey(editingId),
                    size = 56.dp,
                    cornerRadius = 12.dp,
                    dashedBorder = true,
                    addBadge = true,
                    modifier = Modifier.combinedClickable(
                        onClick = { showColorDialog = true },
                        onLongClick = { showColorDialog = true },
                    ),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    textStyle = TextStyle(fontFamily = PlexSans),
                    placeholder = { Text("Meeting Note", fontFamily = PlexSans, color = c.ink3) },
                )
            }

            if (showKindSwitch) {
                FieldLabel("Type")
                SegmentedControl(
                    options = listOf("Add to a file", "New node file"),
                    selectedIndex = if (kind == TemplateKind.PLAIN) 0 else 1,
                    onSelect = { kind = if (it == 0) TemplateKind.PLAIN else TemplateKind.ROAM_NODE },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (kind == TemplateKind.PLAIN) {
                FieldLabel("Target file")
                NotebookFileField(
                    value = targetFile,
                    onValueChange = { targetFile = it },
                    notebooks = notebooks,
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel("Insert at")
                Column {
                    LOCATION_OPTIONS.forEachIndexed { i, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (i == locationIdx) c.accentSoft else c.surface)
                                .clickable { locationIdx = i }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                label,
                                fontFamily = PlexSans,
                                fontWeight = if (i == locationIdx) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp,
                                color = if (i == locationIdx) c.accent else c.ink,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                if (locationIdx == 2) {
                    FieldLabel("Heading: ID (recommended) or exact name")
                    Text(
                        "ID keeps working if the heading is renamed; exact name is simpler but fragile. A CUSTOM_ID also works.",
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    OutlinedTextField(
                        value = headingId, onValueChange = { headingId = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = PlexMono),
                        placeholder = { Text("heading ID (recommended)", fontFamily = PlexMono, color = c.ink3) },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = headingTitle, onValueChange = { headingTitle = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = PlexSans),
                        placeholder = { Text("…or exact heading name", fontFamily = PlexSans, color = c.ink3) },
                    )
                }

                FieldLabel("Template")
                OutlinedTextField(
                    value = templateText, onValueChange = { templateText = it },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    isError = invalidPlaceholders.isNotEmpty(),
                    textStyle = TextStyle(fontFamily = PlexMono, fontSize = 13.5.sp),
                )
                if (invalidPlaceholders.isNotEmpty()) {
                    Text(
                        "Unsupported placeholder${if (invalidPlaceholders.size > 1) "s" else ""}: " +
                            invalidPlaceholders.joinToString(", "),
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.red,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    "placeholder help",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp, color = c.accent,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { showPlaceholderHelp = true }
                        .padding(vertical = 10.dp),
                )
            } else {
                FieldLabel("Directory")
                DirectoryField(
                    value = roamDirectory,
                    onValueChange = { roamDirectory = it },
                    directories = directories,
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel("File name pattern")
                OutlinedTextField(
                    value = filenamePattern, onValueChange = { filenamePattern = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = filenamePatternError != null,
                    supportingText = {
                        if (filenamePatternError != null) {
                            Text(filenamePatternError, color = c.red, fontFamily = PlexSans, fontSize = 12.sp)
                        }
                    },
                    textStyle = TextStyle(fontFamily = PlexMono),
                    suffix = { Text(".org", fontFamily = PlexMono, color = c.ink3) },
                )
                InsertChipsRow(FILENAME_PATTERN_CHIPS) { insertText -> filenamePattern += insertText }

                FieldLabel("New file template")
                Text(
                    "An :ID: property is added automatically at the top of the file. No need to add it here.",
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                OutlinedTextField(
                    value = newFileTemplate, onValueChange = { newFileTemplate = it },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    isError = roamInvalidPlaceholders.isNotEmpty(),
                    textStyle = TextStyle(fontFamily = PlexMono, fontSize = 13.5.sp),
                )
                if (roamInvalidPlaceholders.isNotEmpty()) {
                    Text(
                        "Unsupported placeholder${if (roamInvalidPlaceholders.size > 1) "s" else ""}: " +
                            roamInvalidPlaceholders.joinToString(", "),
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.red,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (roamTemplateDefinesId) {
                    Text(
                        "An ID is created automatically, so the :ID: in this template will be ignored.",
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.amber,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                InsertChipsRow(NEW_FILE_TEMPLATE_CHIPS) { insertText ->
                    val sel = newFileTemplate.selection
                    val text = newFileTemplate.text.replaceRange(sel.min, sel.max, insertText)
                    newFileTemplate = TextFieldValue(text, TextRange(sel.min + insertText.length))
                }
                Text(
                    "placeholder help",
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp, color = c.accent,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable { showPlaceholderHelp = true }
                        .padding(vertical = 10.dp),
                )
            }

            // Leaving this screen retires this feature's NEW dot from its whole
            // trail (menu glyph, drawer, Settings hub row, this label).
            MarkNewFeatureSeen(NewAnchors.SETTINGS_CAPTURE_TEMPLATES_LINK)
            FieldLabel("Capture link", badge = { NewDot(NewAnchors.SETTINGS_CAPTURE_TEMPLATES_LINK) })
            Text(
                "Jumps straight into this template's capture editor -- open it from a " +
                    "browser bookmark, a launcher shortcut app, or anywhere else that can " +
                    "open a link. Tap to copy it.",
                fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            val slug = remember(name) { templateSlug(name) }
            if (slug.isBlank()) {
                Text(
                    "Add a name above to generate this template's capture link.",
                    fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3,
                )
            } else {
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                val deepLink = DeepLinks.captureTemplate(slug)
                Text(
                    deepLink,
                    fontFamily = PlexMono, fontSize = 13.sp, color = c.accent,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("capture link", deepLink)))
                            }
                        }
                        .padding(vertical = 4.dp),
                )

                Spacer(Modifier.height(10.dp))
                val context = LocalContext.current
                val pinScope = rememberCoroutineScope()
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (canSave) c.accentSoft else c.surface)
                        .clickable(enabled = canSave) {
                            // The shortcut's bloom://capture/{id} target only resolves once the
                            // template is actually persisted, so save whatever's on screen first --
                            // including a brand-new, not-yet-saved template.
                            val template = currentTemplate()
                            viewModel.upsert(template)
                            pinScope.launch {
                                val app = context.applicationContext as com.rrajath.grove.GroveApplication
                                val settings = app.settingsRepository.settings.first()
                                val pinned = ShortcutSyncer.requestPin(
                                    context, template, settings.theme, settings.syncAppIconWithTheme,
                                )
                                if (!pinned) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Your launcher doesn't support adding shortcuts to the homescreen.",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        "Add to Homescreen",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp, color = if (canSave) c.accent else c.ink3,
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (showPlaceholderHelp) {
        PlaceholderInfoDialog(onDismiss = { showPlaceholderHelp = false })
    }

    if (showColorDialog) {
        ChangeIconColorDialog(
            name = name.ifBlank { "New template" },
            hint = "Letter follows the template name",
            letter = monogramLetter(name),
            currentColorKey = colorKey ?: nameHashPaletteKey(editingId),
            onPickColor = { colorKey = it },
            onDismiss = { showColorDialog = false },
            templateIcon = true,
        )
    }
}

/** A row of tappable chips that append their insert text to a field, mirroring the
 * shorthand-hint chips in PlanningDatesScreen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InsertChipsRow(chips: List<Pair<String, String>>, onInsert: (String) -> Unit) {
    val c = MaterialTheme.grove
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        chips.forEach { (label, insertText) ->
            Text(
                label,
                fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.surface2)
                    .clickable { onInsert(insertText) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String, badge: (@Composable () -> Unit)? = null) {
    // The badge sits in the same Row as the label with no padding of its own,
    // so Alignment.CenterVertically centers it against the label's actual
    // glyph height -- putting the padding on the label Text itself (as a
    // plain Row-less label does) would bias a co-centered sibling upward,
    // since the top/bottom padding here isn't symmetric.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    ) {
        Text(
            text,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, letterSpacing = 1.sp,
            color = MaterialTheme.grove.accent,
        )
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            badge()
        }
    }
}
