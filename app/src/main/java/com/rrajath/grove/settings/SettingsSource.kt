package com.rrajath.grove.settings

import kotlinx.coroutines.flow.Flow

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
}
