package com.rrajath.grove.testing

import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SettingsSource] backed by a [MutableStateFlow] so a test can seed the initial
 * [GroveSettings] and push changes mid-test. Unlike the real repository this
 * emits its seed immediately (no "nothing until first read" gap); a test that
 * needs to assert the pre-load behaviour should drive the real repository under
 * Robolectric instead.
 */
class FakeSettingsRepository(
    initial: GroveSettings = GroveSettings(),
) : SettingsSource {

    private val _settings = MutableStateFlow(initial)

    override val settings: StateFlow<GroveSettings> = _settings.asStateFlow()

    /** Current value, for direct assertions. */
    val current: GroveSettings get() = _settings.value

    /** Replace the whole settings object. */
    fun set(value: GroveSettings) {
        _settings.value = value
    }

    /** Mutate the current settings object. */
    fun update(block: (GroveSettings) -> GroveSettings) {
        _settings.value = block(_settings.value)
    }
}
