package com.rrajath.grove.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.searchDataStore: DataStore<Preferences> by preferencesDataStore(name = "search")
private val SAVED_KEY = stringPreferencesKey("saved_searches_json")

/** Query syntax the stored searches were written in; absent = 1 (see [QueryMigration]). */
private val SYNTAX_KEY = intPreferencesKey("saved_searches_syntax")
private const val CURRENT_SYNTAX = 2

/** Saved searches (drawer shortcuts). */
class SearchRepository(private val context: Context) {

    /** Searches stored before the Orgzly date semantics are read through
     *  [QueryMigration] so they keep their meaning; the first write persists that. */
    val savedSearches: Flow<List<SavedSearch>> = context.searchDataStore.data.map { prefs ->
        val stored = prefs[SAVED_KEY]?.let { SavedSearchSerializer.decode(it) } ?: return@map DefaultSavedSearches.all
        if ((prefs[SYNTAX_KEY] ?: 1) >= CURRENT_SYNTAX) stored
        else stored.map { it.copy(query = QueryMigration.toV2(it.query)) }
    }

    private suspend fun persist(searches: List<SavedSearch>) {
        context.searchDataStore.edit {
            it[SAVED_KEY] = SavedSearchSerializer.encode(searches)
            it[SYNTAX_KEY] = CURRENT_SYNTAX
        }
    }

    /** Replaces every saved search, Quick Start overrides included, with [list] (settings backup import). */
    suspend fun replaceAll(list: List<SavedSearch>) = persist(list)

    suspend fun saveSearch(name: String, query: String) {
        val current = savedSearches.first()
        val updated = current + SavedSearch(UUID.randomUUID().toString(), name, query)
        persist(updated)
    }

    suspend fun deleteSearch(id: String) {
        val updated = savedSearches.first().filterNot { it.id == id }
        persist(updated)
    }

    suspend fun renameSearch(id: String, name: String) {
        val updated = savedSearches.first().map { if (it.id == id) it.copy(name = name) else it }
        persist(updated)
    }

    suspend fun updateSearchQuery(id: String, query: String) {
        val updated = savedSearches.first().map { if (it.id == id) it.copy(query = query) else it }
        persist(updated)
    }

    /** Upsert a Quick Start card's override row (see [QuickStartOverrides]): updates
     *  it in place if already overridden, otherwise adds it. */
    suspend fun setQuickStartOverride(id: String, name: String, query: String) {
        val current = savedSearches.first()
        val updated = if (current.any { it.id == id }) {
            current.map { if (it.id == id) it.copy(query = query) else it }
        } else {
            current + SavedSearch(id, name, query)
        }
        persist(updated)
    }

    /** Swaps the search [id] with its neighbor [delta] slots away (-1 up, +1 down); no-op past either end. */
    suspend fun moveSearch(id: String, delta: Int) {
        val current = savedSearches.first().toMutableList()
        val from = current.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to < 0 || to >= current.size) return
        current.add(to, current.removeAt(from))
        persist(current)
    }
}
