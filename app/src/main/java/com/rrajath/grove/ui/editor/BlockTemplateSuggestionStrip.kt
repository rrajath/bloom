package com.rrajath.grove.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange

/**
 * The `<q` / `<e` / `<s` shorthand at [textState]'s cursor, tracked live. Not gated on
 * any setting: block templates are plain org, unlike the Roam suggestion providers.
 */
@Composable
internal fun rememberBlockTrigger(textState: TextFieldState): State<BlockTrigger?> =
    remember(textState) { derivedStateOf { blockTemplateTriggerAt(textState.text, textState.selection) } }

/** Replaces [trigger]'s shorthand with its block (see [expandBlockTemplate]). */
internal fun TextFieldState.applyBlockTemplate(trigger: BlockTrigger) {
    val ins = expandBlockTemplate(text.toString(), trigger)
    edit {
        replace(ins.start, ins.end, ins.replacement)
        selection = TextRange(ins.cursor)
    }
}

/**
 * One chip ("Insert quote?") in the editor's suggestion slot while a block
 * shorthand sits at the cursor, styled and padded like [AutoLinkSuggestionStrip]'s.
 */
@Composable
internal fun BlockTemplateSuggestionStrip(
    template: BlockTemplate,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(SuggestionStripPadding)) {
        SuggestionChip(
            label = stringResource(template.insertPrompt),
            onClick = onPick,
            modifier = Modifier.testTag("block_template_chip"),
        )
    }
}
