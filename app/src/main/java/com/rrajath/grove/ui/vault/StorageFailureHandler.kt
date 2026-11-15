package com.rrajath.grove.ui.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.rrajath.grove.vault.StorageException

/** The [StorageException] behind [error] (itself, or anywhere in its cause chain), or null. */
fun findStorageException(error: Throwable): StorageException? =
    generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<StorageException>().firstOrNull()

/**
 * Safety net for writes nobody handled: [com.rrajath.grove.vault.Vault.save]
 * throws a [StorageException] when the provider refuses, and most call sites
 * (agenda toggles, outline edits, search actions) run inside a `viewModelScope`
 * launch that has no handler of its own. Without this the app would crash on
 * every refused write. With it, the reason is toasted and the app carries on;
 * the write simply didn't happen, so nothing is half-applied.
 *
 * Screens that can do better (the editors, capture, the notebook list) handle
 * the failure themselves and never reach this. Anything that isn't a storage
 * failure goes to the previous handler unchanged.
 */
fun installStorageFailureHandler(context: Context) {
    val app = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    val main = Handler(Looper.getMainLooper())
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        val storage = findStorageException(error)
        if (storage == null) {
            previous?.uncaughtException(thread, error)
        } else {
            main.post {
                Toast.makeText(app, storageErrorMessage(app, storage.error), Toast.LENGTH_LONG).show()
            }
        }
    }
}

private const val MAX_CAUSE_DEPTH = 8
