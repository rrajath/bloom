package com.rrajath.grove.debug

import android.content.Context
import android.content.Intent
import com.rrajath.grove.BuildConfig
import com.rrajath.grove.vault.TestVaultHook
import java.io.File

/**
 * Test-only activation of the directory-vault hook, driven by extras on
 * `MainActivity`'s launch intent so Maestro (Layer 3) and the `:macrobenchmark`
 * module (Layer 4) can start from a populated vault with no SAF picker and no
 * onboarding.
 *
 * ```
 * adb shell am start -n com.rrajath.grove.debug/com.rrajath.grove.MainActivity \
 *   --ez grove_test_direct_vault true --ez grove_test_seed true
 * ```
 *
 * Every entry point is a no-op unless [BuildConfig.TEST_HOOKS], which is on for
 * the `debug` and `benchmark` build types and off for `release` (R8 then
 * dead-strips this). The `.org` fixtures the small seed reads are only copied
 * into those two variants' assets (`copy{Debug,Benchmark}TestFixtures` in
 * `app/build.gradle.kts`). See internal/test-suite-03-e2e-maestro.md and
 * internal/test-suite-04-performance-macrobenchmark.md.
 */
object DebugTestVault {

    const val EXTRA_DIRECT_VAULT = "grove_test_direct_vault"
    const val EXTRA_SEED = "grove_test_seed"

    /**
     * String extra naming a fixture set to seed instead of the flat `.org`
     * fixtures. `attachments` ([DebugTestAttachments]) seeds notes with their
     * attachment folders.
     */
    const val EXTRA_SEED_SET = "grove_test_seed_set"

    /** Boolean extra: wipe the test vault, so the next flow starts clean. */
    const val EXTRA_CLEANUP = "grove_test_cleanup"

    /**
     * Int extra: when > 0, seed a large *synthetic* vault instead of the bundled
     * fixtures — [size] small notebooks plus one [LARGE_OUTLINE_HEADINGS]-heading
     * outline, for the scroll benchmarks.
     */
    const val EXTRA_VAULT_SIZE = "grove_test_vault_size"

    private const val LARGE_OUTLINE_HEADINGS = 500

    /** Directory name under the app's external files dir used as the test vault root. */
    private const val VAULT_DIR = "testvault"

    /**
     * Non-`.org` marker file holding a signature of whatever seed last populated
     * [VAULT_DIR]. The scroll benchmarks launch COLD × 10 iterations, so
     * `applyFromLaunchIntent` runs ~10 times per test; without this guard each
     * run would `wipe` + rewrite ~560 files, bumping every mtime and forcing the
     * app to fully re-index the vault before the Notebooks list appears, which
     * blew past the launch timeout on a slow emulator. When the signature
     * already matches, seeding is a no-op and only the first iteration pays the
     * write + index cost.
     *
     * The signature covers the seed *shape* (kind + counts), not file contents,
     * so after changing the generated `.org` bodies below, delete the vault dir
     * (or bump a count) to force a reseed.
     */
    private const val SEED_MARKER = ".grove-seed"

    /** Called from `MainActivity.onCreate` before content is set. */
    fun applyFromLaunchIntent(context: Context, intent: Intent?) {
        if (!BuildConfig.TEST_HOOKS || intent == null) return
        if (!intent.getBooleanExtra(EXTRA_DIRECT_VAULT, false)) return

        val root = File(context.getExternalFilesDir(null), VAULT_DIR).apply { mkdirs() }
        val size = intent.getIntExtra(EXTRA_VAULT_SIZE, 0)
        when {
            intent.getBooleanExtra(EXTRA_CLEANUP, false) -> DebugTestAttachments.wipe(root)
            size > 0 -> seedLarge(root, size)
            intent.getStringExtra(EXTRA_SEED_SET) == DebugTestAttachments.SET_NAME -> DebugTestAttachments.seedVault(root)
            intent.getBooleanExtra(EXTRA_SEED, false) -> seed(context, root)
        }
        TestVaultHook.useDirectory(root)
    }

    /** Wipe [root] and rewrite it from the debug/benchmark APK's bundled `.org` fixtures. */
    private fun seed(context: Context, root: File) {
        val names = context.assets.list("fixtures").orEmpty().filter { it.endsWith(".org") }
        val signature = "fixtures:" + names.sorted().joinToString(",")
        if (isSeeded(root, signature)) return
        wipe(root)
        names.forEach { name ->
            context.assets.open("fixtures/$name").use { input ->
                File(root, name).outputStream().use { input.copyTo(it) }
            }
        }
        markSeeded(root, signature)
    }

    /**
     * Wipe [root] and fill it with generated content sized for the scroll
     * benchmarks: [notebooks] short files plus one long single-file outline.
     */
    private fun seedLarge(root: File, notebooks: Int) {
        val signature = "large:$notebooks:$LARGE_OUTLINE_HEADINGS"
        if (isSeeded(root, signature)) return
        wipe(root)
        File(root, "big-outline.org").writeText(
            buildString {
                append("#+TITLE: Big Outline\n\n")
                repeat(LARGE_OUTLINE_HEADINGS) { i ->
                    append("* Heading ${i + 1}\n")
                    append("  Body line for heading ${i + 1}.\n")
                }
            },
        )
        repeat(notebooks) { i ->
            File(root, "notebook-%03d.org".format(i + 1)).writeText(
                "#+TITLE: Notebook ${i + 1}\n\n* Item A\n  Note body.\n* Item B\n",
            )
        }
        markSeeded(root, signature)
    }

    /** True when [root] was last seeded with exactly [signature] (see [SEED_MARKER]). */
    private fun isSeeded(root: File, signature: String): Boolean =
        runCatching { File(root, SEED_MARKER).takeIf { it.isFile }?.readText() }.getOrNull() == signature

    private fun markSeeded(root: File, signature: String) {
        runCatching { File(root, SEED_MARKER).writeText(signature) }
    }

    private fun wipe(root: File) {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }
}
