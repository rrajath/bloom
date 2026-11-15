package com.rrajath.grove.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import com.rrajath.grove.macrobenchmark.BenchmarkVault.awaitNotebooks
import com.rrajath.grove.macrobenchmark.BenchmarkVault.awaitObject
import com.rrajath.grove.macrobenchmark.BenchmarkVault.withSeededVault
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scroll-jank on the two long lists — the Notebooks list and a note's Outline —
 * against a large synthetic vault (60 notebooks, one 500-heading outline).
 *
 * `FrameTimingMetric` reports `frameDurationCpuMs` P50/P90/P99 and
 * `frameOverrunMs` (how far past the frame deadline each frame ran).
 *
 * Run: `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val vaultSize = 60

    @Test
    fun scrollNotebooks() = rule.measureRepeated(
        packageName = BenchmarkVault.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 10,
        // No startupMode: StartupMode.COLD kills the process *after* setupBlock,
        // which threw away the list setupBlock just opened and left the measure
        // block waiting on a dead app. Kill here instead, so every iteration
        // still starts from a fresh process but the measured scroll has a screen.
        setupBlock = {
            killProcess()
            pressHome()
            startActivityAndWait { it.withSeededVault(vaultSize) }
            device.awaitNotebooks()
        },
    ) {
        device.awaitObject(BenchmarkVault.NOTEBOOKS_LIST, "Notebooks list")
        val list = device.findObject(By.res(BenchmarkVault.NOTEBOOKS_LIST))
        list.setGestureMargin(device.displayWidth / 5)
        repeat(3) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        repeat(3) {
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    @Test
    fun scrollOutline() = rule.measureRepeated(
        packageName = BenchmarkVault.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 10,
        // No startupMode: StartupMode.COLD kills the process *after* setupBlock,
        // which threw away the list setupBlock just opened and left the measure
        // block waiting on a dead app. Kill here instead, so every iteration
        // still starts from a fresh process but the measured scroll has a screen.
        setupBlock = {
            killProcess()
            pressHome()
            startActivityAndWait { it.withSeededVault(vaultSize) }
            device.awaitNotebooks()
            // Open the first notebook → its Outline.
            device.findObject(By.res(BenchmarkVault.NOTEBOOK_ROW)).click()
            device.awaitObject(BenchmarkVault.OUTLINE_LIST, "Outline list")
        },
    ) {
        device.awaitObject(BenchmarkVault.OUTLINE_LIST, "Outline list")
        val list = device.findObject(By.res(BenchmarkVault.OUTLINE_LIST))
        list.setGestureMargin(device.displayWidth / 5)
        repeat(3) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        repeat(3) {
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    }
}
