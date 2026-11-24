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

