package com.rrajath.grove.ui.screens.settings

import com.rrajath.grove.ui.util.UiText
import com.rrajath.grove.ui.util.asString
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.search.NoteMeta
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.agenda.AgendaMeta
import com.rrajath.grove.ui.agenda.AgendaMetaTone
import com.rrajath.grove.ui.agenda.AgendaRow
import com.rrajath.grove.ui.agenda.agendaPriorityColor
import com.rrajath.grove.ui.agenda.metaColor
import com.rrajath.grove.ui.components.BrandMark
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.uppercaseForUi
import com.rrajath.grove.widget.LedgerBuckets
import com.rrajath.grove.widget.agendaSubtitle
import java.time.LocalDate
import java.time.LocalTime

/**
 * Live stand-in for the home-screen Agenda ledger widget (`LedgerWidget`), built
 * from stubbed notes and re-run through the real [LedgerBuckets.build] so every
 * widget-only lever below (Filename, Tags, Priority, Overdue days, Font size,
 * as well as Transparency/Days ahead) reflects in this card exactly the way it
 * would on the widget. Glance composables can't render inside a normal Compose
 * screen, so this is a plain-Compose re-rendering of `LedgerContent`'s layout,
 * not the widget's own composition.
 */
@Composable
internal fun AgendaWidgetPreview(settings: GroveSettings, transparencyOverride: Float? = null) {
    val c = MaterialTheme.grove
    val today = remember { LocalDate.now() }
    val resources = LocalResources.current
    val notes = remember(today, resources) { previewNotes(today, resources) }
    val sections = remember(notes, today, settings) {
        LedgerBuckets.build(notes, today, settings.agendaWidgetDaysAhead, settings)
    }
    val scale = settings.agendaWidgetFontSize.scale
    // Overridable so dragging the Transparency slider (which no longer writes
    // to disk on every tick, see SettingsWidgetScreen) still tracks live here.
    val backgroundColor = c.surface.copy(alpha = 1f - (transparencyOverride ?: settings.agendaWidgetTransparency))
    val totalCount = sections.sumOf { it.count }
    val todayCount = sections.firstOrNull { it.day == today }?.count ?: 0

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(backgroundColor)
            .border(1.dp, c.line, RoundedCornerShape(22.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark(tileSize = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                ScaledText(stringResource(R.string.widget_view_agenda), 13.5f, scale, FontWeight.Medium, c.ink, PlexSans)
                ScaledText(agendaSubtitle(resources, todayCount, totalCount, settings.agendaWidgetDaysAhead), 10.5f, scale, FontWeight.Normal, c.ink2, PlexMono)
            }
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.accent),
                contentAlignment = Alignment.Center,
            ) {
                ScaledText("+", 17f, 1f, FontWeight.Medium, c.accentInk, PlexSans)
            }
        }
        if (sections.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                ScaledText(stringResource(R.string.agenda_empty_title), 12f, scale, FontWeight.Normal, c.ink3, PlexSans)
            }
        } else {
            Column(Modifier.padding(bottom = 10.dp)) {
                sections.forEach { section ->
                    PreviewSectionHeader(c, section, scale)
                    section.rows.forEach { row -> PreviewRow(c, row, scale) }
                }
            }
        }
    }
}

@Composable
private fun PreviewSectionHeader(c: GroveColors, section: LedgerBuckets.Section, scale: Float) {
    val isOverdue = section.isOverdue
    Row(
        Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 9.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScaledText(section.label.asString().uppercaseForUi(), 10f, scale, FontWeight.Bold, if (isOverdue) c.red else c.ink2, PlexSans)
        Spacer(Modifier.width(7.dp))
        ScaledText(section.count.toString(), 10f, scale, FontWeight.Normal, c.ink3, PlexMono)
        Spacer(Modifier.width(7.dp))
        Box(Modifier.weight(1f).height(1.dp).background(if (isOverdue) c.red else c.line))
    }
}

/** Same 18dp anchor band [LedgerWidget] uses so the ring/keyword pill line up with the title's first line. */
private val PREVIEW_LINE_HEIGHT = 18.dp

@Composable
private fun PreviewRow(c: GroveColors, row: AgendaRow, scale: Float) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.height(PREVIEW_LINE_HEIGHT).width(14.dp), contentAlignment = Alignment.Center) {
            if (row.keyword != null) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(c.line2), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c.surface))
                }
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                if (row.keyword != null) {
                    val (fg, bg) = previewStateChipColors(c, row.keyword)
                    Box(Modifier.height(PREVIEW_LINE_HEIGHT), contentAlignment = Alignment.Center) {
                        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 5.dp, vertical = 2.dp)) {
                            ScaledText(row.keyword, 9.5f, scale, FontWeight.Bold, fg, PlexMono)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                ScaledText(row.title, 13f, scale, FontWeight.Medium, c.ink, PlexSans, maxLines = 2)
            }
            if (row.meta.isNotEmpty()) {
                // Mirrors LedgerWidget's LedgerRow: when a crowded meta line also carries
                // tags, the trailing filename chip drops to its own line instead of
                // wrapping mid-word.
                val fileChip = row.meta.lastOrNull()
                    ?.takeIf { it.tone == AgendaMetaTone.MUTED && it.text == UiText.raw(LedgerBuckets.contractFileName(row.fileName)) }
                val splitFile = fileChip != null && row.meta.any { it.tone == AgendaMetaTone.TAG }
                val inlineMeta = if (splitFile) row.meta.dropLast(1) else row.meta
                PreviewMetaLine(c, inlineMeta, scale, Modifier.padding(top = 3.dp))
                if (splitFile) PreviewMetaLine(c, listOf(fileChip!!), scale, Modifier.padding(top = 2.dp))
            }
        }
        if (row.priority != null) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                ScaledText(row.priority, 9.5f, scale, FontWeight.Bold, c.agendaPriorityColor(row.priority), PlexMono)
            }
        }
    }
}

@Composable
private fun PreviewMetaLine(c: GroveColors, meta: List<AgendaMeta>, scale: Float, modifier: Modifier) {
    Row(modifier) {
        meta.forEachIndexed { index, m ->
            if (index > 0) Spacer(Modifier.width(8.dp))
            ScaledText(m.text.asString(), 10.5f, scale, FontWeight.Normal, c.metaColor(m.tone), PlexMono, maxLines = 1)
        }
    }
}

/** Foreground/background pair for the keyword pill, mirroring `LedgerWidget`'s `stateChipColors`. */
private fun previewStateChipColors(c: GroveColors, keyword: String) = when (keyword) {
    "NEXT" -> c.green to c.greenSoft
    "WAITING" -> c.ink3 to c.surface2
    else -> c.synTodo to c.amberSoft
}

/** One text run at [baseSp] scaled by the widget's Font size lever. */
@Composable
private fun ScaledText(
    text: String,
    baseSp: Float,
    scale: Float,
    weight: FontWeight,
    color: Color,
    fontFamily: FontFamily,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        fontFamily = fontFamily, fontWeight = weight,
        fontSize = (baseSp * scale).sp, color = color,
        maxLines = maxLines,
    )
}

/**
 * Four stubbed headings spanning Overdue (two, at different ages), Today, and
 * Tomorrow — enough sections to preview every widget-only lever at once (tags,
 * filename, priority, font size).
 */
// i18n-begin: sample file names, tags and keywords are org data
private fun previewNotes(today: LocalDate, res: android.content.res.Resources): List<NoteMeta> = listOf(
    previewNote(
        fileName = "Work/Personal/admin.org", title = res.getString(R.string.settings_widget_sample_passport), keyword = "TODO",
        priority = "A", tags = listOf("errand"),
        scheduled = OrgTimestamp(today.minusDays(3)).format(),
    ),
    previewNote(
        fileName = "finance.org", title = res.getString(R.string.settings_widget_sample_expenses), keyword = "TODO",
        priority = "B", tags = emptyList(),
        scheduled = OrgTimestamp(today.minusDays(10)).format(),
    ),
    previewNote(
        fileName = "work/standup.org", title = res.getString(R.string.settings_widget_sample_standup), keyword = "NEXT",
        priority = null, tags = listOf("meeting"),
        scheduled = OrgTimestamp(today, time = LocalTime.of(9, 0)).format(),
    ),
    previewNote(
        fileName = "reports/q3.org", title = res.getString(R.string.settings_widget_sample_report), keyword = "TODO",
        priority = "B", tags = listOf("work"),
        scheduled = OrgTimestamp(today.plusDays(1)).format(),
    ),
)
// i18n-end

private fun previewNote(
    fileName: String,
    title: String,
    keyword: String?,
    priority: String?,
    tags: List<String>,
    scheduled: String,
) = NoteMeta(
    fileName = fileName, lineIndex = 0, title = title, keyword = keyword, isDoneKeyword = false,
    priority = priority, tags = tags, inheritedTags = tags, scheduled = scheduled, deadline = null,
    closed = null, createdAt = null, lastModified = 0L, searchText = title,
)
