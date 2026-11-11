package com.rrajath.grove.attach

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The app's current [AttachConfig]. [config] is null until settings have
 * loaded; the vault file store is built from it, so whenever a store exists
 * [current] is the user's config, never a stand-in. Consumers read [current]
 * when they resolve, or collect [config] to rebuild cached resolvers and drop
 * cached answers when it changes.
 */
interface AttachConfigSource {
    val config: StateFlow<AttachConfig?>

    /** The loaded config, or [AttachConfig.Default] before settings load (no store resolves anything then). */
    fun current(): AttachConfig = config.value ?: AttachConfig.Default
}

/**
 * Runs [action] each time the config changes after it first loads (not for
 * the load itself: nothing was resolved before it).
 */
fun AttachConfigSource.onConfigChange(scope: CoroutineScope, action: (AttachConfig) -> Unit): Job =
    scope.launch { config.filterNotNull().drop(1).collect { action(it) } }

/** A fixed config: tests and previews. */
class FixedAttachConfigSource(config: AttachConfig = AttachConfig.Default) : AttachConfigSource {
    override val config: StateFlow<AttachConfig?> = MutableStateFlow<AttachConfig?>(config).asStateFlow()
}

/** The config from Settings § Attachments: [folders] is the stored attachment folder. */
class SettingsAttachConfigSource(folders: Flow<String>, scope: CoroutineScope) : AttachConfigSource {
    private val state = MutableStateFlow<AttachConfig?>(null)
    override val config: StateFlow<AttachConfig?> = state.asStateFlow()

    init {
        scope.launch { folders.distinctUntilChanged().collect { state.value = AttachConfig.forFolder(it) } }
    }
}
