package com.rrajath.grove.ui.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

/**
 * Text a ViewModel (or anything without a Context) hands to the UI, resolved against
 * string resources only when it's shown. This keeps ViewModel messages translatable
 * and makes them follow a language change without being rebuilt.
 *
 * Arguments may themselves be [UiText]; they're resolved first, so a message can embed
 * another translated phrase.
 */
sealed interface UiText {

    /** A `<string>` resource with optional format arguments. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A `<plurals>` resource; [count] picks the form and, by default, is also the first argument. */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Text that isn't ours to translate: a file name, a heading title, an OS error message. */
    data class Raw(val value: String) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
        fun raw(value: String): UiText = Raw(value)
    }
}

/** Resolves against [context]'s resources: for receivers, workers, notifications and Glance. */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *resolveArgs(context))
    is UiText.Plural -> context.resources.getQuantityString(id, count, *resolveArgs(context))
}

/** Resolves in a composable; recomposes with the current configuration's resources. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    LocalResources.current // read so a configuration change re-resolves
    return resolve(LocalContext.current)
}

private fun UiText.resolveArgs(context: Context): Array<Any> {
    val raw = when (this) {
        is UiText.Res -> args
        is UiText.Plural -> args
        is UiText.Raw -> emptyList()
    }
    return raw.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()
}
