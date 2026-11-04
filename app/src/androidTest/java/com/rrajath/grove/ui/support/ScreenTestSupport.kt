package com.rrajath.grove.ui.support

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.AppDispatchers
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.capture.TemplatesRepository
import com.rrajath.grove.data.FavoritesRepository
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.search.SearchRepository
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSettingsRepository
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.TestClockService
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.ui.capture.CaptureViewModel
import com.rrajath.grove.ui.editor.EditorViewModel
import com.rrajath.grove.ui.search.SearchViewModel
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.ui.vault.DocumentViewModel
import com.rrajath.grove.ui.vault.NotebooksViewModel
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Shared wiring for the Layer-2 Compose UI tests (see
 * internal/test-suite-02-ui-compose.md).
 *
 * Screens are hosted directly (not the nav graph) with a ViewModel built from
 * the shared `testFixtures` fakes. What has no JVM-free fake — the Context-backed
 * `SearchRepository` / `FavoritesRepository` / `SettingsRepository` and the real
 * `GroveApplication` — is taken from the instrumented app; on a clean emulator
 * those start at their defaults and the render/interaction assertions here don't
 * write to them.
 *
 * Real coroutine dispatchers are used (not a `TestDispatcher`): a UI test drives
 * time through `composeRule.waitUntil { … }`, never `advanceUntilIdle()`.
 */
class ScreenTestEnv(
    vaultFiles: Map<String, String> = OrgFixtures.all,
    index: Boolean = true,
) {
    val app: GroveApplication = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store = FakeFileStore(vaultFiles)
    val vaultFlow = MutableStateFlow<Vault?>(Vault(store))
    val sync = FakeSyncTrigger()
    val keywords = MutableStateFlow(OrgKeywords.DEFAULT)
    val fakeSettings = FakeSettingsRepository(GroveSettings())
    val database: GroveDatabase = InMemoryGroveDatabase.create(app)

    /** Real, Context-backed collaborators with no fake (DataStore-backed). */
    val settingsRepository = SettingsRepository(app, scope)
    val searchRepository = SearchRepository(app)
    val favoritesRepository = FavoritesRepository(app)
    val templatesRepository = TemplatesRepository(app)

    private val dispatchers = AppDispatchers()

    val clockService = TestClockService.create(vaultFlow, database, store, fakeSettings, scope)

    init {
        if (index) runBlocking { TestVaultSeeder.index(database, store) }
    }

    fun close() {
        database.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    fun searchViewModel() = SearchViewModel(
        vaultFlow = vaultFlow,
        sync = sync,
        searchRepository = searchRepository,
        database = database,
        keywordsFlow = keywords,
        settings = fakeSettings,
        dispatchers = dispatchers,
    )

    fun notebooksViewModel() = NotebooksViewModel(
        vaultFlow = vaultFlow,
        database = database,
        settingsRepository = settingsRepository,
        sync = sync,
        dispatchers = dispatchers,
    )

    fun documentViewModel() = DocumentViewModel(
        vaultFlow = vaultFlow,
        keywordsFlow = keywords,
        sync = sync,
        database = database,
        settingsRepository = settingsRepository,
        favoritesRepository = favoritesRepository,
        dispatchers = dispatchers,
        app = app,
        clockService = clockService,
    )

    fun editorViewModel() = EditorViewModel(
        vaultFlow = vaultFlow,
        sync = sync,
        database = database,
        settings = fakeSettings,
        keywords = keywords,
        dispatchers = dispatchers,
        templatesRepository = templatesRepository,
    )

    fun agendaViewModel() = com.rrajath.grove.ui.agenda.AgendaViewModel(
        settingsRepository = settingsRepository,
        keywordsFlow = keywords,
        database = database,
        vaultFlow = vaultFlow,
        sync = sync,
        dispatchers = dispatchers,
        clockService = clockService,
    )

    fun captureViewModel() = CaptureViewModel(
        templatesRepository = templatesRepository,
        database = database,
        sync = sync,
        vaultFlow = vaultFlow,
        settings = fakeSettings,
        dispatchers = dispatchers,
    )
}

/** Set the screen under `GroveTheme`, matching production chrome. */
fun AndroidComposeTestRule<*, ComponentActivity>.setGroveContent(content: @Composable () -> Unit) {
    setContent { GroveTheme { content() } }
}
