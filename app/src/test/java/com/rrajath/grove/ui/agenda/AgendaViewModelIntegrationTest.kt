package com.rrajath.grove.ui.agenda

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgTimestamp
import com.rrajath.grove.org.PlanningKind
import com.rrajath.grove.settings.SettingsRepository
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSyncTrigger
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.LocalDate

/**
 * Layer-1 integration coverage for [AgendaViewModel]: planned notes indexed in
 * Room are bucketed into the agenda, and a swipe-to-done writes the file and
 * requests a sync.
 *
 * See internal/test-suite-01-integration-robolectric.md § Coverage matrix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AgendaViewModelIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // A note scheduled yesterday is always overdue, whatever day the test runs.
    private val yesterday = LocalDate.now().minusDays(1)

    private fun orgDate(d: LocalDate): String =
        "<$d ${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}>"

    /** A repeater goes *inside* the timestamp's brackets, e.g. `<2025-06-09 Mon +1w>`. */
    private fun orgDate(d: LocalDate, repeater: String): String =
        "<$d ${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} $repeater>"

    private val vaultText = """
        #+TITLE: Agenda fixture

        * TODO Renew the domain
        SCHEDULED: ${orgDate(yesterday)}
        * TODO Undated idea
    """.trimIndent() + "\n"

    private val today = LocalDate.now()
    private val inThreeDays = today.plusDays(3)
    private val inTwentyDays = today.plusDays(20) // outside the 14-day initial window, inside one page-out

    private val bucketsVault = """
        #+TITLE: Buckets

        * TODO Water the plants
        SCHEDULED: ${orgDate(today)}
        * TODO Quarterly review
        SCHEDULED: ${orgDate(inThreeDays)}
        * TODO Renew the passport
        SCHEDULED: ${orgDate(inTwentyDays)}
    """.trimIndent() + "\n"

    /** Line index of the first headline whose title starts with [prefix], in [store]'s [file]. */
    private suspend fun headlineLine(file: String, prefix: String): Int {
        val doc = com.rrajath.grove.org.OrgParser.parse(store.read(file))
        return doc.headlines.first { it.title.startsWith(prefix) }.lineIndex
    }

    private val store = FakeFileStore(mapOf("agenda.org" to vaultText))
    private val vaultFlow = MutableStateFlow<Vault?>(Vault(store, parseDispatcher = mainDispatcherRule.dispatcher))
    private val sync = FakeSyncTrigger()
    private val keywords = MutableStateFlow(OrgKeywords.DEFAULT)
    private val db: GroveDatabase =
        InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val settingsRepository = SettingsRepository(
        ApplicationProvider.getApplicationContext<Application>(),
        CoroutineScope(mainDispatcherRule.dispatcher),
    )

    private fun agenda() = AgendaViewModel(
        settingsRepository = settingsRepository,
        keywordsFlow = keywords,
        database = db,
        vaultFlow = vaultFlow,
        sync = sync,
        dispatchers = mainDispatcherRule.appDispatchers,
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a note scheduled in the past lands in the overdue bucket`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()

        val vm = agenda()
        advanceUntilIdle()

        val overdueTitles = vm.state.value.overdue.map { it.title }
        assertTrue("expected 'Renew the domain' in overdue, was $overdueTitles",
            overdueTitles.contains("Renew the domain"))
        // The undated heading is excluded by the plannedNotes SQL narrowing.
        val everyTitle = (vm.state.value.overdue + vm.state.value.groups.flatMap { it.rows })
            .map { it.title }
        assertTrue(everyTitle.none { it == "Undated idea" })
    }

    @Test
    fun `markDone writes a done keyword to the file and requests a sync`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val row = vm.state.value.overdue.single { it.title == "Renew the domain" }
        vm.markDone(row.fileName, row.lineIndex)
        advanceUntilIdle()

        val updated = store.read("agenda.org")
        assertTrue("file should carry a DONE keyword now:\n$updated",
            updated.contains("* DONE Renew the domain") || updated.contains("DONE Renew the domain"))
        assertTrue(sync.reindexCalls.isNotEmpty())
    }

    @Test
    fun `markDone is a no-op on a keyword-less bare-timestamp event`() = runTest {
        val eventVault = """
            #+TITLE: Events

            * Team offsite
            ${orgDate(today)}
        """.trimIndent() + "\n"
        store.write("events.org", eventVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val before = store.read("events.org")
        val line = headlineLine("events.org", "Team offsite")
        vm.markDone("events.org", line)
        advanceUntilIdle()

        assertEquals("an event has nothing to complete", before, store.read("events.org"))
        assertFalse(sync.syncRequests.contains("agenda toggle done"))
    }

    @Test
    fun `markDone is a no-op on a keyword-less scheduled heading`() = runTest {
        val eventVault = """
            #+TITLE: Events

            * Dentist appointment
            SCHEDULED: ${orgDate(today)}
        """.trimIndent() + "\n"
        store.write("events.org", eventVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val before = store.read("events.org")
        val line = headlineLine("events.org", "Dentist appointment")
        vm.markDone("events.org", line)
        advanceUntilIdle()

        assertEquals("no keyword means nothing to complete", before, store.read("events.org"))
        assertFalse(sync.syncRequests.contains("agenda toggle done"))
    }

    @Test
    fun `advanceRepeater advances a keyword-less SCHEDULED repeater and requests a sync`() = runTest {
        val recurringVault = """
            #+TITLE: Recurring

            * Take out compost
            SCHEDULED: ${orgDate(today, "+1w")}
        """.trimIndent() + "\n"
        store.write("recurring.org", recurringVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val row = vm.state.value.groups.flatMap { it.rows }.single { it.title == "Take out compost" }
        assertEquals(PlanningKind.SCHEDULED, row.repeaterKind)

        vm.advanceRepeater(row)
        advanceUntilIdle()

        val updated = store.read("recurring.org")
        assertTrue("SCHEDULED should have advanced a week and kept no keyword:\n$updated",
            updated.contains("SCHEDULED: ${orgDate(today.plusDays(7), "+1w")}") && !updated.contains("* TODO"))
        assertTrue(sync.reindexCalls.isNotEmpty())
    }

    @Test
    fun `advanceRepeater advances a keyword-less repeating DEADLINE, leaving a non-repeating SCHEDULED on the same heading untouched`() = runTest {
        val recurringVault = """
            #+TITLE: Recurring

            * Renew passport
            SCHEDULED: ${orgDate(today)} DEADLINE: ${orgDate(today.plusDays(5), "+1y")}
        """.trimIndent() + "\n"
        store.write("recurring.org", recurringVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val row = vm.state.value.groups.flatMap { it.rows }.single { it.title == "Renew passport" }
        assertEquals(PlanningKind.DEADLINE, row.repeaterKind)

        vm.advanceRepeater(row)
        advanceUntilIdle()

        val updated = store.read("recurring.org")
        assertTrue("DEADLINE should advance a year:\n$updated",
            updated.contains("DEADLINE: ${orgDate(today.plusDays(5).plusYears(1), "+1y")}"))
        assertTrue("non-repeating SCHEDULED must stay untouched:\n$updated",
            updated.contains("SCHEDULED: ${orgDate(today)}"))
    }

    @Test
    fun `a note scheduled today lands in the Today tab`() = runTest {
        store.write("buckets.org", bucketsVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val todayTitles = vm.state.value.groups.flatMap { it.rows }.map { it.title }
        assertTrue("expected 'Water the plants' in Today, was $todayTitles",
            todayTitles.contains("Water the plants"))
        assertTrue(vm.state.value.todayCount >= 1)
        assertFalse("a future note must not be in Today", todayTitles.contains("Quarterly review"))
    }

    @Test
    fun `a note scheduled within the window lands in the Upcoming tab`() = runTest {
        store.write("buckets.org", bucketsVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        vm.setTab(AgendaTab.UPCOMING)
        advanceUntilIdle()

        val upcomingTitles = vm.state.value.groups.flatMap { it.rows }.map { it.title }
        assertTrue("expected 'Quarterly review' in Upcoming, was $upcomingTitles",
            upcomingTitles.contains("Quarterly review"))
        assertFalse("a note 30 days out is beyond the initial window",
            upcomingTitles.contains("Renew the passport"))
    }

    @Test
    fun `loadMoreDays grows the window to reach a further-out note`() = runTest {
        store.write("buckets.org", bucketsVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()
        vm.setTab(AgendaTab.UPCOMING)
        advanceUntilIdle()

        vm.loadMoreDays()
        advanceUntilIdle()

        val upcomingTitles = vm.state.value.groups.flatMap { it.rows }.map { it.title }
        assertTrue("expected 'Renew the passport' after paging, was $upcomingTitles",
            upcomingTitles.contains("Renew the passport"))
    }

    @Test
    fun `a heading scheduled and active on the same day shows once, not twice`() = runTest {
        val sameDayVault = """
            #+TITLE: Same day

            * TODO Standup
            SCHEDULED: ${orgDate(today)}
            ${orgDate(today)}
        """.trimIndent() + "\n"
        store.write("sameday.org", sameDayVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val todayTitles = vm.state.value.groups.flatMap { it.rows }.map { it.title }
        assertEquals(
            "expected exactly one 'Standup' row, was $todayTitles",
            1,
            todayTitles.count { it == "Standup" },
        )
    }

    @Test
    fun `a heading scheduled today with an active date days later shows in both`() = runTest {
        val differentDaysVault = """
            #+TITLE: Different days

            * TODO Meeting
            SCHEDULED: ${orgDate(today)}
            ${orgDate(inThreeDays)}
        """.trimIndent() + "\n"
        store.write("differentdays.org", differentDaysVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val todayTitles = vm.state.value.groups.flatMap { it.rows }.map { it.title }
        assertEquals(
            "expected exactly one 'Meeting' row in Today, was $todayTitles",
            1,
            todayTitles.count { it == "Meeting" },
        )

        vm.setTab(AgendaTab.UPCOMING)
        advanceUntilIdle()

        val upcomingTitles = vm.state.value.groups.flatMap { it.rows }.map { it.title }
        assertEquals(
            "expected exactly one 'Meeting' row in Upcoming (its active-date occurrence), was $upcomingTitles",
            1,
            upcomingTitles.count { it == "Meeting" },
        )
    }

    @Test
    fun `moveOverdueToToday rewrites the overdue SCHEDULED date and requests a sync`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()
        assertTrue(vm.state.value.overdue.any { it.title == "Renew the domain" })

        vm.moveOverdueToToday()
        advanceUntilIdle()

        val text = store.read("agenda.org")
        assertTrue("SCHEDULED should now be today ($today):\n$text", text.contains("SCHEDULED: <$today"))
        assertFalse("the old date should be gone", text.contains(yesterday.toString()))
        assertTrue(sync.reindexCalls.any { it.reason == "agenda move overdue" })
    }

    @Test
    fun `setPlanningDates writes the new SCHEDULED date to the file and requests a sync`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val line = headlineLine("agenda.org", "Renew the domain")
        vm.setPlanningDates("agenda.org", line, OrgTimestamp(inThreeDays), null, emptyList())
        advanceUntilIdle()

        assertTrue(store.read("agenda.org").contains("SCHEDULED: <$inThreeDays"))
        assertTrue(sync.reindexCalls.any { it.reason == "agenda planning edit" })
    }

    @Test
    fun `undo after markDone restores the file to its pre-mutation text`() = runTest {
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()
        val before = store.read("agenda.org")

        val row = vm.state.value.overdue.single { it.title == "Renew the domain" }
        vm.markDone(row.fileName, row.lineIndex)
        advanceUntilIdle()
        assertFalse(store.read("agenda.org") == before)

        vm.undo()
        advanceUntilIdle()

        assertEquals(before, store.read("agenda.org"))
        assertTrue(sync.reindexCalls.any { it.reason == "agenda undo" })
    }

    // --- report phases 4 and 5 ---

    private val parityVault get() = """
        #+TITLE: Parity

        * TODO Weekly review
        SCHEDULED: ${orgDate(today.minusDays(14), "+1w")}
        * TODO File taxes
        DEADLINE: ${orgDate(inThreeDays)}
        * TODO Held back
        SCHEDULED: ${orgDate(yesterday).dropLast(1)} -3d>
    """.trimIndent() + "\n"

    @Test
    fun `Today lists a near deadline under Due soon and hides a delayed plan`() = runTest {
        store.write("parity.org", parityVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()

        val dueSoon = vm.state.value.groups.first()
        assertEquals(AgendaViewModel.DUE_SOON_KEY, dueSoon.key)
        assertEquals(listOf("File taxes"), dueSoon.rows.map { it.title })
        val everyTitle = (vm.state.value.overdue + vm.state.value.groups.flatMap { it.rows }).map { it.title }
        assertFalse("a -3d delay holds yesterday's plan back", everyTitle.contains("Held back"))
        // The overdue weekly task stays in the overdue card, once.
        assertEquals(1, vm.state.value.overdue.count { it.title == "Weekly review" })
    }

    @Test
    fun `Upcoming previews the next repeats of an overdue weekly task`() = runTest {
        store.write("parity.org", parityVault)
        TestVaultSeeder.index(db, store)
        advanceUntilIdle()
        val vm = agenda()
        advanceUntilIdle()
        vm.setTab(AgendaTab.UPCOMING)
        advanceUntilIdle()

        val previews = vm.state.value.groups.flatMap { it.rows }.filter { it.title == "Weekly review" }
        // Repeats on today+7 and today+14 fall inside the 14-day window only for the first.
        assertEquals(1, previews.size)
        assertEquals(PlanningKind.SCHEDULED, previews.single().previewKind)
        assertTrue(vm.state.value.groups.none { it.key == AgendaViewModel.DUE_SOON_KEY })
    }
}
