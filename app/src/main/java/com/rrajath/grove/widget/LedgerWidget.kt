package com.rrajath.grove.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.rrajath.grove.data.PlannedNoteRow
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.MainActivity
import com.rrajath.grove.R
import com.rrajath.grove.data.toNoteMeta
import com.rrajath.grove.icon.AppIconManager
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.agenda.AgendaMeta
import com.rrajath.grove.ui.agenda.AgendaMetaTone
import com.rrajath.grove.ui.agenda.AgendaRow
import com.rrajath.grove.ui.agenda.agendaPriorityColor
import com.rrajath.grove.ui.agenda.hasDoneAffordance
import com.rrajath.grove.ui.agenda.metaColor
import com.rrajath.grove.ui.agenda.repeaterKind
import com.rrajath.grove.ui.components.orgInlinePlainText
import com.rrajath.grove.ui.nav.DeepLinks
import com.rrajath.grove.ui.nav.Routes
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.groveColorsFor
import com.rrajath.grove.ui.theme.systemDarkThemeSupported
import com.rrajath.grove.ui.vault.NoteRef
import com.rrajath.grove.ui.vault.headlineAtLine
import com.rrajath.grove.vault.AutoArchive
import com.rrajath.grove.vault.StateChangeResult
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Home-screen "Agenda · ledger" widget: `design/Grove.dc.html`'s Widget A variant,
 * re-grouped by day (per-widget spec) instead of priority.
 *
 * The Room/settings reads happen *inside* [provideContent]'s composition, via
 * `collectAsState`, rather than being snapshotted into locals beforehand. That
 * distinction is the whole reason mark-done used to look like a no-op: while a
 * Glance session is alive, `updateAll()` only recomposes the existing content —
 * it does not re-invoke [provideGlance]. A composition built from values captured
 * before `provideContent` therefore re-rendered the exact same stale rows forever,
 * no matter how correctly the tap had rewritten the file and reindexed Room; only
 * a brand-new session (app relaunch, host rebind) ever picked the edit up.
 *
 * Collecting the flows in-composition means any vault write that lands in Room
 * pushes a fresh emission straight into the live session, so the widget redraws on
 * its own. `updateAll()` calls are kept as a belt-and-braces nudge for the case
 * where no session is running at all (then, and only then, does it start one and
 * re-run this function).
 *
 * The initial values are read with `first()` so the very first frame after a cold
 * session start is already correct instead of flashing an empty ledger.
 */
private const val TAG = "GroveWidget"

/*
 * Row caps (see [WidgetPaging]): each list shows [WidgetPaging.PAGE] rows, then
 * "+N more" pages it, up to [WidgetPaging.MAX_TOTAL_ROWS] across the whole
 * widget. Glance's `LazyColumn` ships every item inline in the RemoteViews
 * update over a single binder call (there's no out-of-process adapter like
 * classic AppWidget ListViews), which Android caps around 1MB; an unbounded
 * section (e.g. a corrupted index misclassifying hundreds of notes as overdue,
 * see `GroveApplication.keywords`'s cold-start race) can blow past that and the
 * whole widget falls back to the host's generic "Can't show content"
 * placeholder with nothing logged, since the failure happens in Glance's send,
 * not in this composition.
 */

private val AGENDA_URI = DeepLinks.agenda().toUri()

/**
 * Projection of [GroveSettings] onto just the fields this widget's composition
 * reads, so an unrelated DataStore write (an in-app editor preference, a
 * different theme lever than this widget shows) doesn't re-bucket and re-push
 * RemoteViews on every tick (PERFORMANCE_AUDIT 2026-09-16 F2, mirrors
 * `AgendaPrefs` in `AgendaViewModel`).
 */
private data class WidgetPrefs(
    val themeSelection: com.rrajath.grove.settings.ThemeSelection,
    val syncAppIconWithTheme: Boolean,
    val agendaWidgetDaysAhead: Int,
    val agendaWidgetTransparency: Float,
    val agendaWidgetFontSize: com.rrajath.grove.settings.FontSizePreference,
    val agendaWidgetShowTags: Boolean,
    val agendaWidgetShowFileName: Boolean,
    val agendaWidgetShowPriority: Boolean,
) {
    constructor(s: GroveSettings) : this(
        s.themeSelection,
        s.syncAppIconWithTheme,
        s.agendaWidgetDaysAhead,
        s.agendaWidgetTransparency,
        s.agendaWidgetFontSize,
        s.agendaWidgetShowTags,
        s.agendaWidgetShowFileName,
        s.agendaWidgetShowPriority,
    )
}

/**
 * Per-widget view selection (see [WidgetViews]): a Quick Start's reserved id or
 * a saved search's id, absent for the agenda. Lives in this widget instance's
 * own Glance state, keyed by its [GlanceId], so two Agenda widgets can show
 * different views; it's not a setting and isn't part of settings import/export.
 * Written by [WidgetViewPickerActivity].
 */
internal val WIDGET_VIEW_KEY = stringPreferencesKey("view_saved_search_id")

/**
 * "+N more" expansion (see [WidgetPaging]), per widget like [WIDGET_VIEW_KEY]:
 * the view the expansion was made in ("" for the agenda) and the encoded
 * per-list page counts. Expansion for any other view reads as nothing expanded,
 * and the picker clears both keys on a view change. Written by [LoadMoreAction].
 */
internal val EXPANSION_VIEW_KEY = stringPreferencesKey("expansion_view")
internal val EXPANSION_PAGES_KEY = stringPreferencesKey("expansion_pages")

/** [EXPANSION_VIEW_KEY]'s value for a view: its search id, or "" for the agenda. */
private fun viewKeyOf(view: WidgetViews.SearchView?) = view?.id ?: ""

class LedgerWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GroveApplication
        val initialSettings = app.settingsRepository.settings.first()
        val initialSaved = app.searchRepository.savedSearches.first()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val names = quickStartNames(context)
        // Loaded before the session so its first frame isn't an empty list:
        // the agenda's rows, or a widget left on a search view's results. Only
        // the view on screen is loaded; switching views later starts that
        // view's own collection.
        val initialSelection = WidgetViews.resolve(
            getAppWidgetState<Preferences>(context, id)[WIDGET_VIEW_KEY],
            initialSaved,
            app.keywords.value.active,
            names,
        )
        val initialNotes = if (initialSelection == null) app.database.indexDao().plannedNotes().first() else emptyList()
        val initialResults = initialSelection?.let { WidgetSearch.runSafely(app, it.query, initialSettings.defaultPriority) }

        provideContent {
            val settings by app.settingsRepository.settings
                .distinctUntilChangedBy { WidgetPrefs(it) }
                .collectAsState(initial = initialSettings)
            val saved by app.searchRepository.savedSearches.collectAsState(initial = initialSaved)
            val keywords by app.keywords.collectAsState()
            val systemIsDark by app.systemNightMode.collectAsState()
            // Re-resolved on every recomposition, so a deleted search falls
            // back to the agenda, a renamed one shows its new name, and a Quick
            // Start follows its override and the vault's keywords.
            val selected = WidgetViews.resolve(currentState(WIDGET_VIEW_KEY), saved, keywords.active, names)
            val pages = WidgetPaging.pagesFor(
                viewKeyOf(selected),
                currentState(EXPANSION_VIEW_KEY),
                currentState(EXPANSION_PAGES_KEY),
            )

            // Resolved here, not handed to the launcher as Glance day/night
            // ColorProviders. On API 31+ those become RemoteViews night colors the
            // launcher picks when it applies the views, and on device it kept
            // picking night after the phone switched to light (while the icon,
            // resolved here, updated). systemIsDark recomposes a live session;
            // GroveApplication.onConfigurationChanged starts one otherwise, and the
            // 30-minute widget update covers a switch while the process was dead.
            val theme = settings.themeSelection.resolve(systemIsDark, systemDarkThemeSupported)
            val colors = groveColorsFor(theme)
            val today = LocalDate.now()
            // Themed mipmaps only exist as <adaptive-icon> (API 26+); below that
            // there is only the default fallback icon.
            val iconRes = AppIconManager.mipmapRes(
                settings.syncAppIconWithTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
                theme,
            )
            val backgroundColor = colors.surface.copy(alpha = 1f - settings.agendaWidgetTransparency)
            val fontScale = settings.agendaWidgetFontSize.scale
            val chrome = WidgetChrome(context, colors, backgroundColor, iconRes, fontScale, appWidgetId)

            if (selected == null) {
                AgendaView(app, chrome, settings, initialNotes, today, pages)
            } else {
                // Keyed on the query (not the view's id or name) so a rename
                // keeps the running collection, while an edited query restarts it.
                val results by remember(selected.query) { WidgetSearch.results(app, selected.query) }
                    .collectAsState(initial = initialResults.takeIf { selected.query == initialSelection?.query })
                val rows = remember(results, WidgetPrefs(settings), today) {
                    results?.let { headings ->
                        try {
                            WidgetViews.searchRows(headings, today, settings)
                        } catch (e: Exception) {
                            Log.e(TAG, "failed to build search rows; showing no results", e)
                            emptyList()
                        }
                    }
                }
                SearchResultsView(chrome, selected, rows, pages)
            }
        }
    }
}

class LedgerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LedgerWidget()
}

/** Quick Start names from resources (see [WidgetViews.QuickStartName]). */
internal fun quickStartNames(context: Context) = WidgetViews.QuickStartName { context.getString(it.labelRes) }

/** What every view's frame and header need, resolved once per render. */
private class WidgetChrome(
    val context: Context,
    val colors: GroveColors,
    val backgroundColor: Color,
    val iconRes: Int,
    val fontScale: Float,
    val appWidgetId: Int,
)

@Composable
private fun AgendaView(
    app: GroveApplication,
    chrome: WidgetChrome,
    settings: GroveSettings,
    initialNotes: List<PlannedNoteRow>,
    today: LocalDate,
    pages: Map<String, Int>,
) {
    val notes by app.database.indexDao().plannedNotes().collectAsState(initial = initialNotes)
    val windowDays = settings.agendaWidgetDaysAhead
    // Bucketing walks every planned note's SCHEDULED/DEADLINE timestamp
    // (OrgTimestamp.parse). A single unparseable one used to take the
    // whole composition down with it: Glance has no per-widget crash UI
    // of its own, so the AppWidgetHost fell back to its generic "Can't
    // show content" placeholder, and it stayed that way on every
    // subsequent redraw (delete/re-add doesn't touch the vault data that
    // triggered it). Degrade to an empty ledger instead of taking the
    // whole widget down with it; the in-app Agenda screen already
    // guides the user to the underlying data problem.
    // Memoized on the actual inputs: provideGlance's distinctUntilChangedBy already
    // keeps an unrelated settings write from reaching this composition,
    // but this also skips the O(notes) re-bucket on a recomposition that
    // touches neither notes nor these fields.
    val sections = remember(notes, WidgetPrefs(settings), today) {
        try {
            val openNotes = notes.map { it.toNoteMeta() }.filter { !it.isDoneKeyword }
            LedgerBuckets.build(openNotes, today, windowDays, settings)
        } catch (e: Exception) {
            Log.e(TAG, "failed to build ledger sections; showing empty widget", e)
            emptyList()
        }
    }
    val todayCount = sections.firstOrNull { it.key.startsWith("Today") }?.count ?: 0
    val totalCount = sections.sumOf { it.count }
    val plans = WidgetPaging.plan(sections.map { it.key to it.rows.size }, pages)
    val context = chrome.context
    val colors = chrome.colors
    val fontScale = chrome.fontScale

    WidgetFrame(
        chrome,
        title = context.getString(R.string.widget_view_agenda),
        subtitle = "$todayCount today · $totalCount in $windowDays days",
    ) {
        if (sections.isEmpty()) {
            EmptyBody(colors, "Nothing scheduled", fontScale)
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize().padding(bottom = 10.dp)) {
                sections.zip(plans).forEach { (section, plan) ->
                    item(itemId = section.key.hashCode().toLong()) {
                        SectionHeader(colors, section, fontScale)
                    }
                    items(section.rows.take(plan.visible), itemId = { rowItemId(it.fileName, it.lineIndex) }) { row ->
                        LedgerRow(context, colors, row, fontScale)
                    }
                    if (plan.remaining > 0) {
                        item(itemId = ("more-" + section.key).hashCode().toLong()) {
                            MoreRow(context, colors, plan, viewKey = "", agendaIntent(context), fontScale)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One search view's results (a Quick Start or a saved search): a single flat
 * list (no day sections) in the Search screen's own order, paged by
 * [WidgetPaging] like each agenda section. [rows] null means the first results
 * haven't arrived yet, which renders as an empty body rather than a premature
 * "No results".
 */
@Composable
private fun SearchResultsView(
    chrome: WidgetChrome,
    view: WidgetViews.SearchView,
    rows: List<AgendaRow>?,
    pages: Map<String, Int>,
) {
    val context = chrome.context
    val colors = chrome.colors
    val fontScale = chrome.fontScale
    WidgetFrame(
        chrome,
        title = view.name,
        // No-break space while loading, so the header keeps its height.
        subtitle = rows?.let { context.resources.getQuantityString(R.plurals.widget_result_count, it.size, it.size) } ?: "\u00A0",
    ) {
        when {
            rows == null -> Box(modifier = GlanceModifier.fillMaxSize()) {}
            rows.isEmpty() -> EmptyBody(colors, context.getString(R.string.widget_no_results), fontScale)
            else -> {
                val plan = WidgetPaging.plan(listOf(WidgetPaging.SEARCH_LIST_KEY to rows.size), pages).single()
                LazyColumn(modifier = GlanceModifier.fillMaxSize().padding(top = 4.dp, bottom = 10.dp)) {
                    items(rows.take(plan.visible), itemId = { rowItemId(it.fileName, it.lineIndex) }) { row ->
                        LedgerRow(context, colors, row, fontScale)
                    }
                    if (plan.remaining > 0) {
                        item(itemId = "more-search".hashCode().toLong()) {
                            MoreRow(context, colors, plan, viewKey = view.id, searchIntent(context, view.query), fontScale)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetFrame(chrome: WidgetChrome, title: String, subtitle: String, body: @Composable () -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(22.dp)
            .background(ColorProvider(chrome.backgroundColor)),
    ) {
        HeaderRow(chrome, title, subtitle)
        body()
    }
}

@Composable
private fun EmptyBody(colors: GroveColors, text: String, fontScale: Float) {
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(color = ColorProvider(colors.ink3), fontSize = sp(12f, fontScale)),
        )
    }
}

/** Deep link to the in-app Agenda screen. */
private fun agendaIntent(context: Context) =
    Intent(Intent.ACTION_VIEW, AGENDA_URI).setClass(context, MainActivity::class.java)

/** Deep link to the in-app Search screen with [query] already run. */
private fun searchIntent(context: Context, query: String) =
    Intent(Intent.ACTION_VIEW, DeepLinks.search(query).toUri()).setClass(context, MainActivity::class.java)

/** Scales a base widget text size (sp) by the user's Font size lever (Settings § Agenda › Widget). */
private fun sp(base: Float, scale: Float) = (base * scale).sp

@Composable
private fun HeaderRow(chrome: WidgetChrome, title: String, subtitle: String) {
    val context = chrome.context
    val colors = chrome.colors
    val fontScale = chrome.fontScale
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The app icon deep-links to the Agenda screen whichever view this
        // widget shows; the title block opens the view picker.
        Row(
            modifier = GlanceModifier.defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(chrome.iconRes),
                contentDescription = context.getString(R.string.widget_open_agenda, context.getString(R.string.app_name)),
                modifier = GlanceModifier
                    .size(22.dp)
                    .cornerRadius(7.dp)
                    .clickable(actionStartActivity(agendaIntent(context))),
            )
            // Start padding instead of a Spacer so the 10dp gap is part of the
            // title's tap target rather than a dead zone.
            val changeView = context.getString(R.string.widget_view_change)
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .padding(start = 10.dp)
                    .semantics { contentDescription = "$title, $changeView" }
                    .clickable(actionStartActivity(WidgetViewPickerActivity.intent(context, chrome.appWidgetId))),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        maxLines = 1,
                        style = TextStyle(color = ColorProvider(colors.ink), fontSize = sp(13.5f, fontScale), fontWeight = FontWeight.Medium),
                    )
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Text(
                        context.getString(R.string.widget_view_caret),
                        style = TextStyle(color = ColorProvider(colors.ink3), fontSize = sp(11f, fontScale)),
                    )
                }
                Text(
                    subtitle,
                    style = TextStyle(
                        color = ColorProvider(colors.ink2),
                        fontSize = sp(10.5f, fontScale),
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
        Box(
            modifier = GlanceModifier
                .size(30.dp)
                .cornerRadius(10.dp)
                .background(ColorProvider(colors.accent))
                .clickable(actionStartActivity(WidgetQuickAddActivity.intent(context))),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = TextStyle(color = ColorProvider(colors.accentInk), fontSize = 17.sp, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun SectionHeader(colors: GroveColors, section: LedgerBuckets.Section, fontScale: Float) {
    val isOverdue = section.key == "Overdue"
    val labelColor = if (isOverdue) colors.red else colors.ink2
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 9.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            section.key.uppercase(),
            style = TextStyle(color = ColorProvider(labelColor), fontSize = sp(10f, fontScale), fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = GlanceModifier.width(7.dp))
        Text(
            section.count.toString(),
            style = TextStyle(color = ColorProvider(colors.ink3), fontSize = sp(10f, fontScale), fontFamily = FontFamily.Monospace),
        )
        Spacer(modifier = GlanceModifier.width(7.dp))
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .height(1.dp)
                .background(ColorProvider(if (isOverdue) colors.red else colors.line)),
        ) {}
    }
}

/**
 * Terminates a truncated list (see [WidgetPaging.Plan]) with two tap targets:
 * "+N more" grows that list by one page in place ([LoadMoreAction]; N counts
 * the rows still left out), and "view all" opens [viewAll], the in-app Agenda
 * or Search screen, which shows the same rows with no cap. Once the
 * widget-wide row budget is spent, only "view all" remains.
 */
@Composable
private fun MoreRow(
    context: Context,
    colors: GroveColors,
    plan: WidgetPaging.Plan,
    viewKey: String,
    viewAll: Intent,
    fontScale: Float,
) {
    val linkStyle = TextStyle(color = ColorProvider(colors.accent), fontSize = sp(12f, fontScale), fontWeight = FontWeight.Medium)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (plan.canLoadMore) {
            Box(
                modifier = GlanceModifier
                    .padding(horizontal = 8.dp, vertical = 9.dp)
                    .clickable(
                        actionRunCallback<LoadMoreAction>(
                            actionParametersOf(VIEW_KEY_PARAM to viewKey, LIST_KEY_PARAM to plan.key),
                        ),
                    ),
            ) {
                Text(context.getString(R.string.widget_load_more, plan.remaining), style = linkStyle)
            }
            Text(
                context.getString(R.string.widget_more_separator),
                style = TextStyle(color = ColorProvider(colors.ink3), fontSize = sp(12f, fontScale)),
            )
        }
        Box(
            modifier = GlanceModifier
                .padding(horizontal = 8.dp, vertical = 9.dp)
                .clickable(actionStartActivity(viewAll)),
        ) {
            Text(context.getString(R.string.widget_view_all), style = linkStyle)
        }
    }
}

/** [LoadMoreAction]'s view key ([EXPANSION_VIEW_KEY]'s format) and list key. */
private val VIEW_KEY_PARAM = ActionParameters.Key<String>("viewKey")
private val LIST_KEY_PARAM = ActionParameters.Key<String>("listKey")

/**
 * The "+N more" tap: one more page of [LIST_KEY_PARAM]'s list, stored in this
 * widget's Glance state, then a redraw of just this widget. The list's rows keep
 * their item ids, so the new page is appended to the same `LazyColumn` rather
 * than a rebuilt one. Kept by proguard-rules.pro's ActionCallback rule (R8
 * would otherwise strip the no-arg constructor Glance calls reflectively).
 */
class LoadMoreAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val viewKey = parameters[VIEW_KEY_PARAM] ?: return run { Log.w(TAG, "no viewKey parameter") }
        val listKey = parameters[LIST_KEY_PARAM] ?: return run { Log.w(TAG, "no listKey parameter") }
        updateAppWidgetState(context, glanceId) { prefs ->
            val pages = WidgetPaging.pagesFor(viewKey, prefs[EXPANSION_VIEW_KEY], prefs[EXPANSION_PAGES_KEY])
            prefs[EXPANSION_VIEW_KEY] = viewKey
            prefs[EXPANSION_PAGES_KEY] = WidgetPaging.encode(WidgetPaging.expand(pages, listKey))
        }
        LedgerWidget().update(context, glanceId)
    }
}

/**
 * Approximate rendered line-height of the row's 13sp title text. Glance can't
 * report real text-layout metrics, so the circle and the keyword pill are each
 * centered inside a box of this fixed height, anchored to the top of the row —
 * i.e. the same band the title's first line occupies — rather than centered
 * against the title's full (possibly 2-line) height or the row's own top edge.
 */
private val LEDGER_LINE_HEIGHT = 18.dp

@Composable
private fun LedgerRow(context: Context, colors: GroveColors, row: AgendaRow, fontScale: Float) {
    val openIntent = Intent(Intent.ACTION_VIEW, noteUri(row))
        .setClass(context, MainActivity::class.java)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The clickable region is the 14dp ring itself (see below), not this
        // outer Box. Widening the tap target to 23dp (folding in the following
        // spacer) used to compensate for a stale-render bug that made mark-done
        // look unresponsive, but the real fix was the collectAsState move above.
        Box(
            modifier = GlanceModifier.height(LEDGER_LINE_HEIGHT).width(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            // A saved search can list headings that are already done; they get
            // no circle (there's nothing left to mark), just the keyword pill.
            if (row.hasDoneAffordance && !row.isDone) {
                // No border() modifier in Glance 1.1.1: the "hollow ring" is faked
                // by centering a smaller surface-colored circle over a light-grey one.
                // The clickable + cornerRadius(7dp) live on this 14dp square so the
                // system tap ripple clips to the circle instead of spilling into the
                // taller line-height band the outer Box occupies for alignment.
                Box(
                    modifier = GlanceModifier
                        .size(14.dp)
                        .cornerRadius(7.dp)
                        .background(ColorProvider(colors.line2))
                        .clickable(
                            actionRunCallback<MarkDoneAction>(
                                actionParametersOf(
                                    FILE_NAME_KEY to row.fileName,
                                    LINE_INDEX_KEY to row.lineIndex,
                                    // Only meaningful for a keyword-less row; empty string
                                    // sentinels "not set" (ActionParameters has no nullable put).
                                    PLANNING_KIND_KEY to (row.repeaterKind?.name ?: ""),
                                    ACTIVE_DATE_KEY to (row.activeTs?.date?.toString() ?: ""),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = GlanceModifier.size(12.dp).cornerRadius(6.dp).background(ColorProvider(colors.surface))) {}
                }
            }
        }
        Spacer(modifier = GlanceModifier.width(9.dp))
        Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(openIntent))) {
            Row(verticalAlignment = Alignment.Top) {
                if (row.keyword != null) {
                    val (fg, bg) = stateChipColors(colors, row.keyword)
                    Box(
                        modifier = GlanceModifier.height(LEDGER_LINE_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = GlanceModifier.cornerRadius(4.dp).background(ColorProvider(bg))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text(
                                row.keyword,
                                style = TextStyle(
                                    color = ColorProvider(fg),
                                    fontSize = sp(9.5f, fontScale),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                    Spacer(modifier = GlanceModifier.width(6.dp))
                }
                Text(
                    orgInlinePlainText(row.title),
                    maxLines = 2,
                    style = TextStyle(color = ColorProvider(colors.ink), fontSize = sp(13f, fontScale), fontWeight = FontWeight.Medium),
                )
            }
            if (row.meta.isNotEmpty()) {
                // Glance has no FlowRow and can't measure width, so a crowded meta
                // line (date + inherited tags + filename) used to wrap the trailing
                // filename mid-word across three lines. When the row carries tags —
                // the case that actually runs out of room — drop the filename onto
                // its own line instead; everything else stays inline.
                val fileChip = row.meta.lastOrNull()
                    ?.takeIf { it.tone == AgendaMetaTone.MUTED && it.text == LedgerBuckets.contractFileName(row.fileName) }
                val splitFile = fileChip != null && row.meta.any { it.tone == AgendaMetaTone.TAG }
                val inlineMeta = if (splitFile) row.meta.dropLast(1) else row.meta
                MetaLine(colors, inlineMeta, GlanceModifier.padding(top = 3.dp), fontScale)
                if (splitFile) {
                    MetaLine(colors, listOf(fileChip!!), GlanceModifier.padding(top = 2.dp), fontScale)
                }
            }
        }
        if (row.priority != null) {
            Spacer(modifier = GlanceModifier.width(6.dp))
            Box(
                modifier = GlanceModifier.size(16.dp).cornerRadius(5.dp).background(ColorProvider(colors.surface2)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    row.priority,
                    style = TextStyle(
                        color = ColorProvider(colors.agendaPriorityColor(row.priority)),
                        fontSize = sp(9.5f, fontScale),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

/** One horizontal strip of meta chips, 8dp apart. */
@Composable
private fun MetaLine(colors: GroveColors, meta: List<AgendaMeta>, modifier: GlanceModifier, fontScale: Float) {
    Row(modifier = modifier) {
        meta.forEachIndexed { index, m ->
            if (index > 0) Spacer(modifier = GlanceModifier.width(8.dp))
            MetaChip(colors, m, fontScale)
        }
    }
}

@Composable
private fun MetaChip(colors: GroveColors, meta: AgendaMeta, fontScale: Float) {
    Text(
        meta.text,
        // Never let a chip wrap mid-word; when the strip is tight it truncates instead.
        maxLines = 1,
        style = TextStyle(color = ColorProvider(colors.metaColor(meta.tone)), fontSize = sp(10.5f, fontScale), fontFamily = FontFamily.Monospace),
    )
}

/** Foreground/background pair for the keyword pill, mirroring `AgendaScreen`'s `StateChip`. */
private fun stateChipColors(colors: GroveColors, keyword: String) = when (keyword) {
    "NEXT" -> colors.green to colors.greenSoft
    "WAITING" -> colors.ink3 to colors.surface2
    else -> colors.synTodo to colors.amberSoft
}

private fun noteUri(row: AgendaRow) = run {
    val noteId = Routes.encode(NoteRef(row.fileName, row.lineIndex).encode())
    DeepLinks.note(noteId, mode = "read", isNew = false).toUri()
}

private val FILE_NAME_KEY = ActionParameters.Key<String>("fileName")
private val LINE_INDEX_KEY = ActionParameters.Key<Int>("lineIndex")
/** [PlanningKind] name, set only for a keyword-less row with a SCHEDULED/DEADLINE repeater. */
private val PLANNING_KIND_KEY = ActionParameters.Key<String>("planningKind")
/** ISO date, set only for a keyword-less row placed by a repeating bare active timestamp. */
private val ACTIVE_DATE_KEY = ActionParameters.Key<String>("activeDate")

/**
 * The widget's circle tap. For a keyword-less row ([AgendaRow.repeaterKind] set) this
 * is [com.rrajath.grove.ui.agenda.AgendaViewModel.advanceRepeater]'s clean path: just
 * advance [PLANNING_KIND_KEY]/[ACTIVE_DATE_KEY]'s timestamp, no keyword/LOGBOOK/archive
 * involved. Otherwise it's [com.rrajath.grove.ui.agenda.AgendaViewModel.toggleDone]'s
 * semantics (a repeating TODO advances its repeater instead of gaining a done keyword,
 * so it's never auto-archived; a non-recurring done item is auto-archived when Settings
 * has it enabled) but with no undo — the row simply drops out of the widget's next render.
 */
class MarkDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val fileName = parameters[FILE_NAME_KEY] ?: return run { Log.w(TAG, "no fileName parameter") }
        val lineIndex = parameters[LINE_INDEX_KEY] ?: return run { Log.w(TAG, "no lineIndex parameter") }
        val app = context.applicationContext as GroveApplication
        // app.vault is a StateFlow seeded null until the settings DataStore read
        // completes; a widget tap can easily revive a process Android had killed
        // in the background (same race reindexNow's doc comment calls out), so
        // reading .value synchronously here would silently no-op on a cold start.
        val vault = withTimeoutOrNull(5_000) { app.vault.filterNotNull().first() }
            ?: return run { Log.w(TAG, "vault still null after 5s; giving up on $fileName:$lineIndex") }
        val doc = vault.open(fileName)
            ?: return run { Log.w(TAG, "vault.open returned null for $fileName") }
        val headline = doc.headlineAtLine(lineIndex)
            ?: return run {
                Log.w(
                    TAG,
                    "no headline at $fileName:$lineIndex (stale index?); " +
                        "headline lines are ${doc.headlines.map { it.lineIndex }}",
                )
            }
        val keyword = headline.keyword
        if (keyword == null) {
            val kindParam = parameters[PLANNING_KIND_KEY]?.takeIf { it.isNotEmpty() }
            val activeDateParam = parameters[ACTIVE_DATE_KEY]?.takeIf { it.isNotEmpty() }
            val newText = when {
                activeDateParam != null ->
                    runCatching { LocalDate.parse(activeDateParam) }.getOrNull()
                        ?.let { OrgMutations.advanceActiveTimestamp(doc, headline, it, LocalDateTime.now()) }
                kindParam != null ->
                    runCatching { PlanningKind.valueOf(kindParam) }.getOrNull()
                        ?.let { OrgMutations.advanceRepeatingPlanning(doc, headline, it, LocalDateTime.now()) }
                else -> null
            } ?: return run { Log.w(TAG, "headline at $fileName:$lineIndex has no keyword and nothing to advance") }
            vault.save(fileName, newText)
            app.reindexNow(fileName, newText)
            app.syncManager.requestReindex(fileName, newText, "ledger widget advance repeater")
            LedgerWidget().updateAll(context)
            return
        }
        if (doc.keywords.isDone(keyword)) {
            return run { Log.i(TAG, "headline at $fileName:$lineIndex is already done ($keyword)") }
        }
        val doneKeyword = doc.keywords.done.firstOrNull()
            ?: return run { Log.w(TAG, "no done keyword configured; keywords=${doc.keywords}") }
        val settings = app.settingsRepository.settings.first()
        when (
            val result = AutoArchive.apply(vault, settings, doc, fileName, headline, doneKeyword, LocalDateTime.now())
        ) {
            is StateChangeResult.Plain -> {
                vault.save(fileName, result.text)
                app.reindexNow(fileName, result.text)
                app.syncManager.requestReindex(fileName, result.text, "ledger widget mark done")
            }
            is StateChangeResult.Archived -> {
                vault.save(fileName, result.sourceText)
                app.reindexNow(fileName, result.sourceText)
                app.syncManager.requestReindex(fileName, result.sourceText, "ledger widget mark done")
                if (result.destFile != fileName) {
                    vault.save(result.destFile, result.destText)
                    app.reindexNow(result.destFile, result.destText)
                    app.syncManager.requestReindex(result.destFile, result.destText, "ledger widget mark done")
                }
            }
        }
        LedgerWidget().updateAll(context)
    }
}

/**
 * A row's Glance item id: a 64-bit FNV-1a hash of `file@line`, so two rows
 * practically never share an id (a shared id makes the launcher reuse the
 * wrong row's views). The sign bit is cleared: Glance reserves ids at or below
 * `ReservedItemIdRangeEnd`, a quarter of the negative range.
 */
internal fun rowItemId(fileName: String, lineIndex: Int): Long {
    var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (c in "$fileName@$lineIndex") {
        h = (h xor c.code.toLong()) * 0x100000001b3L
    }
    return h and Long.MAX_VALUE
}
