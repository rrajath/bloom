package com.rrajath.grove.capture

import android.content.Context
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.org.NotePlacement
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsSource
import com.rrajath.grove.sync.SyncTrigger
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Decides how content shared into Grove becomes an org note (PRD §10):
 * - a URL → a heading linking the page title to the URL,
 * - long text → an empty heading with the text as the body,
 * - short text → the text itself as the heading.
 * Pure so the rule is unit-testable; the network title lookup happens elsewhere.
 */
object ShareIntake {
    /** Text at or below this length reads fine as a heading; longer text becomes a body. */
    const val TEXT_HEADING_LIMIT = 40

    data class Note(val heading: String, val body: String?)

    /**
     * Build the note for [payload]. [resolvedTitle] is the page title fetched for
     * a URL (null if the fetch failed or the payload isn't a URL).
     */
    fun composeNote(payload: SharedPayload, resolvedTitle: String?): Note = when {
        payload.url.isNotEmpty() -> {
            val description = (resolvedTitle?.takeIf { it.isNotBlank() }
                ?: payload.text.takeIf { it.isNotBlank() }
                ?: payload.url)
                // Square brackets would break the [[url][desc]] link syntax.
                .replace('[', '(').replace(']', ')')
                .trim()
            Note(heading = "[[${payload.url}][$description]]", body = null)
        }
        payload.text.length > TEXT_HEADING_LIMIT -> Note(heading = "", body = payload.text)
        else -> Note(heading = payload.text, body = null)
    }

    /**
     * Everything [consumeShare] needs from the app, so tests can pass fakes.
     * [context] is only for string resources; [notify] shows the result to the
     * user (a toast in production).
     */
    class Deps(
        val settings: SettingsSource,
        val vault: Flow<Vault?>,
        val sync: SyncTrigger,
        val context: Context,
        val newOrgId: suspend () -> String,
        val fetchTitle: suspend (url: String) -> String?,
        val notify: suspend (message: String) -> Unit,
        val now: () -> LocalDateTime = LocalDateTime::now,
    ) {
        companion object {
            fun forApp(app: GroveApplication) = Deps(
                settings = app.settingsRepository,
                vault = app.vault,
                sync = app.syncManager,
                context = app,
                newOrgId = { app.orgIdAllocator.next() },
                fetchTitle = { url -> PageTitleFetcher.fetch(url, app) },
                notify = toaster(app),
            )
        }
    }

    /** Production wiring: [consumeShare] against the live [GroveApplication]. */
    suspend fun consumeShare(app: GroveApplication, payload: SharedPayload) =
        consumeShare(Deps.forApp(app), payload)

    /**
     * Routes [payload] straight to the configured file or heading (PRD §10):
     * fetches a URL's page title, composes the note, inserts it at the top or
     * bottom of the target (Settings § Sharing), syncs, and reports the
     * result. Not tied to any particular scope: [com.rrajath.grove.ui.AppViewModel.consumeSharedContent]
     * runs this on `viewModelScope` for shares that arrive while the app is on
     * screen; [ShareReceiverActivity] runs it on [GroveApplication.appScope] so
     * the share never has to bring the app's UI to the foreground at all.
     */
    suspend fun consumeShare(deps: Deps, payload: SharedPayload) {
        val context = deps.context
        val settings = deps.settings.settings.first()
        // A WebDAV mirror is a vault on its own, so sharing works before a SAF folder is attached.
        if (settings.vaultTreeUri == null && !settings.webdavEnabled) {
            deps.notify(context.getString(R.string.share_no_vault, context.getString(R.string.app_name)))
            return
        }
        // On a cold start the vault may still be initializing; await it.
        val vault = deps.vault.filterNotNull().first()
        // Off → no network at all. composeNote then describes the link with whatever
        // text the sharing app sent (often the page title, via EXTRA_SUBJECT), else the URL.
        val resolvedTitle = if (payload.url.isNotEmpty() && settings.fetchSharedLinkTitles) {
            deps.fetchTitle(payload.url)
        } else null
        val note = composeNote(payload, resolvedTitle)
        val target = settings.shareTargetFile.trim().ifBlank { GroveSettings.DEFAULT_SHARE_TARGET }
        val fileName = if (target.endsWith(".org")) target else "$target.org"
        if (vault.open(fileName) == null) vault.createNotebook(fileName)
        val doc = vault.open(fileName)
        if (doc == null) {
            deps.notify(context.getString(R.string.location_open_failed, fileName))
            return
        }
        val headingPath = settings.shareTargetHeadingPath.split('/').filter { it.isNotEmpty() }
        val placed = place(
            doc,
            headingPath,
            settings.shareInsertPlacement,
            note,
            OrgMutations.NewNoteOptions(
                id = if (settings.addIdToNewNotes) deps.newOrgId() else null,
                createdAt = if (settings.addCreatedToNewNotes) deps.now() else null,
                body = note.body,
            ),
        )
        vault.save(fileName, placed.text)
        deps.sync.requestReindex(fileName, placed.text, "shared note")
        val fileLabel = fileName.removeSuffix(".org")
        deps.notify(
            when {
                headingPath.isEmpty() -> context.getString(R.string.share_saved_to, fileLabel)
                placed.headingFound ->
                    context.getString(R.string.share_saved_to, (listOf(fileLabel) + headingPath).joinToString(" › "))
                else -> context.getString(R.string.share_heading_not_found, headingPath.last(), fileLabel)
            },
        )
    }

    /** [text] is the new document; [headingFound] is false when the target heading was missing. */
    data class Placed(val text: String, val headingFound: Boolean)

    /**
     * Inserts [note] as the first or last child ([placement]) of the heading at
     * [headingPath] (exact titles, outermost first), or of the file when the path
     * is empty. A heading that no longer exists falls back to the file itself,
     * so a renamed or deleted heading never drops a share.
     */
    fun place(
        doc: OrgDocument,
        headingPath: List<String>,
        placement: NotePlacement,
        note: Note,
        options: OrgMutations.NewNoteOptions,
    ): Placed {
        val parent = if (headingPath.isEmpty()) null else CaptureInserter.findByPath(doc, headingPath)
        val (text, _) = OrgMutations.newNoteIn(doc, parent, placement, note.heading, options)
        return Placed(text, headingFound = headingPath.isEmpty() || parent != null)
    }

    /** A [Deps.notify] that shows a short toast on the main thread. */
    fun toaster(context: Context): suspend (String) -> Unit = { message ->
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
