package com.rrajath.grove.testing.support

import com.rrajath.grove.AppDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` (what `viewModelScope` uses) for a single
 * [StandardTestDispatcher] for the duration of a test, so `viewModelScope`
 * coroutines only run when the test advances the scheduler
 * (`advanceUntilIdle()` / Turbine's `awaitItem()` inside `runTest`).
 *
 * [appDispatchers] points main/io/default at that same dispatcher, so a VM built
 * with it is fully deterministic. Pass it to the VM constructor under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    val appDispatchers = AppDispatchers(main = dispatcher, io = dispatcher, default = dispatcher)

    override fun starting(description: Description) {
        swapMain { Dispatchers.setMain(dispatcher) }
    }

    override fun finished(description: Description) {
        swapMain { Dispatchers.resetMain() }
    }

    /**
     * DataStore resumes `viewModelScope` coroutines (often a finished test's) from its
     * own IO thread, which reads `Dispatchers.Main`. When that read overlaps a
     * set/reset, kotlinx-coroutines-test throws "Dispatchers.Main is used concurrently
     * with setting it" from the swap, possibly on the *next* swap. A failed `setMain`
     * in [starting] leaves the real Main in place while the test body still runs, so
     * the test hangs or fails for no reason of its own. The collision is transient and
     * retrying is safe (setting the same value twice is a no-op), so retry it.
     */
    private fun swapMain(swap: () -> Unit) {
        repeat(MAX_SWAP_ATTEMPTS - 1) {
            try {
                return swap()
            } catch (e: IllegalStateException) {
                if (e.message?.contains("concurrently") != true) throw e
                Thread.sleep(SWAP_RETRY_DELAY_MS)
            }
        }
        swap()
    }

    private companion object {
        const val MAX_SWAP_ATTEMPTS = 20
        const val SWAP_RETRY_DELAY_MS = 5L
    }
}
