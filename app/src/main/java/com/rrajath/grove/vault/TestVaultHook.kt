package com.rrajath.grove.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Debug-only escape hatch that points the vault at a plain app-owned directory
 * instead of a Storage Access Framework tree, so instrumentation and Maestro can
 * get a populated vault with no system folder picker and no runtime permission.
 *
 * Only `debug.DebugTestVault` calls [useDirectory], from a `MainActivity`
 * launch-intent extra, and only when `BuildConfig.TEST_HOOKS` is on (`debug` +
 * the benchmark variants). In a release build R8 dead-strips that path, so
 * [root] stays `null` for the life of the process and
 * [com.rrajath.grove.GroveApplication.fileStore] behaves exactly as before.
 */
object TestVaultHook {
    private val _root = MutableStateFlow<File?>(null)

    /** The forced directory-vault root, or `null` for normal SAF behavior. */
    val root: StateFlow<File?> = _root.asStateFlow()

    /**
     * Set alongside [useDirectory] so a freshly `clearState`d Maestro run lands
     * on Notebooks instead of onboarding (the `onboardingDone` pref is wiped and
     * cannot be written synchronously before the first composition). Read once at
     * startup; never toggled at runtime.
     */
    @Volatile
    var skipOnboarding: Boolean = false
        private set

    fun useDirectory(dir: File) {
        _root.value = dir
        skipOnboarding = true
    }

    /** Unit tests only: undoes [useDirectory] so the forced vault can't leak into the next test. */
    internal fun reset() {
        _root.value = null
        skipOnboarding = false
    }
}
