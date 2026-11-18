package com.rrajath.grove.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp

/** The property key being typed at [textState]'s cursor ([propertyKeyTriggerAt]), tracked live. */
@Composable
internal fun rememberPropertyKeyTrigger(textState: TextFieldState): State<PropertyKeyTrigger?> =
    remember(textState) { derivedStateOf { propertyKeyTriggerAt(textState.text, textState.selection) } }

/**
 * The chips for [trigger] ([rankPropertyKeys]). The open buffer's own drawer keys
 * are gathered once per typed key (when [trigger] first appears on a line), not on
 * every keystroke; filtering the small key list per keystroke is trivial.
 */
@Composable
internal fun rememberPropertyKeySuggestions(
    textState: TextFieldState,
    trigger: PropertyKeyTrigger?,
    vaultCounts: Map<String, Int>,
): List<String> {
    val anchor = trigger?.range?.start
    val bufferKeys = remember(textState, anchor) {
        if (anchor == null) emptySet() else propertyKeysIn(textState.text)
    }
    return remember(trigger, vaultCounts, bufferKeys) {
        if (trigger == null) emptyList() else rankPropertyKeys(vaultCounts, BUILT_IN_PROPERTY_KEYS + bufferKeys, trigger)
    }
}

/** Replaces [trigger]'s typed `:prefix` with `:KEY: `, caret after the space, ready for the value. */
internal fun TextFieldState.applyPropertyKey(trigger: PropertyKeyTrigger, key: String) {
    val start = trigger.range.min.coerceIn(0, text.length)
    val end = trigger.range.max.coerceIn(start, text.length)
    val insertion = formatPropertyKeyInsertion(key)
    edit {
        replace(start, end, insertion)
        selection = TextRange(start + insertion.length)
    }
}

/**
 * Property-key chips (`:CUSTOM_ID:`) in the editor's suggestion slot while a key
 * is typed in a `:PROPERTIES:` drawer; text-only, styled like the block-template
 * chip. Tapping one inserts it and keeps the keyboard up.
 */
@Composable
internal fun PropertyKeySuggestionStrip(
    keys: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier.fadingTrailingEdge(SuggestionFadeWidth).testTag("property_key_strip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = SuggestionStripPadding,
    ) {
        items(keys, key = { it }) { key ->
            SuggestionChip(
                label = ":$key:",
                onClick = { onPick(key) },
                modifier = Modifier.testTag("property_key_chip_$key"),
            )
        }
    }
}
