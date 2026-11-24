package com.rrajath.grove.capture

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID

private val Context.templatesDataStore: DataStore<Preferences> by preferencesDataStore(name = "templates")
private val KEY = stringPreferencesKey("templates_json")

/** Capture templates persisted as JSON; defaults seeded until first save. */
class TemplatesRepository(
    private val store: DataStore<Preferences>,
    /** The seeded list shown until the first save; read each time so it follows the UI language. */
    private val defaults: () -> List<CaptureTemplate> = { DefaultTemplates.all },
) {

    constructor(context: Context) : this(context.templatesDataStore, { DefaultTemplates.localized(context) })

    /** Bumped after every [save]; see [templates]. */
    private val saves = MutableStateFlow(0)

    /**
     * Re-subscribes to [DataStore.data] after each [save] because DataStore 1.2.1 can
     * lose a write for a collector that subscribes while that write is in flight: the
     * writer bumps the version before writing the file, the new collector reads the old
     * file tagged with the new version, then drops the cache update as already seen.
     * That happens when the templates list resumes while the editor's save is landing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val templates: Flow<List<CaptureTemplate>> = saves
        .flatMapLatest { store.data }
        .map { prefs -> prefs[KEY]?.let { TemplateSerializer.decode(it) } ?: defaults() }
        .distinctUntilChanged()

    suspend fun save(list: List<CaptureTemplate>) {
        store.edit { it[KEY] = TemplateSerializer.encode(list) }
        saves.update { it + 1 }
    }

    suspend fun upsert(template: CaptureTemplate) {
        val current = templates.first()
        val updated = if (current.any { it.id == template.id }) {
            current.map { if (it.id == template.id) template else it }
        } else {
            current + template
        }
        save(updated)
    }

    suspend fun delete(id: String) {
        save(templates.first().filterNot { it.id == id })
    }

    suspend fun move(id: String, delta: Int) {
        val current = templates.first().toMutableList()
        val from = current.indexOfFirst { it.id == id }
        if (from == -1) return
        val to = (from + delta).coerceIn(0, current.lastIndex)
        val item = current.removeAt(from)
        current.add(to, item)
        save(current)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
