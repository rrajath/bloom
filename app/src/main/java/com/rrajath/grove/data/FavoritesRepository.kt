package com.rrajath.grove.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites")
private val FAVORITES_KEY = stringPreferencesKey("favorites_json")

class FavoritesRepository(private val context: Context) {

    val favorites: Flow<List<FavoriteNote>> = context.favoritesDataStore.data.map { prefs ->
        prefs[FAVORITES_KEY]?.let { FavoriteNoteSerializer.decode(it) } ?: emptyList()
    }

    /**
     * Identifies a favorite by its stable [customId] when both sides have one (survives external
     * edits that shift [lineIndex]); falls back to the (fileName, lineIndex) pair otherwise, e.g.
     * for favorites saved before ids existed or when a caller has no id at hand.
     */
    private fun matches(fav: FavoriteNote, fileName: String, lineIndex: Int, customId: String?): Boolean =
        if (customId != null && fav.customId != null) fav.fileName == fileName && fav.customId == customId
        else fav.fileName == fileName && fav.lineIndex == lineIndex

    suspend fun addFavorite(note: FavoriteNote) {
        val current = favorites.first()
        if (current.any { matches(it, note.fileName, note.lineIndex, note.customId) }) return
        context.favoritesDataStore.edit { it[FAVORITES_KEY] = FavoriteNoteSerializer.encode(current + note) }
    }

    suspend fun removeFavorite(fileName: String, lineIndex: Int, customId: String? = null) {
        val updated = favorites.first().filterNot { matches(it, fileName, lineIndex, customId) }
        context.favoritesDataStore.edit { it[FAVORITES_KEY] = FavoriteNoteSerializer.encode(updated) }
    }

    suspend fun renameFavorite(fileName: String, lineIndex: Int, title: String, customId: String? = null) {
        val updated = favorites.first().map {
            if (matches(it, fileName, lineIndex, customId)) it.copy(title = title) else it
        }
        context.favoritesDataStore.edit { it[FAVORITES_KEY] = FavoriteNoteSerializer.encode(updated) }
    }

    /** Keeps favorites in [oldPath] pointing at the file after it is renamed or moved to [newPath]. */
    suspend fun rebaseFile(oldPath: String, newPath: String) = rewrite { it.rebasedFile(oldPath, newPath) }

    /** Keeps favorites in files under [oldDir] pointing at them after the folder is renamed to [newDir]. */
    suspend fun rebaseFolder(oldDir: String, newDir: String) = rewrite { it.rebasedFolder(oldDir, newDir) }

    /** Re-points favorites whose file vanished at wherever their heading id now lives (see [relocatedMissing]). */
    suspend fun relocateMissing(existingFiles: Set<String>, locate: suspend (String) -> NoteKey?) {
        val current = favorites.first()
        // Cheap pre-check outside the transaction: most index emissions have nothing to heal.
        if (current.relocatedMissing(existingFiles, locate) === current) return
        rewrite { it.relocatedMissing(existingFiles, locate) }
    }

    /** Read-modify-write inside one DataStore transaction; skips the write when [transform] changes nothing. */
    private suspend fun rewrite(transform: suspend (List<FavoriteNote>) -> List<FavoriteNote>) {
        context.favoritesDataStore.edit { prefs ->
            val current = prefs[FAVORITES_KEY]?.let { FavoriteNoteSerializer.decode(it) } ?: emptyList()
            val updated = transform(current)
            if (updated !== current) prefs[FAVORITES_KEY] = FavoriteNoteSerializer.encode(updated)
        }
    }

    /** Swaps the favorite matching (fileName, lineIndex[, customId]) with its neighbor [delta] slots away (-1 up, +1 down); no-op past either end. */
    suspend fun moveFavorite(fileName: String, lineIndex: Int, delta: Int, customId: String? = null) {
        val current = favorites.first().toMutableList()
        val from = current.indexOfFirst { matches(it, fileName, lineIndex, customId) }
        val to = from + delta
        if (from < 0 || to < 0 || to >= current.size) return
        current.add(to, current.removeAt(from))
        context.favoritesDataStore.edit { it[FAVORITES_KEY] = FavoriteNoteSerializer.encode(current) }
    }
}
