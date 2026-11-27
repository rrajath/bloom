package com.rrajath.grove.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.rrajath.grove.macrobenchmark.BenchmarkVault.awaitNotebooks
import com.rrajath.grove.macrobenchmark.BenchmarkVault.awaitObject
import com.rrajath.grove.macrobenchmark.BenchmarkVault.awaitSelector
import com.rrajath.grove.macrobenchmark.BenchmarkVault.backTo
import com.rrajath.grove.macrobenchmark.BenchmarkVault.withSeededVault
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline profile that ships in the APK, recording every
 * class/method each journey touches so ART can AOT-compile them ahead of first
 * run. Both tests' rules are merged into one profile.
 *
 * Run: `./gradlew :app:generateBaselineProfile` (needs a connected API 33+
 * device or emulator). Output lands in `app/src/release/generated/baselineProfiles/`:
 * commit it, and regenerate before each release (`scripts/release.sh` refuses to tag without it).
 *
 * Dailies has no journey: it is behind the Roam toggle, which only debug
 * builds honour, so release code never reaches it.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Cold start into Notebooks, scroll, open a note's outline, back. The only
     * journey in the startup profile, which also drives the dex layout, so it
     * stays limited to what launch actually needs.
     */
    @Test
    fun startup() = rule.collect(
        packageName = BenchmarkVault.TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait { it.withSeededVault() }
        device.awaitNotebooks()

        device.findObject(By.res(BenchmarkVault.NOTEBOOKS_LIST)).fling(Direction.DOWN)
        device.waitForIdle()

        device.findObject(By.res(BenchmarkVault.NOTEBOOK_ROW)).click()
        device.awaitObject(BenchmarkVault.OUTLINE_LIST, "Outline list")
        device.findObject(By.res(BenchmarkVault.OUTLINE_LIST)).fling(Direction.DOWN)
        device.waitForIdle()

        device.pressBack()
        device.awaitNotebooks()
    }

    /**
     * The everyday screens past launch: Outline bulk select and its structure
     * commands, the whole-file view, Read mode, Search, and Agenda.
     */
    @Test
    fun journeys() = rule.collect(
        packageName = BenchmarkVault.TARGET_PACKAGE,
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait { it.withSeededVault() }
        device.awaitNotebooks()

        outlineJourney()
        searchJourney()
        agendaJourney()
    }

    /** projects.org: bulk-select a heading, move it up and back, whole-file view, Read mode. */
    private fun MacrobenchmarkScope.outlineJourney() {
        device.awaitSelector(By.desc(PROJECTS_ROW), "projects notebook").click()
        device.awaitObject(BenchmarkVault.OUTLINE_LIST, "Outline list")

        // Move up then down leaves projects.org as seeded for the next iteration.
        device.awaitSelector(By.textContains("Backlog"), "Backlog heading").click(LONG_PRESS_MS)
        device.awaitSelector(By.desc("Move up"), "Move up command").click()
        device.waitForIdle()
        device.findObject(By.desc("Move down")).click()
        device.waitForIdle()
        device.pressBack() // clears the selection
        device.waitForIdle()

        device.awaitSelector(By.text("⋮"), "Outline overflow menu").click()
        // The menu is a popup window, which doesn't carry testTagsAsResourceId.
        device.awaitSelector(By.text("View file"), "View file item").click()
        device.awaitObject(BenchmarkVault.READ_FILE_SCROLL, "Whole-file view")
        device.findObject(By.res(BenchmarkVault.READ_FILE_SCROLL)).fling(Direction.DOWN)
        device.waitForIdle()
        device.pressBack()
        device.awaitObject(BenchmarkVault.OUTLINE_LIST, "Outline list")

        device.awaitSelector(By.textContains("Backlog"), "Backlog heading").click()
        device.awaitObject(BenchmarkVault.READ_NOTE_SCROLL, "Read note")
        device.waitForIdle()
        device.pressBack()
        device.awaitObject(BenchmarkVault.OUTLINE_LIST, "Outline list")
        device.pressBack()
        device.awaitNotebooks()
    }

    /** Search from the Notebooks dock, open a result in Read mode, back out. */
    private fun MacrobenchmarkScope.searchJourney() {
        device.awaitSelector(By.desc("Search"), "Search dock button").click()
        device.awaitObject(BenchmarkVault.SEARCH_FIELD, "Search field")
        device.findObject(By.res(BenchmarkVault.SEARCH_FIELD)).text = "release"
        device.awaitObject(BenchmarkVault.SEARCH_RESULT_ROW, "Search result")
        device.findObject(By.res(BenchmarkVault.SEARCH_RESULT_ROW)).click()
        device.awaitObject(BenchmarkVault.READ_NOTE_SCROLL, "Read note")
        device.waitForIdle()
        device.backTo(BenchmarkVault.READ_NOTE_SCROLL, BenchmarkVault.SEARCH_RESULTS_LIST, "Search results")
        // Search spends Back in stages: hide the keyboard (the field refocuses on
        // return from Read), clear the query, then leave.
        repeat(MAX_BACKS_OUT_OF_SEARCH) {
            if (device.wait(Until.hasObject(By.res(BenchmarkVault.NOTEBOOKS_LIST)), 1_500)) return
            device.pressBack()
        }
        device.awaitNotebooks()
    }

    /** Agenda from the drawer. */
    private fun MacrobenchmarkScope.agendaJourney() {
        // The drawer item exists as soon as the open animation starts; a tap while it
        // is still sliding in doesn't navigate. Tap once its bounds settle, and retry.
        for (attempt in 1..MAX_DRAWER_ATTEMPTS) {
            if (!device.hasObject(By.text("Agenda"))) {
                device.awaitSelector(By.text("☰"), "Drawer button").click()
            }
            awaitSettled(By.text("Agenda"), "Agenda drawer item").click()
            if (device.wait(Until.hasObject(By.res(BenchmarkVault.AGENDA_LIST)), DRAWER_NAV_MS)) break
        }
        // waitForIdle can return mid drawer-close/nav transition; a Back sent
        // before Agenda is on screen lands on Notebooks and exits the app.
        device.awaitObject(BenchmarkVault.AGENDA_LIST, "Agenda list")
        device.waitForIdle()
        device.pressBack()
        device.awaitNotebooks()
    }

    /** Wait for [selector], then until its on-screen bounds stop moving (an animation ended). */
    private fun MacrobenchmarkScope.awaitSettled(selector: BySelector, what: String): UiObject2 {
        var last = device.awaitSelector(selector, what).visibleBounds
        repeat(MAX_SETTLE_POLLS) {
            Thread.sleep(SETTLE_POLL_MS)
            val now = device.awaitSelector(selector, what).visibleBounds
            if (now == last) return device.awaitSelector(selector, what)
            last = now
        }
        return device.awaitSelector(selector, what)
    }

    private companion object {
        const val LONG_PRESS_MS = 800L
        const val MAX_BACKS_OUT_OF_SEARCH = 4
        const val MAX_DRAWER_ATTEMPTS = 3
        const val DRAWER_NAV_MS = 5_000L
        const val MAX_SETTLE_POLLS = 20
        const val SETTLE_POLL_MS = 100L

        /** The row reads its `#+TITLE:` ("Projects") once indexed, the file name before. */
        val PROJECTS_ROW: Pattern = Pattern.compile("(?i)projects(\\.org)?")
    }
}
