package com.rrajath.grove.ui.nav

import java.net.URLEncoder

/**
 * Navigation route table (CLAUDE.md / PRD §13). Pure Kotlin so the builders are
 * JVM-unit-testable. IDs are URL-encoded because notebook ids are file names.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val NOTEBOOKS = "notebooks"
    /**
     * [auto] is `true` when the outline was reached by opening the notebook itself
     * (Notebooks list, a `[[file:]]` link, a search file match): with the Roam
     * "open small files as one note" toggle on, a file under the line limit then
     * skips the outline and lands on [FILE] instead. Breadcrumbs and the file
     * view's own filename tap ask for the outline explicitly and leave it off.
     * [reveal] is a heading's line index to scroll to and briefly highlight
     * (Search's "show in notebook").
     */
    const val OUTLINE = "outline/{notebookId}?narrowTo={narrowTo}&auto={auto}&reveal={reveal}"
    const val NOTE = "note/{noteId}?mode={mode}&isNew={isNew}"
    /**
     * The whole `.org` file as one note: preface, intro and every heading in one
     * Read scroll, or the entire buffer in one editor. Gated on the file's line
     * count (`WHOLE_FILE_LINE_LIMIT`) so a huge file can't freeze the editor.
     */
    const val FILE = "file/{fileName}?mode={mode}"
    /** A file's preamble (everything before its first heading), opened by double-tapping
     *  Outline's PREFACE section; scoped to just that region, unlike NOTE. */
    const val PREFACE = "preface/{fileName}"
    /** A single metadata drawer, opened by double-tapping it: [kind] is `fileProps`
     *  (file-level `:PROPERTIES:`), `headingProps` or `headingLog`; [noteId] is the
     *  encoded NoteRef of the owning heading (absent for `fileProps`). */
    const val DRAWER = "drawer/{fileName}?kind={kind}&noteId={noteId}"
    /** A single `#+BEGIN_x … #+END_x` block, opened by double-tapping it in Read mode;
     *  [line] is the absolute doc line of its `#+BEGIN` (or first affiliated line). */
    const val BLOCK = "block/{fileName}?line={line}"
    const val CAPTURE = "capture"
    const val CAPTURE_TEMPLATE = "capture/{templateId}"
    const val SEARCH = "search?q={q}&notebook={notebook}"
    const val AGENDA = "agenda"
    const val CONFLICT = "conflict/{notebookId}"
    const val SETTINGS = "settings"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_NOTEBOOKS = "settings/notebooks"
    const val SETTINGS_CAPTURE_TEMPLATES = "settings/templates"
    const val SETTINGS_SYNC = "settings/sync"
    const val SETTINGS_NOTES = "settings/notes"
    const val SETTINGS_AGENDA = "settings/agenda"
    const val SETTINGS_WIDGET = "settings/widget"
    const val SETTINGS_REMINDERS = "settings/reminders"
    const val SETTINGS_SHARING = "settings/sharing"
    /** Experimental Roam-style features: backlinks bar, auto-link suggestions while typing. */
    const val SETTINGS_ROAM = "settings/roam"
    /** Experimental attachments: photo size and default image width (debug builds only). */
    const val SETTINGS_ATTACHMENTS = "settings/attachments"
    /** A daily journal note; [date] is ISO yyyy-MM-dd, absent = today. */
    const val DAILIES = "dailies?date={date}"
    const val SETTINGS_BACKUP = "settings/backup"
    const val SETTINGS_BUG_REPORT = "settings/bug"
    const val SETTINGS_TIPS = "settings/tips"
    const val SETTINGS_WHATS_NEW = "settings/whatsnew"

    /** Debug-only: reset the "NEW" feature badges and other dev actions (see [SETTINGS] gate). */
    const val SETTINGS_DEVELOPER = "settings/developer"
    const val TEMPLATE_EDIT = "template/{templateId}"
    const val SYNC_LOG = "settings/synclog"
    /**
     * Tapping a reminder notification's *body*: the heading is identified by its
     * composite key, which has to be resolved against the vault to get a line
     * index, so it lands here first and is then handed off to NOTE in read mode.
     * (The "Reschedule" action does not come through here; it opens
     * `RescheduleActivity` in its own task instead of entering the app.)
     */
    const val REMINDER = "reminder/{fileName}?headingPath={headingPath}&level={level}"

    /** Sentinel templateId that opens the editor in create mode. */
    const val NEW_TEMPLATE_ID = "new"

    /**
     * Percent-encode a route segment/arg so it survives `NavDeepLink`'s decoding.
     * `URLEncoder` alone encodes a space as `+`, but androidx.navigation decodes
     * path args via `Uri.decode` and query args via `Uri.getQueryParameters`;
     * neither undoes `+`→space, so `"Buy milk"` would round-trip as `"Buy+milk"`
     * and break exact-string matching (e.g. reminder reschedule deep links,
     * multi-word note ids, search queries). Emitting `%20` for spaces round-trips
     * correctly through `Uri.decode` while keeping this object pure-JVM testable
     * (no `android.net.Uri` dependency). Every other char `URLEncoder` escapes as
     * `%XX` already round-trips through `Uri.decode` unchanged.
     */
    fun encode(id: String): String = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    /**
     * [narrowTo] is a heading's line index, set when navigating here from a
     * Read Mode breadcrumb, so the Outline shows only that heading's subtree
     * (org-narrow-to-subtree semantics) until the user taps "widen".
     */
    fun outline(notebookId: String, narrowTo: Int? = null, autoOpen: Boolean = false, reveal: Int? = null): String {
        val params = listOfNotNull(
            narrowTo?.let { "narrowTo=$it" },
            "auto=true".takeIf { autoOpen },
            reveal?.let { "reveal=$it" },
        )
        return "outline/${encode(notebookId)}" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    fun note(noteId: String, mode: String = "read", isNew: Boolean = false) =
        "note/${encode(noteId)}?mode=$mode&isNew=$isNew"
    fun file(fileName: String, mode: String = "read") = "file/${encode(fileName)}?mode=$mode"
    fun preface(fileName: String) = "preface/${encode(fileName)}"
    fun drawer(fileName: String, kind: String, noteId: String? = null) =
        "drawer/${encode(fileName)}?kind=$kind" + (noteId?.let { "&noteId=${encode(it)}" } ?: "")
    fun block(fileName: String, line: Int) = "block/${encode(fileName)}?line=$line"
    fun capture(templateId: String? = null) =
        if (templateId == null) CAPTURE else "capture/${encode(templateId)}"
    fun conflict(notebookId: String) = "conflict/${encode(notebookId)}"
    fun templateEdit(templateId: String) = "template/${encode(templateId)}"
    /**
     * [notebook] pins the search's notebook facet to one file: used by the
     * Outline's search action, which searches inside the notebook you're
     * already looking at. The user can still widen it back to all notebooks
     * from the Filters sheet.
     */
    fun search(query: String? = null, notebook: String? = null) =
        "search?q=" + (if (query.isNullOrBlank()) "" else encode(query)) +
                (if (notebook.isNullOrBlank()) "" else "&notebook=${encode(notebook)}")
    fun reminder(fileName: String, headingPath: String, level: Int) =
        "reminder/${encode(fileName)}?headingPath=${encode(headingPath)}&level=$level"
    fun dailies(date: String? = null) = "dailies" + (date?.let { "?date=${encode(it)}" } ?: "")
}
