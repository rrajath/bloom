package com.rrajath.grove.capture

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    val templates: Flow<List<CaptureTemplate>> = store.data.map { prefs ->
        prefs[KEY]?.let { TemplateSerializer.decode(it) } ?: defaults()
    }

    suspend fun save(list: List<CaptureTemplate>) {
        store.edit { it[KEY] = TemplateSerializer.encode(list) }
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
