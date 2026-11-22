package com.rrajath.grove.ui.screens.settings

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.rrajath.grove.BuildConfig
import com.rrajath.grove.R
import com.rrajath.grove.org.LineEditing
import com.rrajath.grove.org.TextEdit
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.editor.insertedSoloNewlineAt
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val BUG_REPORT_EMAIL = "r.rajath@gmail.com"

private data class BugPayloadRow(val key: String, val value: String)

private data class DeviceInfo(
    val appVersion: String,
    val buildNumber: String,
    val androidVersion: String,
    val deviceModel: String,
    val locale: String,
)

private const val GITHUB_ISSUE_URL = "https://github.com/rrajath/bloom/issues/new"

/**
 * Settings § Help → Report a bug (design/Grove.dc.html lines 1811-1881). Both
 * actions build the same plain-text report from the form (based on which toggles
 * are on) and copy it to the clipboard, then: "Send report via email" opens a
 * mail app chooser with it filled into a new email to [BUG_REPORT_EMAIL];
 * "Open an issue on GitHub" opens [GITHUB_ISSUE_URL] in the browser (the user
 * pastes the copied report into the issue body). Nothing is filed remotely by
 * the app itself. The clipboard copy happens unconditionally so the report
 * survives even if the target app can't be opened.
 */
@Composable
fun SettingsBugReportScreen(onBack: () -> Unit) {
    val c = MaterialTheme.grove
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val bugReportSubject = "${stringResource(R.string.app_name)} Bug Report"

    var description by rememberSaveable { mutableStateOf("") }
    val stepsState = rememberTextFieldState()
    var includeDeviceInfo by rememberSaveable { mutableStateOf(true) }
    var includeErrorLog by rememberSaveable { mutableStateOf(false) }
    var previewOpen by rememberSaveable { mutableStateOf(false) }
    var sending by rememberSaveable { mutableStateOf(false) }

    // Not rememberSaveable: a captured log is only meaningful for the current process's
    // session, so it's fine (and correct) for this to reset on process death.
    var errorLogLines by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(includeErrorLog) {
        errorLogLines = if (includeErrorLog) captureRecentErrorLog(maxLines = 50) else null
    }

    // Builds the report from the form as it stands right now (re-capturing the log so it
    // reflects everything up to the tap, not just when the toggle was flipped on) and puts
    // it on the clipboard, unconditionally, so it's never lost if the target app won't open.
    suspend fun buildAndCopyReport(): String {
        val freshErrorLog = if (includeErrorLog) captureRecentErrorLog(maxLines = 50) else null
        errorLogLines = freshErrorLog
        val body = formatBugReportEmail(
            description = description,
            steps = stepsState.text.toString(),
            deviceInfo = if (includeDeviceInfo) deviceInfo() else null,
            errorLogLines = if (includeErrorLog) freshErrorLog else null,
        )
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(bugReportSubject, body)))
        return body
    }

    val canSend = description.isNotBlank() && !sending
    val payload = remember(includeDeviceInfo, includeErrorLog, errorLogLines) {
        bugPayload(includeDeviceInfo, includeErrorLog, errorLogLines)
    }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                leading = { IconGlyph("←", onClick = onBack) },
                title = {
                    Text(
                        "Report a bug",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.ink,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(c.bg)) {
                HorizontalDivider(color = c.line)
                Column(Modifier.padding(16.dp)) {
                    BugReportButton(
                        label = if (sending) "Opening mail app…" else "Send report via email",
                        enabled = canSend,
                        primary = true,
                        onClick = {
                            sending = true
                            scope.launch {
                                val body = buildAndCopyReport()
                                // ACTION_SEND + "message/rfc822" (rather than ACTION_SENDTO +
                                // a mailto: URI) is the intent shape non-Gmail mail apps
                                // (Outlook, Yahoo, Samsung Email, …) register a compose-email
                                // intent-filter for, so the chooser actually lists them too.
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "message/rfc822"
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf(BUG_REPORT_EMAIL))
                                    putExtra(Intent.EXTRA_SUBJECT, bugReportSubject)
                                    putExtra(Intent.EXTRA_TEXT, body)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(intent, "Send bug report"))
                                }.onSuccess {
                                    Toast.makeText(context, "Opened your mail app. Report also copied to clipboard.", Toast.LENGTH_LONG).show()
                                }.onFailure {
                                    Toast.makeText(context, "No mail app found. Report copied to clipboard instead.", Toast.LENGTH_LONG).show()
                                }
                                sending = false
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    BugReportButton(
                        label = "Open an issue on GitHub",
                        enabled = canSend,
                        primary = false,
                        onClick = {
                            scope.launch {
                                buildAndCopyReport()
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_ISSUE_URL.toUri()))
                                }.onSuccess {
                                    Toast.makeText(context, "Report copied. Opening GitHub, paste it into the issue.", Toast.LENGTH_LONG).show()
                                }.onFailure {
                                    Toast.makeText(context, "Couldn't open a browser. Report copied to clipboard instead.", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                    Text(
                        "The bug details will be copied to your clipboard.",
                        fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Tell us what happened. Everything below is optional except the description. " +
                    "Either button copies the report to your clipboard, then opens your mail app " +
                    "or GitHub; nothing is filed until you send the email or submit the issue.",
                fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 1.55.em, color = c.ink2,
                modifier = Modifier.padding(bottom = 18.dp),
            )

            FieldSectionLabel(label = "WHAT WENT WRONG", required = true)
            BugTextArea(
                value = description,
                onValueChange = { description = it },
                placeholder = "e.g. App crashed when I opened a saved note offline",
                minLines = 4,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            FieldSectionLabel(label = "STEPS TO REPRODUCE", required = false)
            BugListTextArea(
                state = stepsState,
                placeholder = "1. Open a note\n2. Turn on airplane mode\n3. …",
                minLines = 3,
                modifier = Modifier.padding(bottom = 22.dp),
            )

            SectionLabel("WHAT WE INCLUDE")
            SettingsGroup {
                ToggleRow(
                    label = "Device & app info",
                    description = "Android version, device model, app version (helps us reproduce it)",
                    checked = includeDeviceInfo,
                    onToggle = { includeDeviceInfo = it },
                )
                RowDivider()
                ToggleRow(
                    label = "Recent error log",
                    description = "Last ~50 lines from this session only. Off by default.",
                    checked = includeErrorLog,
                    onToggle = { includeErrorLog = it },
                )
            }

            Spacer(Modifier.height(12.dp))
            PreviewToggleRow(open = previewOpen, onToggle = { previewOpen = !previewOpen })
            AnimatedVisibility(visible = previewOpen) {
                BugPayloadPreview(payload, modifier = Modifier.padding(bottom = 6.dp))
            }

            Spacer(Modifier.height(if (previewOpen) 10.dp else 16.dp))
            PrivacyNote()
        }
    }
}

/**
 * List-only counterpart of `OrgEditing.kt`'s `orgInputTransformation`: same
 * change-list-based Enter detection (via the shared [insertedSoloNewlineAt]),
 * `LineEditing.continueListOnEnter` and `capitalizeListItemOnType`, minus the
 * heading capitalization this field has no use for. Deliberately built on the
 * newer `TextFieldState`/`InputTransformation` API rather than mutating a
 * classic `TextFieldValue` in `onValueChange`: doing the latter desyncs the
 * IME's composing region on real devices (a keystroke lands right as this
 * function rewrites the buffer) and silently eats the next character typed.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun stepsListInputTransformation() = InputTransformation {
    val old = originalText.toString()
    val typed = toString()
    val cursor = selection.start

    val afterEnter = if (insertedSoloNewlineAt(cursor)) LineEditing.continueListOnEnter(typed, cursor) else null
    val base = afterEnter ?: TextEdit(typed, cursor)
    val result = LineEditing.capitalizeListItemOnType(old, base.text, base.cursor) ?: base

    if (result.text != typed || result.cursor != cursor) {
        replace(0, length, result.text)
        selection = TextRange(result.cursor)
    }
}

@Composable
private fun FieldSectionLabel(label: String, required: Boolean) {
    val c = MaterialTheme.grove
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    ) {
        Text(
            label,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, letterSpacing = 1.sp, color = c.accent,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            if (required) "required" else "optional",
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp, color = if (required) c.red else c.ink3,
        )
    }
}

@Composable
private fun BugTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minLines: Int,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(placeholder, fontFamily = PlexSans, fontSize = 14.sp, color = c.ink3)
        },
        textStyle = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, lineHeight = 21.sp, color = c.ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        minLines = minLines,
        maxLines = minLines,
        shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surface,
            unfocusedContainerColor = c.surface,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.line,
            cursorColor = c.accent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * List-aware counterpart of [BugTextArea] for the steps field: a
 * `TextFieldState`-backed `BasicTextField` (see [stepsListInputTransformation]
 * for why) hand-styled to match [BugTextArea]'s `OutlinedTextField` chrome —
 * same 15dp-radius `surface` box with a `line`→`accent` border on focus, since
 * `BasicTextField` doesn't provide that shell itself.
 */
@Composable
private fun BugListTextArea(
    state: TextFieldState,
    placeholder: String,
    minLines: Int,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(c.surface)
            .border(1.dp, if (isFocused) c.accent else c.line, RoundedCornerShape(15.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (state.text.isEmpty()) {
            Text(placeholder, fontFamily = PlexSans, fontSize = 14.sp, lineHeight = 21.sp, color = c.ink3)
        }
        BasicTextField(
            state = state,
            inputTransformation = remember { stepsListInputTransformation() },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = minLines),
            textStyle = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, lineHeight = 21.sp, color = c.ink),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
        )
    }
}

@Composable
private fun PreviewToggleRow(open: Boolean, onToggle: () -> Unit) {
    val c = MaterialTheme.grove
    val rotation by animateFloatAsState(if (open) 90f else 0f, label = "bugPreviewCaret")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Text(
            "▸",
            fontFamily = PlexSans, fontSize = 10.sp, color = c.accent,
            modifier = Modifier.rotate(rotation),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "Show exactly what gets sent",
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.accent,
        )
    }
}

@Composable
private fun BugPayloadPreview(payload: List<BugPayloadRow>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.grove
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        payload.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    row.key,
                    fontFamily = PlexMono, fontSize = 11.5.sp, color = c.synProp,
                    modifier = Modifier.width(118.dp),
                )
                Text(row.value, fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink)
            }
        }
        DashedDivider(color = c.line2, modifier = Modifier.padding(top = 6.dp, bottom = 9.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.red, fontWeight = FontWeight.SemiBold)) { append("never included: ") }
                append("name, email, account ID, location, contacts, file contents")
            },
            fontFamily = PlexMono, fontSize = 11.5.sp, lineHeight = 1.6.em, color = c.ink3,
        )
    }
}

@Composable
private fun DashedDivider(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f),
        )
    }
}

@Composable
private fun PrivacyNote() {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.accentSoft)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text("⚿", fontSize = 14.sp, color = c.accent, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Goes straight to the developer, by email or as a GitHub issue you submit. " +
                "Never sent to Google or filed anywhere automatically.",
            fontFamily = PlexSans, fontSize = 11.5.sp, lineHeight = 1.6.em, color = c.ink2,
        )
    }
}

/**
 * The bug-report footer's action button. [primary] is the solid accent "Send report via
 * email" button (with a soft shadow when enabled); the secondary variant is the outlined
 * "Open an issue on GitHub" button. Both grey out until the description is filled in.
 */
@Composable
private fun BugReportButton(label: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    val shape = RoundedCornerShape(14.dp)
    val bg = when {
        !enabled -> c.surface3
        primary -> c.accent
        else -> c.surface
    }
    val fg = when {
        !enabled -> c.ink3
        primary -> c.accentInk
        else -> c.ink
    }
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (enabled && primary) {
                    Modifier.shadow(6.dp, shape, clip = false, ambientColor = ButtonShadowColor, spotColor = ButtonShadowColor)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(bg)
            .then(if (!primary) Modifier.border(1.dp, if (enabled) c.line2 else c.line, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = fg,
        )
    }
}

private val ButtonShadowColor = Color(0x668A5A2B)

private fun deviceInfo(): DeviceInfo = DeviceInfo(
    appVersion = BuildConfig.VERSION_NAME,
    buildNumber = BuildConfig.VERSION_CODE.toString(),
    androidVersion = Build.VERSION.RELEASE,
    deviceModel = Build.MODEL,
    locale = Locale.getDefault().toLanguageTag(),
)

private fun bugPayload(includeDeviceInfo: Boolean, includeErrorLog: Boolean, errorLogLines: List<String>?): List<BugPayloadRow> {
    val rows = mutableListOf<BugPayloadRow>()
    if (includeDeviceInfo) {
        val info = deviceInfo()
        rows += BugPayloadRow("App version", info.appVersion)
        rows += BugPayloadRow("Build number", info.buildNumber)
        rows += BugPayloadRow("Android version", info.androidVersion)
        rows += BugPayloadRow("Device model", info.deviceModel)
        rows += BugPayloadRow("Locale", info.locale)
    }
    rows += BugPayloadRow("Report time", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
    if (includeErrorLog) {
        rows += when {
            errorLogLines == null -> BugPayloadRow("Error log", "couldn't capture (unavailable on this device)")
            errorLogLines.isEmpty() -> BugPayloadRow("Error log", "captured, but this session has no log lines yet")
            else -> {
                val n = errorLogLines.size
                BugPayloadRow("Error log", "$n line${if (n == 1) "" else "s"} captured from this session")
            }
        }
    }
    return rows
}

// i18n-begin: the report body is read by the developer, stays English
/** Builds the email body (and the "copy as text" fallback) from exactly the
 *  toggles the user has on, with blank lines between sections for readability. */
private fun formatBugReportEmail(
    description: String,
    steps: String,
    deviceInfo: DeviceInfo?,
    errorLogLines: List<String>?,
): String = buildString {
    appendLine("Description")
    appendLine(description.trim())

    if (steps.isNotBlank()) {
        appendLine()
        appendLine("Steps to reproduce")
        appendLine(steps.trim())
    }

    if (deviceInfo != null) {
        appendLine()
        appendLine("Device & app info")
        appendLine("App version: ${deviceInfo.appVersion}")
        appendLine("Build number: ${deviceInfo.buildNumber}")
        appendLine("Android version: ${deviceInfo.androidVersion}")
        appendLine("Device model: ${deviceInfo.deviceModel}")
        appendLine("Locale: ${deviceInfo.locale}")
    }

    if (errorLogLines != null) {
        appendLine()
        appendLine("Recent error log")
        when {
            errorLogLines.isEmpty() -> appendLine("(captured, but this session has no log lines yet)")
            else -> errorLogLines.forEach { appendLine(it) }
        }
    }

    appendLine()
    appendLine("Report time: ${Instant.now().truncatedTo(ChronoUnit.SECONDS)}")
}.trim()
// i18n-end
