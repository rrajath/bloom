package com.rrajath.grove.data

import com.rrajath.grove.org.OrgHeadline
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FavoriteNote(
    val fileName: String,
    val lineIndex: Int,
    val title: String,
    /**
     * The heading's stable `:ID:` (or `:CUSTOM_ID:`), resolved by
     * [com.rrajath.grove.ui.vault.DocumentViewModel.ensureStableId] before this favorite is saved,
     * so this favorite survives external edits that shift line numbers (the app's whole model is that
     * `.org` files are edited outside the app too). Null for favorites added before this field existed,
     * or if id-writing failed; those fall back to [lineIndex]-based lookup. Defaulted so old persisted
     * JSON without this key still decodes via [FavoriteNoteSerializer]'s `ignoreUnknownKeys`.
     * Named `customId` for persisted-JSON compatibility; it may hold either kind of id.
     */
    val customId: String? = null,
)

/**
 * True when [h] is the note this favorite refers to. When this favorite has a [customId], [h]
 * must carry that same value as either its `:ID:` or its `:CUSTOM_ID:` (a heading can have
 * both, and the favorite may have stored either). A headline with no id of its own is never
 * treated as a line-index coincidence, otherwise a note that later drifts onto the favorite's
 * old stored [lineIndex] (e.g. a new note inserted right above it) would wrongly read as
 * favorited too. Only favorites with no [customId] at all (saved before ids existed) fall
 * back to raw line index.
 */
fun FavoriteNote.matches(h: OrgHeadline): Boolean {
    return if (customId != null) customId == h.id || customId == h.customId
    else lineIndex == h.lineIndex
}

/** True when either of [h]'s stable ids (`:ID:` or `:CUSTOM_ID:`) is in this set of favorite ids. */
fun Set<String>.containsIdOf(h: OrgHeadline): Boolean =
    (h.id != null && h.id in this) || (h.customId != null && h.customId in this)

/**
 * Re-points every favorite whose file is [oldPath] (a notebook rename or move) at [newPath].
 * Returns the same list instance when nothing changed, so callers can skip the write.
 */
fun List<FavoriteNote>.rebasedFile(oldPath: String, newPath: String): List<FavoriteNote> =
    rebased { if (it == oldPath) newPath else null }

/**
 * Re-points every favorite under folder [oldDir] (the folder itself is never a favorite, only
 * files inside it) at the same relative path under [newDir], for a folder rename.
 */
fun List<FavoriteNote>.rebasedFolder(oldDir: String, newDir: String): List<FavoriteNote> {
    val oldPrefix = "${oldDir.trim('/')}/"
    val newPrefix = "${newDir.trim('/')}/"
    return rebased { if (it.startsWith(oldPrefix)) newPrefix + it.removePrefix(oldPrefix) else null }
}

private fun List<FavoriteNote>.rebased(newPathFor: (String) -> String?): List<FavoriteNote> {
    var changed = false
    val out = map { fav ->
        val newPath = newPathFor(fav.fileName)
        if (newPath != null && newPath != fav.fileName) {
            changed = true
            fav.copy(fileName = newPath)
        } else fav
    }
    return if (changed) out else this
}

/**
 * Heals favorites whose file no longer exists in the vault (renamed or moved outside the app, e.g.
 * in Emacs and synced over) by finding the heading's stable id elsewhere. [existingFiles] is every
 * vault file the index knows; [locate] returns where a heading carrying that `:ID:` or `:CUSTOM_ID:`
 * now lives, or null. Favorites with no id, or whose id can't be found, are left untouched (never
 * dropped), so a half-built index can't destroy them. Returns the same instance when nothing moved.
 */
suspend fun List<FavoriteNote>.relocatedMissing(
    existingFiles: Set<String>,
    locate: suspend (id: String) -> NoteKey?,
): List<FavoriteNote> {
    var changed = false
    val out = map { fav ->
        val id = fav.customId
        if (id == null || fav.fileName in existingFiles) return@map fav
        val found = locate(id) ?: return@map fav
        changed = true
        fav.copy(fileName = found.fileName, lineIndex = found.lineIndex)
    }
    return if (changed) out else this
}

object FavoriteNoteSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(favorites: List<FavoriteNote>): String = json.encodeToString(Wrapper(favorites))

    fun decode(text: String): List<FavoriteNote> =
        runCatching { json.decodeFromString<Wrapper>(text).favorites }.getOrDefault(emptyList())

    @Serializable
    private data class Wrapper(val favorites: List<FavoriteNote>)
}
