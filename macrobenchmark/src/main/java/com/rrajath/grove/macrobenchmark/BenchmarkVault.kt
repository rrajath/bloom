package com.rrajath.grove.macrobenchmark

import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Shared setup for every benchmark in this module: which package to measure and
 * how to launch :app straight into a populated Notebooks screen with no SAF
 * folder picker and no onboarding.
 *
 * The `benchmark` build type of :app carries `BuildConfig.TEST_HOOKS = true`, so
 * `MainActivity` honours these launch-intent extras (see
 * `com.rrajath.grove.debug.DebugTestVault` in the app module):
 *
 *  - `grove_test_direct_vault` — point the vault at an app-owned directory
 *  - `grove_test_seed`         — (re)seed it from the bundled `.org` fixtures
 *  - `grove_test_vault_size`   — instead of the fixtures, generate N synthetic
 *                                notebooks plus one large outline, for scroll tests
 */
object BenchmarkVault {

    /** :app's `benchmark` variant installs under the release applicationId. */
    const val TARGET_PACKAGE = "com.rrajath.grove"

    const val EXTRA_DIRECT_VAULT = "grove_test_direct_vault"
    const val EXTRA_SEED = "grove_test_seed"
    const val EXTRA_VAULT_SIZE = "grove_test_vault_size"

    /** testTag → resource-id (MainActivity sets testTagsAsResourceId = true). */
    const val NOTEBOOKS_LIST = "notebooks_list"
    const val NOTEBOOK_ROW = "notebook_row"
    const val OUTLINE_LIST = "outline_list"
    const val READ_FILE_SCROLL = "read_file_scroll"
    const val READ_NOTE_SCROLL = "read_note_scroll"
    const val SEARCH_FIELD = "search_field"
    const val SEARCH_RESULTS_LIST = "search_results_list"
    const val SEARCH_RESULT_ROW = "search_result_row"

    /**
     * Generous: the direct-directory test vault is seeded and fully indexed into
     * Room before the Notebooks list renders, and the scroll benchmarks' large
     * vault (~560 files) takes a while to index on CI's shared emulator the first
     * time. Seeding is idempotent (see `debug.DebugTestVault`), so only the first
     * COLD iteration actually waits this long; the rest resolve near-instantly.
     */
    const val LAUNCH_TIMEOUT_MS = 40_000L

    /**
     * Adds the extras that make MainActivity seed and open a directory vault.
     * Pass [vaultSize] > 0 for the large synthetic vault (scroll benchmarks);
     * 0 uses the small canonical fixture set (startup benchmark).
     */
    fun Intent.withSeededVault(vaultSize: Int = 0): Intent = apply {
        putExtra(EXTRA_DIRECT_VAULT, true)
        if (vaultSize > 0) putExtra(EXTRA_VAULT_SIZE, vaultSize) else putExtra(EXTRA_SEED, true)
    }

    /** Block until the Notebooks list is on screen; fail loudly if it never is. */
    fun UiDevice.awaitNotebooks() {
        check(wait(Until.hasObject(By.res(NOTEBOOKS_LIST)), LAUNCH_TIMEOUT_MS)) {
            "Notebooks list ($NOTEBOOKS_LIST) never appeared within ${LAUNCH_TIMEOUT_MS}ms " +
                "— the seeded vault likely failed to index."
        }
    }

    /** Block until [res] is on screen; fail loudly with [what] if it never is. */
    fun UiDevice.awaitObject(res: String, what: String) {
        check(wait(Until.hasObject(By.res(res)), LAUNCH_TIMEOUT_MS)) {
            "$what ($res) never appeared within ${LAUNCH_TIMEOUT_MS}ms."
        }
    }

    /** Wait for [selector] and return the match; fail loudly with [what] if it never shows. */
    fun UiDevice.awaitSelector(selector: BySelector, what: String): UiObject2 =
        checkNotNull(wait(Until.findObject(selector), LAUNCH_TIMEOUT_MS)) {
            "$what never appeared within ${LAUNCH_TIMEOUT_MS}ms."
        }
}
