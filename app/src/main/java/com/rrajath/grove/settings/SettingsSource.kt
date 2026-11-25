package com.rrajath.grove.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Read-only slice of [SettingsRepository] that ViewModels observe. Narrowing the
 * dependency to this interface lets tests supply a trivial fake
 * (`FakeSettingsRepository`, backed by a `MutableStateFlow`) instead of a real
 * DataStore. [SettingsRepository] implements it.
 */
interface SettingsSource {
    /**
     * Persisted settings. Nothing until the first DataStore read, then the
     * current [GroveSettings] on every change (hot, `replay = 1`).
     */
    val settings: Flow<GroveSettings>

    /**
     * The settings as stored right now, reflecting every edit that has returned.
     * [settings] can briefly replay the previous value after an edit; read this
     * instead when acting on a value just written.
     */
    suspend fun current(): GroveSettings = settings.first()
}
