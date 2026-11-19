package com.rrajath.grove.testing

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.settings.GroveSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the Robolectric bootstrap (M1): the runner loads, `robolectric.properties`
 * pins the SDK, `isIncludeAndroidResources` gives a real merged-resource Context,
 * and shared test-fixtures classes resolve from `test/`.
 *
 * `application = Application` bypasses `GroveApplication.onCreate`'s async wiring;
 * the M3 ViewModel integration tests build their collaborators directly instead.
 *
 * `GroveDatabase` under Robolectric: resolved in M3 — the desktop `sqlite-bundled`
 * natives (`testRuntimeOnly sqlite-bundled-jvm`) let `BundledSQLiteDriver` load on
 * the JVM with full FTS5. `RoomUnderRobolectricTest` guards that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RobolectricBootstrapTest {

    @Test
    fun `robolectric provides an application context at the pinned sdk`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(context.packageName)
        assertEquals(34, android.os.Build.VERSION.SDK_INT)
    }

    @Test
    fun `shared fixtures are visible from the jvm test source set`() = runTest {
        // Resolving these from `test/` proves the testFixtures wiring.
        val store = FakeFileStore()
        TestVaultSeeder.seed(store)
        assertTrue(store.exists("projects.org"))
        assertEquals(GroveSettings(), FakeSettingsRepository().current)
    }
}
