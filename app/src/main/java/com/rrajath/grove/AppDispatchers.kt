package com.rrajath.grove

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The three coroutine dispatchers the app's ViewModels and repositories use,
 * bundled so tests can substitute a single [kotlinx.coroutines.test.TestDispatcher]
 * for all of them and drive coroutines deterministically with `advanceUntilIdle()`.
 *
 * Production code holds the real values (see [GroveApplication.dispatchers]);
 * nothing else should read [Dispatchers] directly once a class has been handed
 * an [AppDispatchers].
 */
data class AppDispatchers(
    val main: CoroutineDispatcher = Dispatchers.Main,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
)
