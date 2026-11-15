package com.rrajath.grove.capture

import com.rrajath.grove.org.OrgMutations
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.newOrgId
import com.rrajath.grove.sync.SyncTrigger
import com.rrajath.grove.ui.editor.AutoLinkSuggestion
import com.rrajath.grove.vault.StorageException
import com.rrajath.grove.vault.Vault
import com.rrajath.grove.vault.storage.StorageError
import java.time.LocalDateTime

/**
 * Whether [CaptureTemplate.newFileTemplate]'s `#+title:` line is something the
 * user types (`%?`/`%cursor` somewhere in it, e.g. bare or `Re: %?`) rather
 * than derived (`%date`) or fixed -- and thus a candidate for splicing a
 * text-selection's title into. A template with no `#+title:` line at all has
 * nothing to splice into either.
 */
fun CaptureTemplate.hasUserDefinedTitle(): Boolean {
    val title = FilenamePattern.titleFromDraft(newFileTemplate) ?: return false
    return title.contains("%?") || title.contains("%cursor")
}

/** Result of [RoamNodeCreator.createOrLink]: linked to an already-existing node, or a fresh one created. */
sealed class RoamNodeResult {
    abstract val id: String
    abstract val title: String

    data class Linked(override val id: String, override val title: String) : RoamNodeResult()
    data class Created(override val id: String, override val title: String, val path: String) : RoamNodeResult()
}

/** The `[[id:…][Title]]` link text either branch of [RoamNodeResult] splices over the selection. */
fun RoamNodeResult.formatLink(): String = "[[id:$id][$title]]"

/**
 * Turns a selected span of text into a link to a Roam node -- an existing
 * title-matching one, or a brand-new file from [template]. Viewmodel-agnostic
 * so both `EditorViewModel` and `CaptureViewModel` can call it directly.
 */
object RoamNodeCreator {

    suspend fun createOrLink(
        vault: Vault,
        sync: SyncTrigger,
        template: CaptureTemplate,
        selectedTitle: String,
        autoLinkIndex: List<AutoLinkSuggestion>,
        now: LocalDateTime,
    ): RoamNodeResult {
        val titleLower = selectedTitle.lowercase()
        autoLinkIndex.firstOrNull { it.titleLower == titleLower }?.let {
            return RoamNodeResult.Linked(it.id, it.title)
        }

        val id = newOrgId()
        val expanded = PlaceholderExpander.expand(template.newFileTemplate, CaptureContext(now = now, id = id))
        // The file-level :ID: is always the generated one, whatever the template says.
        val fullText = RoamFileId.withFileId(
            expanded.text.substring(0, expanded.cursorOffset) +
                selectedTitle +
                expanded.text.substring(expanded.cursorOffset),
            id,
        )
        val title = FilenamePattern.titleFromDraft(fullText) ?: selectedTitle
        val slug = FilenamePattern.slugFromTitle(title)
        val stem = FilenamePattern.expand(template.filenamePattern, now, slug)
        val resolvedPath = if (template.roamDirectory.isBlank()) "$stem.org" else "${template.roamDirectory}/$stem.org"

        val newText = if (vault.open(resolvedPath) == null) {
            // Created with its text in one step: one upload on a lazy-create provider.
            val created = vault.createNotebook(resolvedPath, content = fullText)
            if (created is StorageError) throw StorageException(created)
            fullText
        } else {
            // Defensive fallback: resolvedPath already exists on disk (e.g. a
            // customized filenamePattern with no timestamp component, so no
            // title match was found above but the path still collides) --
            // don't clobber it. Append just the body, same as
            // CaptureViewModel.upsertRoamEntry's ExistingFile branch.
            val currentText = RoamFileId.ensureFileId(vault.open(resolvedPath)?.text.orEmpty(), id)
            val insertion = CaptureInserter.appendVerbatim(currentText, roamBody(fullText))
            vault.save(resolvedPath, insertion.newText)
            insertion.newText
        }
        sync.requestReindex(resolvedPath, newText, "roam node created from selection")
        // An already-existing file may keep its own :ID:; link to that one.
        val fileId = OrgParser.parse(newText).fileId?.takeIf { it.isNotBlank() } ?: id
        return RoamNodeResult.Created(fileId, title, resolvedPath)
    }

    /** Everything after a fresh draft's head (file-level properties drawer + `#+KEY:`
     *  preamble lines) -- mirrors CaptureViewModel.roamBody, needed only for the
     *  existing-path fallback above. */
    private fun roamBody(text: String): String {
        val doc = OrgParser.parse(text)
        val drawerEnd = OrgMutations.fileDrawerRange(doc)?.last ?: -1
        val prefaceEnd = OrgMutations.prefaceRange(doc)?.last ?: -1
        val headEnd = maxOf(drawerEnd, prefaceEnd)
        return doc.lines.drop(headEnd + 1).joinToString("\n")
    }
}
