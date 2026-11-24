package com.rrajath.grove.ui.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.R
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.search.SearchRepository
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSettingsRepository
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.OrgFixtures
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.ui.util.UiText
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layer-1 integration coverage for [SearchViewModel]: a query is debounced and
 * run against the real FTS-backed index, and clearing it returns to the blank
 * state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SearchViewModelIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = FakeFileStore(OrgFixtures.all)
    private val vaultFlow = MutableStateFlow<Vault?>(Vault(store, parseDispatcher = mainDispatcherRule.dispatcher))
    private val sync = FakeSyncTrigger()
    private val settings = FakeSettingsRepository()
    private val keywords = MutableStateFlow(OrgKeywords.DEFAULT)
    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val searchRepository =
        SearchRepository(ApplicationProvider.getApplicationContext<Application>())

    private val liveVms = mutableListOf<SearchViewModel>()

    private fun search() = SearchViewModel(
        vaultFlow = vaultFlow,
        sync = sync,
        searchRepository = searchRepository,
        database = db,
        keywordsFlow = keywords,
        settings = settings,
        dispatchers = mainDispatcherRule.appDispatchers,
    ).also { liveVms += it }

    @After
    fun tearDown() {
        // The unit suite runs in one JVM; a VM whose viewModelScope is never
        // cancelled leaves eager collectors on SearchRepository's process-wide
        // "search" DataStore parked on a dead test scheduler, which backs up the
        // DataStore actor and flakes a later test (often one in this class).
        liveVms.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    /**
     * [search] plus a wait until the VM can run a query. Searches only run once
     * it has its facets, and those wait on [SearchRepository.savedSearches]: a
     * real DataStore that emits from a real IO thread, which [advanceUntilIdle]
     * can't see. Without this, a slow runner (CI) leaves the query unrun and the
     * test reads zero results. `catalog` is set only once both have arrived.
     */
    private fun TestScope.readySearch(): SearchViewModel {
        val vm = search()
        val deadline = System.currentTimeMillis() + 5_000
        advanceUntilIdle()
        while (vm.state.value.catalog.notebooks.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "SearchViewModel never received its facets" }
            Thread.sleep(5)
            advanceUntilIdle()
        }
        return vm
    }

    /** Line index of the first headline whose title starts with [prefix], in [file]. */
    private fun headlineLine(file: String, prefix: String): Int {
        val doc = OrgParser.parse(OrgFixtures.all.getValue(file))
        return doc.headlines.first { it.title.startsWith(prefix) }.lineIndex
    }

    @Test
    fun `a query matches the note whose body contains the term`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        vm.onQueryChange("photosynthesis")
        advanceTimeBy(400) // clear the 300ms debounce
        advanceUntilIdle()

        val files = vm.state.value.groups.map { it.fileName }
        assertEquals(listOf("reading-list.org"), files)
        // A body hit shows only the matched line in context, not its heading.
        val rows = vm.state.value.groups.single().rows
        assertEquals(
            listOf(
                SearchRow.Text(
                    fileName = "reading-list.org",
                    lineIndex = headlineLine("reading-list.org", "How leaves work"),
                    snippet = "The process of photosynthesis converts light into chemical energy.",
                    ordinal = 0,
                ),
            ),
            rows,
        )
        assertEquals(1, vm.state.value.resultCount)
    }

    @Test
    fun `a query matching a heading title shows the heading row`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        vm.onQueryChange("leaves")
        advanceTimeBy(400)
        advanceUntilIdle()

        val rows = vm.state.value.groups.flatMap { it.rows }
        assertEquals(1, rows.size)
        assertEquals("How leaves work", (rows.single() as SearchRow.Heading).result.title)
    }

    @Test
    fun `words that only meet across lines drop the note`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        // "leaves" is in the title, "photosynthesis" in the body: no single line has both.
        vm.onQueryChange("leaves photosynthesis")
        advanceTimeBy(400)
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.isEmpty())
    }

    @Test
    fun `clearing the query returns to the blank state`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        vm.onQueryChange("photosynthesis")
        advanceTimeBy(400)
        advanceUntilIdle()
        assertTrue(vm.state.value.groups.isNotEmpty())

        vm.onQueryChange("")
        advanceTimeBy(400)
        advanceUntilIdle()

        assertTrue(vm.state.value.isBlank)
        assertTrue(vm.state.value.groups.isEmpty())
    }

    @Test
    fun `a query does not run until the debounce window elapses`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        vm.onQueryChange("photosynthesis")
        advanceTimeBy(250) // still inside the 300ms debounce
        assertTrue("search must not have run yet", vm.state.value.groups.isEmpty())

        advanceTimeBy(200) // now past 300ms
        advanceUntilIdle()
        assertTrue(vm.state.value.groups.isNotEmpty())
    }

    @Test
    fun `a state filter narrows results to matching headings`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        vm.applyQuickFilter(SearchFilters(states = setOf("TODO")))
        advanceUntilIdle()

        val titles = vm.state.value.groups.flatMap { g -> g.headings.map { it.title } }
        assertTrue("expected an open TODO", titles.contains("Ship v2 release"))
        assertFalse("a DONE heading must be filtered out", titles.contains("Cut the changelog"))
        assertFalse("an IN-PROGRESS heading must be filtered out", titles.contains("Write the store listing"))
    }

    @Test
    fun `applyQuickQuery replaces the query and filters and runs immediately`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        vm.applyQuickFilter(SearchFilters(states = setOf("TODO")))
        advanceUntilIdle()
        assertTrue(vm.state.value.groups.isNotEmpty())

        vm.applyQuickQuery("photosynthesis")
        advanceTimeBy(400)
        advanceUntilIdle()

        assertEquals(0, vm.state.value.filters.activeCount)
        assertEquals(listOf("reading-list.org"), vm.state.value.groups.map { it.fileName })
    }

    @Test
    fun `an active-date preset counts as a filter and mirrors an a token into the query`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        vm.setActivePreset(DatePreset.TODAY)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.filters.activeCount)
        assertTrue(vm.state.value.query.contains("a.eq.today"))

        // Tapping the same preset again clears it.
        vm.setActivePreset(DatePreset.TODAY)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.filters.activeCount)
        assertFalse(vm.state.value.query.contains("a.eq.today"))
    }

    @Test
    fun `setState writes the new keyword to the file and requests a sync`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        val line = headlineLine("projects.org", "Tag the release")
        vm.setState("projects.org", line, "DONE")
        advanceUntilIdle()

        assertTrue(store.read("projects.org").contains("DONE Tag the release"))
        assertTrue(sync.reindexCalls.any { it.reason == "search state set" })
    }

    @Test
    fun `markDone sets the first done keyword and offers undo`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        val line = headlineLine("projects.org", "Tag the release")
        vm.markDone("projects.org", line)
        // Not advanceUntilIdle: that would also run the snack's auto-dismiss delay.
        advanceTimeBy(1000)

        assertTrue(store.read("projects.org").contains("DONE Tag the release"))
        assertEquals(UiText.res(R.string.snack_marked_done), vm.snack.value?.message)
        vm.undo()
        advanceUntilIdle()
        assertFalse(store.read("projects.org").contains("DONE Tag the release"))
    }

    @Test
    fun `markDone leaves a done heading alone`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()
        val before = store.read("projects.org")

        vm.markDone("projects.org", headlineLine("projects.org", "Cut the changelog"))
        advanceUntilIdle()

        assertEquals(before, store.read("projects.org"))
        assertEquals(null, vm.snack.value)
    }

    @Test
    fun `an ad N query lays results out by day`() = runTest {
        val today = java.time.LocalDate.now()
        fun stamp(d: java.time.LocalDate) = "<$d ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}>"
        store.write(
            "plans.org",
            """
            * TODO Pay rent
            DEADLINE: ${stamp(today.minusDays(2))}
            * TODO Dentist
            SCHEDULED: ${stamp(today.plusDays(1))}
            * Concert
            ${stamp(today)}
            """.trimIndent() + "\n",
        )
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        runQuery(vm, "b.plans ad.3")

        val days = vm.state.value.days!!
        assertEquals(listOf("overdue", today.toString(), today.plusDays(1).toString()), days.map { it.key })
        assertEquals(
            listOf(listOf("Pay rent"), listOf("Concert"), listOf("Dentist")),
            days.map { d -> d.results.map { it.title } },
        )
        assertTrue(vm.state.value.groups.isEmpty())
        assertEquals(3, vm.state.value.resultCount)

        runQuery(vm, "b.plans")
        assertEquals(null, vm.state.value.days)
    }

    @Test
    fun `scheduled and deadline pills show the time of day when set`() = runTest {
        val today = java.time.LocalDate.now()
        val dow = today.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        store.write(
            "timed.org",
            """
            * TODO Late call
            SCHEDULED: <$today $dow 23:00>
            * TODO Meeting
            DEADLINE: <$today $dow 10:00-11:30>
            * TODO All day
            SCHEDULED: <$today $dow>
            """.trimIndent() + "\n",
        )
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        runQuery(vm, "b.timed")

        val byTitle = vm.state.value.groups.flatMap { it.headings }.associateBy { it.title }
        assertEquals("today 23:00", byTitle.getValue("Late call").scheduledLabel)
        assertEquals("today 10:00-11:30", byTitle.getValue("Meeting").deadlineLabel)
        assertEquals("today", byTitle.getValue("All day").scheduledLabel)
    }

    // --- nested queries, it., Orgzly sort keys ---

    private fun kotlinx.coroutines.test.TestScope.runQuery(vm: SearchViewModel, query: String) {
        vm.onQueryChange(query)
        advanceTimeBy(400)
        advanceUntilIdle()
    }

    @Test
    fun `o st sorts by the configured keyword order, not the default`() = runTest {
        // Indexing still uses the default set (so done-ness is unchanged); only
        // the order the view model hands to the sort differs.
        keywords.value = OrgKeywords.parse("IN-PROGRESS TODO | CANCELLED DONE")
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        runQuery(vm, "b.projects o.st")

        val states = vm.state.value.groups.flatMap { g -> g.headings.map { it.keyword } }
        assertEquals(
            listOf("IN-PROGRESS", "TODO", "TODO", "TODO", "TODO", "TODO", "CANCELLED", "DONE"),
            states,
        )
    }

    @Test
    fun `reversed state sort with it filters through the whole pipeline`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        runQuery(vm, "b.projects (it.done OR i.IN-PROGRESS) .o.st")

        val titles = vm.state.value.groups.flatMap { g -> g.headings.map { it.title } }
        // Default order is TODO IN-PROGRESS | DONE CANCELLED, reversed here.
        assertEquals(listOf("Drop the widget rewrite", "Cut the changelog", "Write the store listing"), titles)
    }

    @Test
    fun `a nested query shows the snippet for the branch that matched`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        runQuery(vm, "b.reading-list (photosynthesis OR nomatchanywhere)")

        val rows = vm.state.value.groups.flatMap { it.rows }
        assertEquals(1, rows.size)
        val text = rows.single() as SearchRow.Text
        assertEquals(headlineLine("reading-list.org", "How leaves work"), text.lineIndex)
        assertTrue(text.snippet.contains("photosynthesis"))
        assertEquals(listOf("photosynthesis", "nomatchanywhere"), vm.state.value.matchedTerms)
    }

    @Test
    fun `a negated group excludes matching notes`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = readySearch()

        runQuery(vm, "b.projects .(it.done OR i.TODO)")

        val titles = vm.state.value.groups.flatMap { g -> g.headings.map { it.title } }
        assertEquals(listOf("Write the store listing"), titles)
    }
}
