package com.rrajath.grove.widget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.glance.appwidget.updateAll
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.capture.CaptureInserter
import com.rrajath.grove.org.DateShorthandParser
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.org.ShorthandParse
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.components.PlanningDatesScreen
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.headlineAtLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Host for the widget's "+" button: a small composer overlaid on whatever app the
 * user was in, reproducing `design/Grove.dc.html`'s widget quick-capture sheet
 * (`wCapOpen`) rather than the full multi-step Capture Picker/template flow.
 * Same one-shot-task pattern as [com.rrajath.grove.ui.reminders.RescheduleActivity]:
 * empty `taskAffinity`, finishes back to whatever was underneath, and the write
 * runs on [GroveApplication.appScope] so finishing early can't cancel it.
 */
class WidgetQuickAddActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Manages the IME inset itself (via Modifier.imePadding() below) rather
        // than leaning on windowSoftInputMode's window-resize behavior, which is
        // unreliable on a translucent/floating window like this one.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val app = LocalContext.current.applicationContext as GroveApplication
            var theme by remember { mutableStateOf<GroveSettings?>(null) }
            LaunchedEffect(Unit) { theme = app.settingsRepository.settings.first() }
            theme?.let { s ->
                GroveTheme(theme = s.theme) {
                    QuickAddSheet(onDismiss = ::finish)
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, WidgetQuickAddActivity::class.java)
    }
}

private enum class QuickAddDate(val label: String, val shorthand: String?) {
    TODAY("Today", "today"),
    TOMORROW("Tomorrow", "tomorrow"),
    WEEKEND("This weekend", "weekend"),
    NEXT_WEEK("Next week", "next week"),
    NONE("No date", null),
}

private val PRIORITIES: List<Char?> = listOf(null, 'A', 'B', 'C')

@Composable
private fun QuickAddSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GroveApplication

    var keywords by remember { mutableStateOf(listOf("TODO")) }
    // Fixed by Settings § Widget's "Quick add target" (a file, or a heading in
    // one), not picked here: a vault with hundreds of files made a per-capture
    // file menu more hindrance than help.
    var target by remember { mutableStateOf<QuickAddTarget?>(null) }
    LaunchedEffect(Unit) {
        val settings = app.settingsRepository.settings.first()
        keywords = app.keywords.first().active.ifEmpty { listOf("TODO") }
        target = QuickAddTarget.from(settings)
    }

    var text by remember { mutableStateOf("") }
    var keyword by remember(keywords) { mutableStateOf(keywords.first()) }
    var priority by remember { mutableStateOf<Char?>(null) }
    // Exactly one of these two is "live" at a time: picking a preset clears
    // customDate, confirming the custom picker clears dateOption (null). Kept
    // separate rather than one eagerly-resolved OrgTimestamp so a preset like
    // "Today" still resolves at *send* time (see savedDate below), staying
    // correct even if the sheet is left open across midnight.
    var dateOption by remember { mutableStateOf<QuickAddDate?>(QuickAddDate.TODAY) }
    var customDate by remember { mutableStateOf<OrgTimestamp?>(null) }
    var dateMenuOpen by remember { mutableStateOf(false) }
    var customDateOpen by remember { mutableStateOf(false) }

    // Resolved lazily on every recomposition (not eagerly at pick time) so a
    // preset like "Today" still reflects the actual date whenever it's read,
    // staying correct even if the sheet is left open across midnight.
    val resolvedPresetDate = dateOption?.shorthand?.let { sh ->
        (DateShorthandParser.parse(sh, LocalDate.now()) as? ShorthandParse.Ok)?.value?.date
            ?.let { OrgTimestamp(it, active = true) }
    }
    val selectedDate = customDate ?: resolvedPresetDate
    val dateChipLabel = customDate?.formatHuman() ?: dateOption?.label ?: QuickAddDate.NONE.label

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onDismiss),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 10.dp)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.grove.surface)
                // Swallow taps on the sheet's own empty space so they don't fall
                // through to the scrim and dismiss; children consume their own taps first.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeywordChip(keyword) { keyword = keywords.next(keyword) }
                Spacer(Modifier.width(9.dp))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    textStyle = TextStyle(color = MaterialTheme.grove.ink, fontSize = 15.sp, fontFamily = PlexSans),
                    cursorBrush = SolidColor(MaterialTheme.grove.accent),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text("What needs doing?", color = MaterialTheme.grove.ink3, fontSize = 15.sp, fontFamily = PlexSans)
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(11.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.grove.line))
            Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    ComposerChip(dateChipLabel, active = dateOption != QuickAddDate.NONE || customDate != null) {
                        dateMenuOpen = true
                    }
                    DropdownMenu(
                        expanded = dateMenuOpen,
                        onDismissRequest = { dateMenuOpen = false },
                        containerColor = MaterialTheme.grove.surface,
                    ) {
                        QuickAddDate.entries.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.label, fontFamily = PlexSans, color = MaterialTheme.grove.ink) },
                                onClick = { dateOption = preset; customDate = null; dateMenuOpen = false },
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.grove.line)
                        DropdownMenuItem(
                            text = { Text("Custom date…", fontFamily = PlexSans, color = MaterialTheme.grove.accent) },
                            onClick = { dateMenuOpen = false; customDateOpen = true },
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                ComposerChip(priority?.let { "#$it" } ?: "Priority", active = priority != null) {
                    priority = PRIORITIES.next(priority)
                }
                Spacer(Modifier.width(7.dp))
                // Plain label, not a chip: the target is changed in Settings § Widget.
                Text(
                    target?.label.orEmpty(),
                    color = MaterialTheme.grove.ink3,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = PlexSans,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                val canSend = text.isNotBlank() && target != null
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (canSend) MaterialTheme.grove.accent else MaterialTheme.grove.surface2)
                        .clickable(enabled = canSend) {
                            val savedText = text.trim()
                            val savedKeyword = keyword
                            val savedPriority = priority
                            val savedDate = selectedDate
                            val savedTarget = target ?: return@clickable
                            app.appScope.launch {
                                val headingFound = submitQuickAdd(app, savedTarget, savedKeyword, savedPriority, savedText, savedDate)
                                // The toast waits for the write so a missing heading can be
                                // reported; the app context outlives this finished activity.
                                val message = if (headingFound) {
                                    "Added to ${savedTarget.label}"
                                } else {
                                    "Heading \"${savedTarget.headingPath.last()}\" not found. " +
                                        "Added to the bottom of ${savedTarget.fileName.removeSuffix(".org")}"
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(app, message, if (headingFound) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                                }
                            }
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "↑",
                        color = if (canSend) MaterialTheme.grove.accentInk else MaterialTheme.grove.ink3,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = PlexSans,
                    )
                }
            }
        }
    }

    if (customDateOpen) {
        PlanningDatesScreen(
            title = text.ifBlank { "New task" },
            scheduled = selectedDate,
            deadline = null,
            active = emptyList(),
            focus = PlanningKind.SCHEDULED,
            onDismiss = { customDateOpen = false },
            // Quick-add only picks a scheduled day for a task that does not exist
            // yet; the deadline and active halves of the result are never read.
            onConfirm = { sched, _, _ -> dateOption = null; customDate = sched; customDateOpen = false },
        )
    }
}

@Composable
private fun KeywordChip(keyword: String, onClick: () -> Unit) {
    val (fg, bg) = when (keyword) {
        "NEXT" -> MaterialTheme.grove.green to MaterialTheme.grove.greenSoft
        "WAITING" -> MaterialTheme.grove.ink3 to MaterialTheme.grove.surface2
        else -> MaterialTheme.grove.synTodo to MaterialTheme.grove.amberSoft
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Text(keyword, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = PlexMono)
    }
}

@Composable
private fun ComposerChip(label: String, active: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) c.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (active) c.accent else c.ink2,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = PlexSans,
        )
    }
}

private fun <T> List<T>.next(current: T): T {
    val i = indexOf(current)
    return if (i == -1 || isEmpty()) current else this[(i + 1) % size]
}

/**
 * Adds the task as the last child of [target]'s heading, or as a top-level heading
 * at the bottom of its file when it names no heading (creating the file if
 * needed). A heading that no longer exists (renamed, deleted, or not synced yet)
 * falls back to the bottom of the file so the typed task is never lost; returns
 * false in that case so the caller can say so. Priority/scheduled are applied the
 * same way every other mutation surface does ([OrgMutations.setPriority]/
 * [OrgMutations.setScheduled]) so the on-disk formatting is identical to a
 * heading edited from the Outline or Agenda.
 */
private suspend fun submitQuickAdd(
    app: GroveApplication,
    target: QuickAddTarget,
    keyword: String,
    priority: Char?,
    title: String,
    date: OrgTimestamp?,
): Boolean {
    if (title.isBlank()) return true
    val notebook = target.fileName
    val vault = app.vault.filterNotNull().first()
    if (vault.open(notebook) == null) vault.createNotebook(notebook)
    val orgKeywords = OrgKeywords.parse(app.settingsRepository.settings.first().todoKeywords)
    var headingFound = true
    val text = withContext(Dispatchers.Default) {
        val baseText = vault.open(notebook)?.text ?: ""
        val entry = "$keyword $title"
        val insertion = CaptureInserter.insertUnderPath(baseText, target.headingPath, entry, orgKeywords)
            ?: CaptureInserter.insertUnderPath(baseText, emptyList(), entry, orgKeywords)!!
                .also { headingFound = false }
        var text = insertion.newText
        var doc = OrgParser.parse(text, orgKeywords)
        var headline = doc.headlineAtLine(insertion.insertedAtLine)
        if (priority != null && headline != null) {
            text = OrgMutations.setPriority(doc, headline, priority)
            doc = OrgParser.parse(text, orgKeywords)
            headline = doc.headlineAtLine(insertion.insertedAtLine)
        }
        if (date != null && headline != null) {
            text = OrgMutations.setScheduled(doc, headline, date)
        }
        text
    }
    vault.save(notebook, text)
    app.reindexNow(notebook, text)
    app.syncManager.requestReindex(notebook, text, "widget quick add")
    LedgerWidget().updateAll(app)
    return headingFound
}
