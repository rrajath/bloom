package com.rrajath.grove.ui.util

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource

/**
 * "3 headings": a counted noun from a `<plurals>` resource (`R.plurals.count_*` in
 * `strings_common.xml`), with the count as its only argument. For a bare count label
 * only; a count inside a sentence ("Refiled 3 notes to inbox.org") needs its own
 * whole-sentence `<plurals>` so translators can inflect and reorder it. Outside
 * Compose, use `UiText.plural(R.plurals.count_*, n)` or `resources.getQuantityString`.
 */
@Composable
@ReadOnlyComposable
fun countLabel(@PluralsRes id: Int, count: Int): String = pluralStringResource(id, count, count)

/**
 * "{count} {singular}" for a count of 1, "{count} {plural}" otherwise. [plural]
 * defaults to the singular with an "s" appended; pass it explicitly for
 * irregular words.
 */
// i18n-begin: compiler message for developers
@Deprecated(
    "English-only plural rule. Use countLabel(R.plurals.count_*, n) for a bare count, " +
        "or a whole-sentence <plurals> when the count is part of a sentence.",
)
// i18n-end
fun pluralCount(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"
