package com.rrajath.grove.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.org.DateShorthandParser
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.org.Repeater
import com.rrajath.grove.org.RepeaterType
import com.rrajath.grove.org.ShorthandParse
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * SCHEDULED, DEADLINE and bare active timestamps on one canvas ("Dates B — tabs"
 * in `design/Grove.dc.html`).
 *
 * A three-way segmented control picks which surface the calendar edits:
 * SCHEDULED (blue) and DEADLINE (red) each hold one day; ACTIVE (violet) holds
 * any number of event timestamps and one range, made by long-pressing a day and
 * dragging across others. A day already carrying one of the *other* two kinds
 * shows an outlined cell in that kind's colour so it stays visible while you
 * work on the current tab (distinct from the small dots that mark other notes'
 * planned days).
 * Confirming commits all three at once — [onConfirm] takes the triple — so the
 * [focus] tab only decides where you start, not what gets written.
 *
 * For several notes at once (outline bulk edit), [mixed] names the kinds whose
 * dates differ between them. Such a tab starts empty and offers "Remove from
 * all"; [onConfirmBulk], when set, replaces [onConfirm] and also receives the
 * kinds the user removed, since an empty mixed field and a removed one look the
 * same by value.
 *
 * The dedicated active-timestamp line is the only one this editor manages;
 * active stamps a note carries inline in its prose are shown as read-mode chips
 * but are edited in the raw editor, so the caller passes in only
 * [com.rrajath.grove.org.OrgMutations.dedicatedActiveTimestamps].
 *
 * Presented as a full-window [Dialog] rather than a nav destination so the entry
 * points (agenda swipe, outline row, metadata sheet, reminder reschedule, quick
 * add) keep their existing local state and ViewModel wiring.
 */

/**
 * Every other note's SCHEDULED/DEADLINE/active day, for marking days that
 * already have something on them on the calendar (mirrors Emacs org-mode's
 * scheduling calendar), shared by [PlanningDatesScreen] and
 * [InsertTimestampScreen] rather than each holding its own copy (PERFORMANCE_AUDIT
 * 2026-09-16 F2/H5). `distinctUntilChanged` on each Room source skips
 * re-parsing every planned/active timestamp when an unrelated column changed
 * but these two queries' results didn't.
 */
private fun plannedDatesFlow(app: GroveApplication): Flow<Set<LocalDate>> =
    combine(
        app.database.indexDao().plannedTimestamps().distinctUntilChanged(),
        app.database.indexDao().plannedActiveTimestamps().distinctUntilChanged(),
    ) { planned, activeRows ->
        val days = planned.mapNotNullTo(mutableSetOf()) { OrgTimestamp.parse(it)?.date }
        activeRows.forEach { row ->
            OrgTimestamp.parseAll(row).forEach { ts ->
                var d = ts.date
                val end = ts.rangeEnd ?: ts.date
                while (!d.isAfter(end)) {
                    days.add(d)
                    d = d.plusDays(1)
                }
            }
        }
        days
    }.flowOn(Dispatchers.Default)

@Composable
fun PlanningDatesScreen(
    title: String,
    scheduled: OrgTimestamp?,
    deadline: OrgTimestamp?,
    active: List<OrgTimestamp>,
    focus: PlanningKind,
    onDismiss: () -> Unit,
    onConfirm: (scheduled: OrgTimestamp?, deadline: OrgTimestamp?, active: List<OrgTimestamp>) -> Unit,
    mixed: Set<PlanningKind> = emptySet(),
    onConfirmBulk: (
        (scheduled: OrgTimestamp?, deadline: OrgTimestamp?, active: List<OrgTimestamp>, removed: Set<PlanningKind>) -> Unit
    )? = null,
) {
    val c = MaterialTheme.grove
    val today = remember { LocalDate.now() }

    // Every other note's SCHEDULED/DEADLINE/active day, so the calendar can mark
    // days that already have something on them (mirrors Emacs org-mode's
    // scheduling calendar) with a small dot, distinct from this note's own dates.
    val app = LocalContext.current.applicationContext as GroveApplication
    val plannedDates by remember(app) { plannedDatesFlow(app) }.collectAsState(initial = emptySet())

    var sched by remember { mutableStateOf(scheduled) }
    var dead by remember { mutableStateOf(deadline) }
    val acts = remember { active.sortedBy { it.date }.toMutableStateList() }
    var actI by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(focus) }
    var shorthand by remember { mutableStateOf("") }
    // Transient one-liner under the calendar ("Removed …", "Cleared …").
    var note by remember { mutableStateOf("") }
    // Mixed kinds the user chose to remove from every selected note.
    var removed by remember { mutableStateOf(emptySet<PlanningKind>()) }

    val startDate = when (focus) {
        PlanningKind.SCHEDULED -> scheduled?.date
        PlanningKind.DEADLINE -> deadline?.date
        PlanningKind.ACTIVE -> active.firstOrNull()?.date
    } ?: today
    var month by remember { mutableStateOf(YearMonth.from(startDate)) }

    val accent = when (tab) {
        PlanningKind.SCHEDULED -> c.blue
        PlanningKind.DEADLINE -> c.red
        PlanningKind.ACTIVE -> c.violet
    }
    val accentSoft = when (tab) {
        PlanningKind.SCHEDULED -> c.blueSoft
        PlanningKind.DEADLINE -> c.redSoft
        PlanningKind.ACTIVE -> c.violetSoft
    }
    val isActiveTab = tab == PlanningKind.ACTIVE

    val entries: List<OrgTimestamp> = when (tab) {
        PlanningKind.SCHEDULED -> listOfNotNull(sched)
        PlanningKind.DEADLINE -> listOfNotNull(dead)
        PlanningKind.ACTIVE -> acts
    }
    val current: OrgTimestamp? = when (tab) {
        PlanningKind.SCHEDULED -> sched
        PlanningKind.DEADLINE -> dead
        PlanningKind.ACTIVE -> acts.getOrNull(actI)
    }
    val count = entries.size

    // This note's own dates for the *other* two kinds, kept on the calendar as
    // outlined accent cells while you edit the current tab. Distinct from the
    // small dots that mark days other notes already have something on.
    val secondaryMarks: Map<LocalDate, Color> = buildMap {
        if (tab != PlanningKind.ACTIVE) {
            acts.forEach { ts ->
                var d = ts.date
                val end = ts.rangeEnd ?: ts.date
                while (!d.isAfter(end)) {
                    put(d, c.violet)
                    d = d.plusDays(1)
                }
            }
        }
        if (tab != PlanningKind.SCHEDULED) sched?.date?.let { put(it, c.blue) }
        if (tab != PlanningKind.DEADLINE) dead?.date?.let { put(it, c.red) }
    }

    fun selectTab(next: PlanningKind) {
        tab = next
        note = ""
        val d = when (next) {
            PlanningKind.SCHEDULED -> sched?.date
            PlanningKind.DEADLINE -> dead?.date
            PlanningKind.ACTIVE -> acts.getOrNull(actI)?.date
        }
        if (d != null) month = YearMonth.from(d)
    }

    fun patchCurrent(block: (OrgTimestamp) -> OrgTimestamp) {
        when (tab) {
            PlanningKind.SCHEDULED -> sched = block(sched ?: OrgTimestamp(today))
            PlanningKind.DEADLINE -> dead = block(dead ?: OrgTimestamp(today))
            PlanningKind.ACTIVE ->
                if (acts.isEmpty()) {
                    acts.add(block(OrgTimestamp(today)))
                    actI = 0
                } else {
                    val i = actI.coerceIn(0, acts.lastIndex)
                    acts[i] = block(acts[i])
                    actI = i
                }
        }
    }

    fun tapDay(day: LocalDate) {
        note = ""
        when (tab) {
            PlanningKind.ACTIVE -> {
                val i = acts.indexOfFirst { it.covers(day) }
                if (i >= 0) {
                    val removed = acts.removeAt(i)
                    actI = actI.coerceIn(0, maxOf(0, acts.lastIndex))
                    note = "Removed ${removed.formatHuman(today)}"
                } else {
                    acts.add(OrgTimestamp(day))
                    acts.sortBy { it.date }
                    actI = acts.indexOfFirst { it.date == day && it.rangeEnd == null }.coerceAtLeast(0)
                    month = YearMonth.from(day)
                }
            }
            PlanningKind.SCHEDULED ->
                if (sched?.covers(day) == true) {
                    sched = null
                    note = "Cleared — tap a day to set it again."
                } else {
                    sched = (sched ?: OrgTimestamp(day)).copy(date = day, rangeEnd = null)
                    month = YearMonth.from(day)
                }
            PlanningKind.DEADLINE ->
                if (dead?.covers(day) == true) {
                    dead = null
                    note = "Cleared — tap a day to set it again."
                } else {
                    dead = (dead ?: OrgTimestamp(day)).copy(date = day, rangeEnd = null)
                    month = YearMonth.from(day)
                }
        }
    }

    // Long-press-drag on the ACTIVE tab only; SCHEDULED/DEADLINE stay single-day.
    fun rangeDays(lo: LocalDate, hi: LocalDate) {
        acts.removeAll { it.covers(lo) || it.covers(hi) }
        acts.add(OrgTimestamp(lo, rangeEnd = hi))
        acts.sortBy { it.date }
        actI = acts.indexOfFirst { it.date == lo && it.rangeEnd == hi }.coerceAtLeast(0)
        note = ""
        month = YearMonth.from(lo)
    }

    val parse = remember(shorthand, today) { DateShorthandParser.parse(shorthand, today) }
    val parsedOk = parse as? ShorthandParse.Ok

    val shorthandTarget = DateShorthandParser.targetPrefix(shorthand) ?: tab
    val shorthandAccent = when (shorthandTarget) {
        PlanningKind.SCHEDULED -> c.blue
        PlanningKind.DEADLINE -> c.red
        PlanningKind.ACTIVE -> c.violet
    }

    fun applyShorthand() {
        val sh = parsedOk?.value ?: return
        val kind = sh.target ?: tab
        fun merged(base: OrgTimestamp?): OrgTimestamp {
            var n = base ?: OrgTimestamp(sh.date ?: today)
            sh.date?.let { n = n.copy(date = it) }
            sh.time?.let { n = n.copy(time = it, endTime = sh.endTime) }
            sh.repeater?.let { n = n.copy(repeater = it) }
            return n
        }
        when (kind) {
            PlanningKind.SCHEDULED -> sched = merged(sched)
            PlanningKind.DEADLINE -> dead = merged(dead)
            PlanningKind.ACTIVE -> {
                val n = merged(acts.getOrNull(actI))
                if (acts.isEmpty()) acts.add(n) else acts[actI.coerceIn(0, acts.lastIndex)] = n
                acts.sortBy { it.date }
                actI = acts.indexOf(n).coerceAtLeast(0)
            }
        }
        tab = kind
        month = YearMonth.from(
            when (kind) {
                PlanningKind.SCHEDULED -> sched?.date
                PlanningKind.DEADLINE -> dead?.date
                PlanningKind.ACTIVE -> acts.getOrNull(actI)?.date
            } ?: month.atDay(1),
        )
        shorthand = ""
        note = ""
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // This Dialog opens its own platform Window (decorFitsSystemWindows = false
        // for edge-to-edge), separate from the Activity window GroveTheme's
        // SideEffect styles — so the status/nav bar icon appearance has to be
        // re-asserted here or it falls back to the platform default.
        val dialogView = LocalView.current
        if (!dialogView.isInEditMode) {
            SideEffect {
                val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(window, dialogView)
                controller.isAppearanceLightStatusBars = !c.isDark
                controller.isAppearanceLightNavigationBars = !c.isDark
            }
        }
        Surface(Modifier.fillMaxSize(), color = c.bg) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                // Before Android 9 a new window gives initial focus to its first
                // focusable view even in touch mode: here the shorthand field, so
                // the keyboard opened on its own over the tab row. This blank
                // target ahead of it takes that focus instead (same fix as
                // MetadataSheet); API 28+ never assigns it.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    Box(Modifier.clearAndSetSemantics {}.focusable())
                }

                // ---- header ----------------------------------------------------
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                            tint = c.ink,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Text(
                        title,
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 15.5.sp, color = c.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Clear",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp, color = c.ink2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable {
                                sched = null
                                dead = null
                                acts.clear()
                                actI = 0
                                note = ""
                                removed = mixed
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                // ---- scrolling canvas ----------------------------------------
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
                ) {
                    ShorthandBox(
                        value = shorthand,
                        onValueChange = { shorthand = it },
                        accent = shorthandAccent,
                        canApply = parsedOk != null,
                        onApply = ::applyShorthand,
                    )

                    if (parse != null) {
                        Text(
                            shorthandEcho(parse, today, tab),
                            fontFamily = PlexMono, fontSize = 11.5.sp,
                            color = if (parsedOk != null) c.green else c.red,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 7.dp, bottom = 2.dp),
                        )
                    }

                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        ShorthandHints.forEach { hint ->
                            Text(
                                hint,
                                fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink3,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.surface2)
                                    .clickable {
                                        shorthand = if (shorthand.isEmpty()) hint else "$shorthand $hint"
                                    }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(13.dp))

                    AccentCalendar(
                        month = month,
                        today = today,
                        entries = entries,
                        selectedIndex = if (isActiveTab) actI else 0,
                        perEntrySelection = isActiveTab,
                        accent = accent,
                        accentSoft = accentSoft,
                        secondaryMarks = secondaryMarks,
                        plannedDates = plannedDates,
                        rangeEnabled = isActiveTab,
                        onPrev = { month = month.minusMonths(1) },
                        onNext = { month = month.plusMonths(1) },
                        onTapDay = ::tapDay,
                        onRange = ::rangeDays,
                    )

                    if (note.isNotEmpty()) {
                        Text(
                            note,
                            fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink2, lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 3.dp).padding(top = 10.dp),
                        )
                    }

                    // ---- tab control -------------------------------------------
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TabButton(Icons.Outlined.CalendarMonth, "SCHEDULED", 0,
                            tab == PlanningKind.SCHEDULED, c.blue, c.blueSoft,
                        ) {
                            selectTab(PlanningKind.SCHEDULED)
                        }
                        TabButton(Icons.Filled.Flag, "DEADLINE", 0,
                            tab == PlanningKind.DEADLINE, c.red, c.redSoft,
                        ) {
                            selectTab(PlanningKind.DEADLINE)
                        }
                        TabButton(Icons.Filled.FiberManualRecord, "ACTIVE", acts.size, isActiveTab, c.violet, c.violetSoft,
                            badgeAnchor = NewAnchors.PLANNING_DATES_ACTIVE,
                        ) { selectTab(PlanningKind.ACTIVE) }
                    }
                    // Retire the "NEW" trail once the user actually opens the ACTIVE tab.
                    if (isActiveTab) MarkNewFeatureSeen(NewAnchors.PLANNING_DATES_ACTIVE)

                    Text(
                        if (isActiveTab)
                            "Long-press and drag for a range · tap days for separate timestamps · tap one again to remove it"
                        else
                            "One day only. Tapping another day moves it; tapping it again clears it.",
                        fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink2, lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 3.dp).padding(top = 10.dp),
                    )

                    if (count == 0 && tab in mixed && tab !in removed) {
                        Text(
                            "Selected notes have different ${tab.name} dates.",
                            fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink2, lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 3.dp).padding(top = 14.dp),
                        )
                        // Same chrome as the header's Clear action.
                        Text(
                            "Remove from all",
                            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp, color = c.ink2,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable {
                                    removed = removed + tab
                                    note = "${tab.name} will be removed from every selected note."
                                }
                                .padding(horizontal = 3.dp, vertical = 8.dp),
                        )
                    }

                    if (count > 0) {
                        Text(
                            if (isActiveTab) "$count timestamp${plural(count)}" else "1 timestamp",
                            fontFamily = PlexMono, fontWeight = FontWeight.Bold,
                            fontSize = 11.sp, letterSpacing = 0.7.sp,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 3.dp).padding(top = 14.dp),
                        )
                    }

                    if (count == 0) {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(top = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            datePresets(today).forEach { (name, date) ->
                                Chip(
                                    label = "$name · ${date.format(ShortDate)}",
                                    selected = false,
                                    accent = accent,
                                    onClick = { tapDay(date) },
                                )
                            }
                        }
                    } else {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            entries.forEachIndexed { i, ts ->
                                StampChip(
                                    label = ts.formatHuman(today),
                                    selected = !isActiveTab || i == actI,
                                    accent = accent,
                                    accentSoft = accentSoft,
                                    onClick = {
                                        if (isActiveTab) {
                                            actI = i
                                            month = YearMonth.from(ts.date)
                                        }
                                    },
                                    onDelete = {
                                        when (tab) {
                                            PlanningKind.SCHEDULED -> sched = null
                                            PlanningKind.DEADLINE -> dead = null
                                            PlanningKind.ACTIVE -> {
                                                acts.removeAt(i)
                                                actI = actI.coerceIn(0, maxOf(0, acts.lastIndex))
                                            }
                                        }
                                        note = ""
                                    },
                                )
                            }
                        }

                        current?.let { value ->
                            Spacer(Modifier.height(4.dp))
                            StampEditor(
                                value = value,
                                today = today,
                                accent = accent,
                                accentSoft = accentSoft,
                                onChange = { next -> patchCurrent { next } },
                            )
                        }
                    }
                }

                // ---- footer --------------------------------------------------
                HorizontalDivider(color = c.line)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
                ) {
                    Column(Modifier.padding(horizontal = 2.dp).padding(top = 1.dp, bottom = 10.dp)) {
                        acts.forEach { ts ->
                            Text(
                                ts.format(),
                                fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 20.sp,
                                color = c.violet,
                            )
                        }
                        Text(
                            "SCHEDULED: " + (sched?.format() ?: "-"),
                            fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 20.sp,
                            color = if (sched != null) c.synTs else c.ink3,
                        )
                        Text(
                            "DEADLINE:  " + (dead?.format() ?: "-"),
                            fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 20.sp,
                            color = if (dead != null) c.red else c.ink3,
                        )
                    }
                    Text(
                        "Apply dates",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp, color = c.accentInk,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(c.accent)
                            .clickable {
                                onConfirmBulk?.invoke(sched, dead, acts.toList(), removed)
                                    ?: onConfirm(sched, dead, acts.toList())
                            }
                            .padding(vertical = 13.dp),
                    )
                }
            }
        }
    }
}

/**
 * Single-day, no-tabs picker for inserting one bare timestamp at the editor
 * cursor (`ic_...` clock button's long-press in
 * [com.rrajath.grove.ui.editor.EditorToolbar]) -- inactive by default (a plain
 * logged moment), with a link under the calendar to flip it active. Not a
 * SCHEDULED/DEADLINE/ACTIVE planning line, so it doesn't reuse
 * [PlanningDatesScreen]'s tri-kind state or `onConfirm` shape. It still shares
 * that screen's calendar, day-preset chips and time editor via the private
 * helpers below.
 */
@Composable
fun InsertTimestampScreen(
    title: String,
    initial: OrgTimestamp,
    onDismiss: () -> Unit,
    onConfirm: (OrgTimestamp) -> Unit,
) {
    val c = MaterialTheme.grove
    val today = remember { LocalDate.now() }
    val accent = c.synTs
    // Soft accents elsewhere in this file are just the accent colour at ~0.16
    // alpha for a given theme (e.g. blueSoft's hex matches blue's), so this
    // mirrors that instead of needing a synTsSoft token per theme.
    val accentSoft = remember(accent) { accent.copy(alpha = 0.16f) }

    var ts by remember { mutableStateOf(initial) }
    var month by remember { mutableStateOf(YearMonth.from(initial.date)) }

    // Flash the preview once per toggle: pop in a background chip behind it,
    // then fade it back out, as confirmation that the tap registered. `tapId`
    // keys the revert effect, so a fresh tap restarts (not stacks) the timer
    // and the chip only ever cycles on-then-off once.
    var flashOn by remember { mutableStateOf(false) }
    var tapId by remember { mutableIntStateOf(0) }
    val previewFlashBg by animateColorAsState(
        targetValue = if (flashOn) c.surface3 else Color.Transparent,
        animationSpec = tween(180),
        label = "timestampPreviewFlash",
    )
    LaunchedEffect(tapId) {
        if (tapId > 0) {
            delay(260.milliseconds)
            flashOn = false
        }
    }
    fun toggleActive() {
        ts = ts.copy(active = !ts.active)
        flashOn = true
        tapId++
    }

    val app = LocalContext.current.applicationContext as GroveApplication
    val plannedDates by remember(app) { plannedDatesFlow(app) }.collectAsState(initial = emptySet())

    fun tapDay(day: LocalDate) {
        ts = ts.copy(date = day, rangeEnd = null)
        month = YearMonth.from(day)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = LocalView.current
        if (!dialogView.isInEditMode) {
            SideEffect {
                val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(window, dialogView)
                controller.isAppearanceLightStatusBars = !c.isDark
                controller.isAppearanceLightNavigationBars = !c.isDark
            }
        }
        Surface(Modifier.fillMaxSize(), color = c.bg) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {

                // ---- header ----------------------------------------------------
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                            tint = c.ink,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Text(
                        title,
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 15.5.sp, color = c.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ---- scrolling canvas ----------------------------------------
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
                ) {
                    AccentCalendar(
                        month = month,
                        today = today,
                        entries = listOf(ts),
                        selectedIndex = 0,
                        perEntrySelection = false,
                        accent = accent,
                        accentSoft = accentSoft,
                        secondaryMarks = emptyMap(),
                        plannedDates = plannedDates,
                        rangeEnabled = false,
                        onPrev = { month = month.minusMonths(1) },
                        onNext = { month = month.plusMonths(1) },
                        onTapDay = ::tapDay,
                        onRange = { _, _ -> },
                    )

                    Text(
                        if (ts.active) {
                            "Inserts an active timestamp at the cursor; it may show up on the agenda."
                        } else {
                            "Inserts an inactive timestamp at the cursor; a plain logged date/time that won't show up on the agenda."
                        },
                        fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink2, lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 3.dp).padding(top = 10.dp),
                    )
                    Text(
                        if (ts.active) "Make it an inactive timestamp?" else "Make it an active timestamp?",
                        fontFamily = PlexSans, fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp, color = c.synLink,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .padding(top = 4.dp)
                            .clickable { toggleActive() },
                    )

                    StampEditor(
                        value = ts,
                        today = today,
                        accent = accent,
                        accentSoft = accentSoft,
                        onChange = { next -> ts = next },
                    )
                }

                // ---- footer --------------------------------------------------
                HorizontalDivider(color = c.line)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
                ) {
                    Column(Modifier.padding(horizontal = 2.dp).padding(top = 1.dp, bottom = 10.dp)) {
                        Text(
                            ts.format(),
                            fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 20.sp,
                            color = c.synTs,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(previewFlashBg)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        "Apply date",
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp, color = c.accentInk,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(c.accent)
                            .clickable { onConfirm(ts) }
                            .padding(vertical = 13.dp),
                    )
                }
            }
        }
    }
}

private val ShorthandHints = listOf("fri", "+2w", "aug 3", "10-11am", "++1w", "a: sat")

/** `covers` — is [day] within this stamp's span (a range end, or the single day)? */
private fun OrgTimestamp.covers(day: LocalDate): Boolean =
    !day.isBefore(date) && !day.isAfter(rangeEnd ?: date)

// ---- calendar ---------------------------------------------------------------

/**
 * One month, single-accent: the current tab's timestamps fill their days (a
 * range draws as one connected pill), the other kinds' days ([secondaryMarks])
 * draw as an outlined cell in that kind's colour, and days other notes already
 * use carry a small dot.
 * A tap sets/moves/clears a day; on the ACTIVE tab a long-press then drag paints
 * a range. No month swipe — the ‹ / › arrows are the only way to change months.
 */
@Composable
private fun AccentCalendar(
    month: YearMonth,
    today: LocalDate,
    entries: List<OrgTimestamp>,
    selectedIndex: Int,
    perEntrySelection: Boolean,
    accent: Color,
    accentSoft: Color,
    secondaryMarks: Map<LocalDate, Color>,
    plannedDates: Set<LocalDate>,
    rangeEnabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTapDay: (LocalDate) -> Unit,
    onRange: (LocalDate, LocalDate) -> Unit,
) {
    val c = MaterialTheme.grove
    val density = LocalDensity.current

    // Sunday-first grid: DayOfWeek.value is Mon=1..Sun=7, so % 7 puts Sunday at 0.
    val leading = month.atDay(1).dayOfWeek.value % 7
    val len = month.lengthOfMonth()
    val weeks = (leading + len + 6) / 7
    val cellHeightPx = with(density) { 38.dp.toPx() }
    val rowGapPx = with(density) { 2.dp.toPx() }

    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    var dragFrom by remember { mutableStateOf<LocalDate?>(null) }
    var dragTo by remember { mutableStateOf<LocalDate?>(null) }

    fun dayAt(pos: Offset): LocalDate? {
        if (gridSize.width == 0) return null
        val col = (pos.x / (gridSize.width / 7f)).toInt()
        val row = (pos.y / (cellHeightPx + rowGapPx)).toInt()
        if (col !in 0..6 || row !in 0 until weeks) return null
        val dayNum = row * 7 + col - leading + 1
        return if (dayNum in 1..len) month.atDay(dayNum) else null
    }

    val dragLo = dragFrom?.let { f -> dragTo?.let { t -> minOf(f, t) } }
    val dragHi = dragFrom?.let { f -> dragTo?.let { t -> maxOf(f, t) } }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .padding(start = 11.dp, end = 11.dp, top = 11.dp, bottom = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MonthArrow("‹", onPrev)
            Text(
                month.format(MonthLabel),
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp, color = c.ink, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            if (dragLo != null && dragHi != null) {
                Text(
                    "${dragLo.format(ShortDate)} → ${dragHi.format(ShortDate)}",
                    fontFamily = PlexMono, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentSoft)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            MonthArrow("›", onNext)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { d ->
                Text(
                    d,
                    fontFamily = PlexSans, fontSize = 10.sp, color = c.ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { gridSize = it }
                .pointerInput(month, entries, selectedIndex) {
                    detectTapGestures { pos -> dayAt(pos)?.let(onTapDay) }
                }
                .then(
                    if (rangeEnabled) Modifier.pointerInput(month, entries) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pos -> dayAt(pos)?.let { dragFrom = it; dragTo = it } },
                            onDrag = { change, _ ->
                                dayAt(change.position)?.let { dragTo = it }
                                change.consume()
                            },
                            onDragEnd = {
                                val f = dragFrom
                                val t = dragTo
                                dragFrom = null
                                dragTo = null
                                if (f != null && t != null) {
                                    if (f == t) onTapDay(f)
                                    else onRange(minOf(f, t), maxOf(f, t))
                                }
                            },
                            onDragCancel = { dragFrom = null; dragTo = null },
                        )
                    } else Modifier,
                ),
        ) {
            // A fixed 6×7 grid: every cell is the same node shape (one Box → one
            // Text) behind a stable key(). The old grid emitted a variable number
            // of week Rows plus per-row trailing Spacers with no keys, so paging
            // months reshaped the layout-node tree mid-recompose and the Compose
            // applier crashed (negative arraycopy in PostInsertNodeFixup).
            repeat(GRID_ROWS) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    repeat(7) { col ->
                        val dayNum = row * 7 + col - leading + 1
                        val day = if (dayNum in 1..len) month.atDay(dayNum) else null
                        key(row * 7 + col) {
                            DayCell(
                                day = day,
                                today = today,
                                entries = entries,
                                selectedIndex = selectedIndex,
                                perEntrySelection = perEntrySelection,
                                accent = accent,
                                accentSoft = accentSoft,
                                secondaryMarks = secondaryMarks,
                                plannedDates = plannedDates,
                                dragLo = dragLo,
                                dragHi = dragHi,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Always six week rows so [AccentCalendar]'s node tree never changes shape. */
private const val GRID_ROWS = 6

private enum class Pos { NONE, SOLO, START, MID, END }

/**
 * One calendar day. Renders the same node shape whether or not [day] is set (an
 * out-of-month slot is a blank cell), so the grid stays structurally stable as
 * the month changes. The other-note / today dot is painted in [Modifier.drawBehind]
 * rather than as a conditional child for the same reason.
 */
@Composable
private fun RowScope.DayCell(
    day: LocalDate?,
    today: LocalDate,
    entries: List<OrgTimestamp>,
    selectedIndex: Int,
    perEntrySelection: Boolean,
    accent: Color,
    accentSoft: Color,
    secondaryMarks: Map<LocalDate, Color>,
    plannedDates: Set<LocalDate>,
    dragLo: LocalDate?,
    dragHi: LocalDate?,
) {
    val c = MaterialTheme.grove
    val hit = day?.let { d -> entries.indexOfFirst { it.covers(d) } } ?: -1
    val e = entries.getOrNull(hit)
    val ranged = e != null && e.rangeEnd != null && e.rangeEnd != e.date
    val posKind = when {
        day == null || e == null -> Pos.NONE
        !ranged -> Pos.SOLO
        day == e.date -> Pos.START
        day == e.rangeEnd -> Pos.END
        else -> Pos.MID
    }
    val sel = if (perEntrySelection) hit == selectedIndex else hit >= 0
    val inDrag = day != null && dragLo != null && dragHi != null &&
        !day.isBefore(dragLo) && !day.isAfter(dragHi)
    // A day this note uses on one of the *other* two tabs.
    val secondary = if (day != null && posKind == Pos.NONE && !inDrag) secondaryMarks[day] else null

    val shape = when (posKind) {
        Pos.START -> RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
        Pos.END -> RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
        Pos.MID -> RoundedCornerShape(0.dp)
        else -> RoundedCornerShape(10.dp)
    }
    val bg: Color
    val fg: Color
    val weight: FontWeight
    when {
        inDrag -> {
            bg = accentSoft; fg = accent; weight = FontWeight.SemiBold
        }
        posKind == Pos.MID -> {
            bg = accentSoft; fg = accent; weight = FontWeight.SemiBold
        }
        posKind != Pos.NONE -> {
            bg = if (sel) accent else accentSoft
            fg = if (sel) c.surface else accent
            weight = FontWeight.SemiBold
        }
        secondary != null -> {
            bg = Color.Transparent; fg = secondary; weight = FontWeight.SemiBold
        }
        else -> {
            bg = Color.Transparent; fg = c.ink; weight = FontWeight.Normal
        }
    }
    val border = when {
        inDrag || (posKind != Pos.NONE && posKind != Pos.MID) -> accent
        posKind == Pos.MID -> Color.Transparent
        secondary != null -> secondary
        day != null && day == today -> c.line2
        else -> Color.Transparent
    }
    val dot = if (day != null && posKind == Pos.NONE && !inDrag && secondary == null) when {
        day == today -> c.accent
        day in plannedDates -> c.violet
        else -> null
    } else null

    Box(
        Modifier
            .weight(1f)
            .height(38.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (inDrag) Modifier.dashedBorder(accent, if (posKind == Pos.MID) 0.dp else 10.dp)
                else Modifier.border(1.dp, border, shape),
            )
            .drawBehind {
                if (dot != null) {
                    drawCircle(
                        color = dot,
                        radius = 2.dp.toPx(),
                        center = Offset(size.width / 2f, size.height - 7.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day?.dayOfMonth?.toString() ?: "",
            fontFamily = PlexMono, fontSize = 13.sp, color = fg, fontWeight = weight,
        )
    }
}

@Composable
private fun MonthArrow(glyph: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontFamily = PlexSans, fontSize = 16.sp, color = c.ink2)
    }
}

@Composable
private fun RowScope.TabButton(
    icon: ImageVector,
    label: String,
    count: Int,
    selected: Boolean,
    accent: Color,
    accentSoft: Color,
    badgeAnchor: String? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    // Fixed height so all three tabs stay identical; each leads with a 14.dp
    // icon in the tab's accent (or ink3 when inactive) ahead of its label.
    Box(
        Modifier
            .weight(1f)
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accentSoft else c.surface2)
            .border(1.dp, if (selected) accent else c.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) accent else c.ink3,
                modifier = Modifier.size(14.dp),
            )
            Text(
                buildString {
                    append(label)
                    if (count > 1) append(" $count")
                },
                fontFamily = PlexMono, fontWeight = FontWeight.Bold,
                fontSize = 11.sp, letterSpacing = 0.6.sp,
                color = if (selected) accent else c.ink3,
                textAlign = TextAlign.Center, maxLines = 1,
            )
        }
        if (badgeAnchor != null) {
            NewDot(badgeAnchor, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** A set stamp in the list under the tabs: `Jan 15  ×`, filled when selected. */
@Composable
private fun StampChip(
    label: String,
    selected: Boolean,
    accent: Color,
    accentSoft: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accentSoft else c.surface2)
            .border(1.dp, if (selected) accent else c.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = 11.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            label,
            fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = if (selected) accent else c.ink2,
        )
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3)
        }
    }
}

/**
 * Presets, time-of-day and the org repeater for whichever stamp is currently
 * selected. Shown only once a date is set (the tab's chip list is how you get
 * one), mirroring the prototype.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StampEditor(
    value: OrgTimestamp,
    today: LocalDate,
    accent: Color,
    accentSoft: Color,
    onChange: (OrgTimestamp) -> Unit,
) {
    val c = MaterialTheme.grove
    fun patch(block: (OrgTimestamp) -> OrgTimestamp) = onChange(block(value))

    Column(Modifier.padding(horizontal = 2.dp).padding(top = 14.dp, bottom = 2.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            datePresets(today).forEach { (name, date) ->
                Chip(
                    label = "$name · ${date.format(ShortDate)}",
                    selected = value.date == date && value.rangeEnd == null,
                    accent = accent,
                    onClick = { patch { it.copy(date = date, rangeEnd = null) } },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                timeLabel(value, c.ink, c.ink2),
                fontFamily = PlexSans, fontSize = 13.5.sp,
                modifier = Modifier.weight(1f),
            )
            GroveSwitch(on = value.time != null, accent = accent) {
                if (value.time != null) patch { it.copy(time = null, endTime = null) }
                else patch { it.copy(time = LocalTime.of(9, 0)) }
            }
        }

        if (value.time != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimeField(value.time, "09:00") { t ->
                    if (t == null) patch { it.copy(time = null, endTime = null) }
                    else patch { it.copy(time = t) }
                }
                Text("to", fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3)
                TimeField(value.endTime, "-", allowClear = true) { t -> patch { it.copy(endTime = t) } }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                ) {
                    listOf("30m" to 30L, "1h" to 60L, "2h" to 120L).forEach { (name, mins) ->
                        MiniChip(name) {
                            val start = value.time ?: LocalTime.of(9, 0)
                            patch { it.copy(time = start, endTime = start.plusMinutes(mins)) }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Repeat",
                fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp, color = c.ink,
                modifier = Modifier.weight(1f),
            )
            GroveSwitch(on = value.repeater != null, accent = accent) {
                if (value.repeater != null) patch { it.copy(repeater = null) }
                else patch { it.copy(repeater = Repeater(RepeaterType.CUMULATIVE, 1, 'w')) }
            }
        }

        value.repeater?.let { rep ->
            RepeaterCard(
                repeater = rep,
                accent = accent,
                accentSoft = accentSoft,
                onChange = { next -> patch { it.copy(repeater = next) } },
            )
        }
    }
}

/** `› [ fri 10-11am ++1w ]  (Set)`: the free-text row above the calendar. */
@Composable
private fun ShorthandBox(
    value: String,
    onValueChange: (String) -> Unit,
    accent: Color,
    canApply: Boolean,
    onApply: () -> Unit,
) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg)
            .border(1.dp, if (value.isNotEmpty()) accent else c.line, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink3)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "d: aug 5 ++1m",
                    fontFamily = PlexMono, fontSize = 14.sp, color = c.ink3,
                    modifier = Modifier.padding(vertical = 11.dp),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = PlexMono, fontSize = 14.sp, color = c.ink),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onApply() }),
                modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
            )
        }
        Text(
            "Set",
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = if (canApply) c.surface else c.ink3,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(if (canApply) accent else c.surface2)
                .clickable(enabled = canApply, onClick = onApply)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/** "Every _week_, and if I am late, _keep the original rhythm_." plus the cookie. */
@Composable
private fun RepeaterCard(
    repeater: Repeater,
    accent: Color,
    accentSoft: Color,
    onChange: (Repeater) -> Unit,
) {
    val c = MaterialTheme.grove
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        val sentence = remember(repeater.value, repeater.unit, repeater.type, accent) {
            buildRepeaterSentence(intervalLabel(repeater), kindWords(repeater.type), accent)
        }
        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = sentence.text,
            style = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, color = c.ink, lineHeight = 26.sp),
            onTextLayout = { layoutResult = it },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(sentence) {
                    detectTapGestures { tapOffset ->
                        val result = layoutResult ?: return@detectTapGestures
                        when (result.getOffsetForPosition(tapOffset)) {
                            in sentence.intervalRange -> {
                                val units = listOf('d', 'w', 'm', 'y')
                                val next = units[(units.indexOf(repeater.unit).coerceAtLeast(0) + 1) % units.size]
                                onChange(repeater.copy(unit = next))
                            }
                            in sentence.kindRange -> {
                                val kinds = RepeaterType.entries
                                onChange(repeater.copy(type = kinds[(kinds.indexOf(repeater.type) + 1) % kinds.size]))
                            }
                            else -> Unit
                        }
                    }
                }
                .drawBehind {
                    val result = layoutResult ?: return@drawBehind
                    drawDashedUnderline(result, sentence.intervalRange, accent)
                    drawDashedUnderline(result, sentence.kindRange, accent)
                },
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                repeater.toString(),
                fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(accentSoft)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                kindHint(repeater),
                fontFamily = PlexSans, fontSize = 11.5.sp, color = c.ink2, lineHeight = 16.sp,
                modifier = Modifier.weight(1f).padding(top = 3.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            StepButton("−") { onChange(repeater.copy(value = (repeater.value - 1).coerceAtLeast(1))) }
            Text(
                repeater.value.toString(),
                fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = c.ink, textAlign = TextAlign.Center,
                modifier = Modifier.width(22.dp),
            )
            StepButton("+") { onChange(repeater.copy(value = (repeater.value + 1).coerceAtMost(30))) }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
            ) {
                listOf('d' to "Day", 'w' to "Week", 'm' to "Month", 'y' to "Year").forEach { (u, name) ->
                    Chip(
                        label = name,
                        selected = repeater.unit == u,
                        accent = accent,
                        onClick = { onChange(repeater.copy(unit = u)) },
                    )
                }
            }
        }
    }
}

/** The repeat sentence's text plus the char ranges of its two tappable words. */
private data class RepeaterSentence(
    val text: AnnotatedString,
    val intervalRange: IntRange,
    val kindRange: IntRange,
)

private fun buildRepeaterSentence(intervalText: String, kindText: String, accent: Color): RepeaterSentence {
    lateinit var intervalRange: IntRange
    lateinit var kindRange: IntRange
    val text = buildAnnotatedString {
        append("Every ")
        val intervalStart = length
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(intervalText) }
        intervalRange = intervalStart until length
        append(", and if I am late, ")
        val kindStart = length
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(kindText) }
        kindRange = kindStart until length
        append(".")
    }
    return RepeaterSentence(text, intervalRange, kindRange)
}

/**
 * Draws a dashed rule under [range] of [layout], anchored to that range's own
 * *font baseline* (not the line box's bottom): the 26sp line height that keeps
 * the sentence readable pads extra leading below the glyphs, which would leave
 * the rule floating well below the text if it were anchored to the box bottom.
 */
private fun DrawScope.drawDashedUnderline(layout: TextLayoutResult, range: IntRange, color: Color) {
    if (range.isEmpty()) return
    val firstLine = layout.getLineForOffset(range.first)
    val lastLine = layout.getLineForOffset(range.last)
    for (line in firstLine..lastLine) {
        val start = maxOf(range.first, layout.getLineStart(line))
        val end = minOf(range.last + 1, layout.getLineEnd(line, visibleEnd = true))
        if (start >= end) continue
        val xStart = layout.getHorizontalPosition(start, usePrimaryDirection = true)
        val xEnd = layout.getHorizontalPosition(end, usePrimaryDirection = true)
        val y = layout.getLineBaseline(line) + 2.dp.toPx()
        drawLine(
            color = color,
            start = Offset(xStart, y),
            end = Offset(xEnd, y),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()), 0f),
        )
    }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontFamily = PlexSans, fontSize = 17.sp, color = c.ink2)
    }
}

/** Selectable preset/unit chip: filled in the tab's accent when active. */
@Composable
private fun Chip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Text(
        label,
        fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 12.5.sp,
        color = if (selected) c.accentInk else c.ink2,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent else c.surface2)
            .border(1.dp, if (selected) Color.Transparent else c.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Unselectable action chip (the 30m / 1h / 2h duration shortcuts). */
@Composable
private fun MiniChip(label: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Text(
        label,
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp,
        color = c.ink2,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 40×23 pill switch matching the prototype's toggles. */
@Composable
private fun GroveSwitch(on: Boolean, accent: Color, onToggle: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .size(width = 40.dp, height = 23.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) accent else c.surface3)
            .clickable(onClick = onToggle),
    ) {
        Box(
            Modifier
                .padding(start = if (on) 19.5.dp else 2.5.dp, top = 2.5.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(c.surface),
        )
    }
}

/**
 * Tap-to-pick `HH:mm` field: opens the M3 clock dial ([SimpleTimePicker]).
 * Free-text time entry still lives in the shorthand box above the calendar.
 * [allowClear] adds a "Clear" button to the dialog (used for the optional end time).
 */
@Composable
private fun TimeField(
    value: LocalTime?,
    placeholder: String,
    allowClear: Boolean = false,
    onChange: (LocalTime?) -> Unit,
) {
    val c = MaterialTheme.grove
    var picking by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .clickable { picking = true }
            .padding(horizontal = 6.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value?.format(ClockTime) ?: placeholder,
            fontFamily = PlexMono, fontSize = 14.sp,
            color = if (value == null) c.ink3 else c.ink,
        )
    }
    if (picking) {
        SimpleTimePicker(
            initial = value ?: LocalTime.of(9, 0),
            onDismiss = { picking = false },
            onConfirm = { picking = false; onChange(it) },
            onClear = if (allowClear) {
                { picking = false; onChange(null) }
            } else {
                null
            },
        )
    }
}

/** 1dp dashed rounded outline (the calendar's in-drag day cells). */
private fun Modifier.dashedBorder(color: Color, radius: Dp) = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f),
        ),
    )
}

// ---- formatting helpers -----------------------------------------------------

private val HumanDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
private val ShortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val MonthLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")
private val ClockTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun plural(n: Int) = if (n == 1) "" else "s"

private fun datePresets(today: LocalDate): List<Pair<String, LocalDate>> {
    fun next(target: java.time.DayOfWeek): LocalDate {
        val delta = ((target.value - today.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }
        return today.plusDays(delta.toLong())
    }
    return listOf(
        "Today" to today,
        "Tomorrow" to today.plusDays(1),
        "This weekend" to next(java.time.DayOfWeek.SATURDAY),
        "Next week" to next(java.time.DayOfWeek.MONDAY),
    )
}

/** "**Time** · All day": the label is semibold, the current value is not. */
private fun timeLabel(ts: OrgTimestamp?, ink: Color, ink2: Color): AnnotatedString {
    val value = ts?.time?.let { t ->
        t.format(ClockTime) + (ts.endTime?.let { " – ${it.format(ClockTime)}" } ?: "")
    } ?: "All day"
    return buildAnnotatedString {
        withStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold)) { append("Time") }
        withStyle(SpanStyle(color = ink2)) { append(" · $value") }
    }
}

private fun unitName(unit: Char, n: Int): String {
    val base = when (unit) {
        'd' -> "day"; 'w' -> "week"; 'm' -> "month"; 'y' -> "year"; 'h' -> "hour"; else -> "week"
    }
    return base + plural(n)
}

private fun intervalLabel(rep: Repeater): String =
    if (rep.value == 1) unitName(rep.unit, 1) else "${rep.value} ${unitName(rep.unit, rep.value)}"

private fun kindWords(type: RepeaterType): String = when (type) {
    RepeaterType.CUMULATIVE -> "keep the original rhythm"
    RepeaterType.CATCH_UP -> "skip the ones I missed"
    RepeaterType.FUTURE -> "count from when I finish"
}

private fun kindHint(rep: Repeater): String {
    val interval = intervalLabel(rep).let { if (rep.value == 1) "one $it" else it }
    return when (rep.type) {
        RepeaterType.CUMULATIVE ->
            "Moves on by exactly $interval, so if you were late the new date can still be in the past."
        RepeaterType.CATCH_UP ->
            "Jumps forward in $interval steps until it lands after today. Missed rounds are dropped."
        RepeaterType.FUTURE ->
            "Next date is $interval after the day you actually mark it DONE."
    }
}

private fun relativeDay(date: LocalDate, today: LocalDate): String {
    val n = ChronoUnit.DAYS.between(today, date).toInt()
    return when {
        n == 0 -> "today"
        n == 1 -> "tomorrow"
        n == -1 -> "yesterday"
        n > 0 -> "in $n days"
        else -> "${abs(n)} days ago"
    }
}

private fun shorthandEcho(parse: ShorthandParse, today: LocalDate, activeTab: PlanningKind): String = when (parse) {
    is ShorthandParse.Unrecognised ->
        "Not understood, try “fri”, “+2w”, “aug 3 10-11am”"
    is ShorthandParse.Ok -> {
        val sh = parse.value
        // No explicit s:/d:/a: prefix falls back to the open tab (the same
        // fallback applyShorthand() uses), so the echo always names its target.
        val target = sh.target ?: activeTab
        val arrow = "→ ${target.name}"
        val segments = listOfNotNull(
            sh.date?.let { "${it.format(HumanDate)} · ${relativeDay(it, today)}" },
            sh.time?.let { t ->
                t.format(ClockTime) + (sh.endTime?.let { "–${it.format(ClockTime)}" } ?: "")
            },
            sh.repeater?.let { r -> "every ${intervalLabel(r)} $r" },
        )
        if (segments.isEmpty()) arrow else segments.joinToString("  ·  ") + "  " + arrow
    }
}
