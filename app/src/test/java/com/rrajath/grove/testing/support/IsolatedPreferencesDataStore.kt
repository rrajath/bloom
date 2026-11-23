package com.rrajath.grove.testing.support

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files

/**
 * A preferences [DataStore] owned by one test, for repositories that take one
 * (`TemplatesRepository(store)`, `FavoritesRepository(store)`).
 *
 * The production `preferencesDataStore` delegates are process-wide singletons, so
 * every Robolectric test in a JVM fork shares them. DataStore runs each `edit`
 * transform on the *caller's* dispatcher, so a ViewModel coroutine leaked by an
 * earlier test, parked on a test dispatcher nobody pumps anymore, can wedge the
 * shared store and hang every later edit. Whether that bites depends on class
 * order, which differs between macOS and the Linux CI runner.
 *
 * Call [close] from `@After`.
 */
class IsolatedPreferencesDataStore {
    private val dir: File = Files.createTempDirectory("test-datastore").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        File(dir, "test.preferences_pb")
    }

    fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }
}
