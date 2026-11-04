package com.rrajath.grove.clock

import com.rrajath.grove.data.GroveDatabase
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.clockedMinutes
import com.rrajath.grove.org.clocks
import com.rrajath.grove.org.runningClock
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.testing.FakeFileStore
import com.rrajath.grove.testing.FakeSettingsRepository
import com.rrajath.grove.testing.InMemoryGroveDatabase
import com.rrajath.grove.testing.TestClockService
import com.rrajath.grove.testing.TestGroveApplication
import com.rrajath.grove.testing.TestVaultSeeder
import com.rrajath.grove.testing.support.MainDispatcherRule
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * [ClockService] against a real in-memory index and a fake vault: one running
 * clock across files, the resolve gate for dangling clocks, re-finding a clock
 * after its lines moved or were closed elsewhere, unsaved-buffer bases, and
 * the clock-in / clock-out state switches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, sdk = [34])
class ClockServiceIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val keywords = OrgKeywords.parse("TODO NEXT | DONE")
    private val store = FakeFileStore(
        mapOf(
            "work.org" to "* TODO Alpha\n* TODO Beta\n",
            "home.org" to "* TODO Gamma\nBody.\n",
        ),
    )
    private val vaultFlow = MutableStateFlow<Vault?>(Vault(store, keywords, parseDispatcher = mainDispatcherRule.dispatcher))
    private val db: GroveDatabase = InMemoryGroveDatabase.create(queryCoroutineContext = mainDispatcherRule.dispatcher)
    private val settings = FakeSettingsRepository(GroveSettings())
    private val scope = CoroutineScope(mainDispatcherRule.dispatcher)
    private var now = LocalDateTime.parse("2026-10-30T09:00")
    private val service = TestClockService.create(vaultFlow, db, store, settings, scope, keywords) { now }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun doc(file: String) = OrgParser.parse(store.read(file), keywords)

    private suspend fun line(file: String, title: String) = doc(file).headlines.first { it.title == title }.lineIndex

    private suspend fun TestScope.indexed() {
        TestVaultSeeder.index(db, store, keywords)
        advanceUntilIdle()
    }

    private suspend fun TestScope.clockIn(file: String, title: String, fixes: Map<RunningClock, ClockFix> = emptyMap()): ClockResult {
        val result = service.clockIn(file, line(file, title), fixes = fixes)
        advanceUntilIdle()
        return result
    }

    @Test
    fun `clocking in elsewhere closes the running clock at the same minute`() = runTest {
        indexed()
        clockIn("work.org", "Alpha")
        assertEquals(listOf("Alpha"), service.running.value.map { it.title })

        now = LocalDateTime.parse("2026-10-30T10:15")
        assertTrue(clockIn("home.org", "Gamma") is ClockResult.Done)

        val work = doc("work.org")
        val alpha = work.headlines.first { it.title == "Alpha" }
        assertNull(work.runningClock(alpha))
        assertEquals(75, work.clockedMinutes(alpha))
        val home = doc("home.org")
        assertEquals(LocalDateTime.parse("2026-10-30T10:15"), home.runningClock(home.headlines[0])?.start)
        assertEquals(listOf("Gamma"), service.running.value.map { it.title })
    }

    @Test
    fun `clocking in on the running heading continues it`() = runTest {
        indexed()
        clockIn("work.org", "Alpha")
        now = now.plusMinutes(5)
        assertEquals(ClockResult.Continues, clockIn("work.org", "Alpha"))
        assertEquals(1, doc("work.org").let { d -> d.clocks(d.headlines[0]).size })
    }

    @Test
    fun `a clock open for half a day is resolved before it is closed`() = runTest {
        store.write("work.org", "* TODO Alpha\n:LOGBOOK:\nCLOCK: [2026-10-29 Thu 17:00]\n:END:\n* TODO Beta\n")
        indexed()

        val ask = clockIn("work.org", "Beta") as ClockResult.NeedsResolution
        assertEquals(listOf("Alpha"), ask.clocks.map { it.title })
        // Nothing was written while asking.
        assertNull(doc("work.org").let { d -> d.runningClock(d.headlines[1]) })

        val stop = LocalDateTime.parse("2026-10-29T18:30")
        assertTrue(clockIn("work.org", "Beta", mapOf(ask.clocks[0] to ClockFix.OutAt(stop))) is ClockResult.Done)
        val work = doc("work.org")
        assertEquals(90, work.clockedMinutes(work.headlines[0]))
        assertEquals(now, work.runningClock(work.headlines[1])?.start)
    }

    @Test
    fun `several open clocks are each resolved as asked`() = runTest {
        store.write(
            "work.org",
            "* TODO Alpha\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 08:00]\n:END:\n" +
                "* TODO Beta\n:LOGBOOK:\nCLOCK: [2026-10-30 Fri 08:30]\n:END:\n",
        )
        indexed()

        val ask = clockIn("home.org", "Gamma") as ClockResult.NeedsResolution
        val alpha = ask.clocks.first { it.title == "Alpha" }
        val beta = ask.clocks.first { it.title == "Beta" }
        clockIn("home.org", "Gamma", mapOf(alpha to ClockFix.Cancel, beta to ClockFix.Keep))

        val work = doc("work.org")
        assertTrue(work.clocks(work.headlines[0]).isEmpty())
        assertEquals(LocalDateTime.parse("2026-10-30T08:30"), work.runningClock(work.headlines[1])?.start)
        assertEquals(setOf("Beta", "Gamma"), service.running.value.map { it.title }.toSet())
    }

    @Test
    fun `clocking out finds the clock after lines moved above it`() = runTest {
        indexed()
        clockIn("home.org", "Gamma")
        val clock = service.running.value.single()
        // An external edit the index hasn't seen yet.
        store.write("home.org", "#+TITLE: Home\n\n" + store.read("home.org"))

        now = LocalDateTime.parse("2026-10-30T09:40")
        assertTrue(service.clockOut(clock) is ClockResult.Done)
        val home = doc("home.org")
        assertEquals(40, home.clockedMinutes(home.headlines[0]))
    }

    @Test
    fun `a clock closed elsewhere is not found, and nothing is written`() = runTest {
        indexed()
        clockIn("home.org", "Gamma")
        val clock = service.running.value.single()
        store.write("home.org", "* TODO Gamma\nBody.\n")
        val before = store.read("home.org")

        assertEquals(ClockResult.NotFound, service.clockOut(clock))
        assertEquals(before, store.read("home.org"))
    }

    @Test
    fun `a base document's unsaved edits go to disk with the clock`() = runTest {
        indexed()
        val base = OrgParser.parse("* TODO Gamma\nBody, edited.\n", keywords)
        val result = service.clockIn("home.org", 0, base = base) as ClockResult.Done
        advanceUntilIdle()

        assertTrue(store.read("home.org").contains("Body, edited."))
        // Undo goes back to the edited text, not to what was on disk.
        assertEquals(base.text, result.writes.single().before)
    }

    @Test
    fun `clocking in and out switch states as configured`() = runTest {
        settings.update { it.copy(clockInState = "NEXT", clockOutState = "DONE") }
        indexed()
        clockIn("home.org", "Gamma")
        assertEquals("NEXT", doc("home.org").headlines[0].keyword)

        now = LocalDateTime.parse("2026-10-30T09:20")
        service.clockOut(service.running.value.single())
        advanceUntilIdle()
        val home = doc("home.org")
        val gamma = home.headlines[0]
        assertEquals("DONE", gamma.keyword)
        assertEquals(20, home.clockedMinutes(gamma))
        assertTrue(service.running.value.isEmpty())
    }
}
