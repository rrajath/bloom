package com.rrajath.grove.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.data.GroveDatabase
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Builds a throwaway in-memory [GroveDatabase] wired through the production
 * [GroveDatabase.inMemory] path, so tests exercise the real FTS bootstrap and
 * driver rather than a hand-rolled copy.
 *
 * Works from both suites: the default [Context] is the Robolectric or
 * instrumented application. Callers own the instance and should
 * [GroveDatabase.close] it when done (a `@get:Rule TemporaryFolder`-style
 * teardown, or `@After`).
 *
 * A JVM (Robolectric) ViewModel test should pass its `TestDispatcher` as
 * [queryCoroutineContext] so DAO queries run in the test's virtual time and
 * `advanceUntilIdle()` deterministically drains them; instrumented callers keep
 * the [Dispatchers.IO] default.
 */
object InMemoryGroveDatabase {
    fun create(
        context: Context = ApplicationProvider.getApplicationContext(),
        queryCoroutineContext: CoroutineContext = Dispatchers.IO,
    ): GroveDatabase = GroveDatabase.inMemory(context, queryCoroutineContext)
}
