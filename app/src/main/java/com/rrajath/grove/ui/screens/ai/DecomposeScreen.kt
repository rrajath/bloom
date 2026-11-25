// i18n: all user-visible text comes from string resources
package com.rrajath.grove.ui.screens.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.ai.DecomposeStage
import com.rrajath.grove.ui.screens.settings.SectionLabel
import com.rrajath.grove.ui.screens.settings.SettingsPageScaffold
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.asString

/**
 * The four confirmation gates of the AI breakdown wizard. The ViewModel owns
 * all state and the model transcript; this screen is a pure renderer.
 */
@Composable
fun DecomposeScreen(
    state: DecomposeViewModel.UiState,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onStart: () -> Unit,
    onAnswerChange: (Int, String) -> Unit,
    onConfirmAnswers: () -> Unit,
    onSelectApproach: (Int) -> Unit,
    onConfirmApproach: () -> Unit,
    onConfirmDesignBlock: () -> Unit,
    onOrgTextChange: (String) -> Unit,
    onInsert: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = MaterialTheme.grove
    SettingsPageScaffold(
        title = stringResource(R.string.ai_wizard_title),
        onBack = onBack,
    ) {
        when (state.stage) {
            DecomposeStage.TITLE -> {
                Text(
                    stringResource(R.string.ai_wizard_title_label),
                    fontFamily = PlexSans,
                    fontSize = 13.sp,
                    color = c.ink2,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    enabled = !state.busy,
                    placeholder = {
                        Text(stringResource(R.string.ai_wizard_title_hint), fontFamily = PlexSans, fontSize = 12.5.sp)
                    },
                    textStyle = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, color = c.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                WizardAction(
                    stringResource(R.string.ai_wizard_start),
                    !state.busy && state.title.isNotBlank(),
                    onStart,
                )
            }

            DecomposeStage.CLARIFY -> {
                SectionLabel(stringResource(R.string.ai_wizard_clarify_section))
                state.questions.forEachIndexed { index, question ->
                    Text(
                        question,
                        fontFamily = PlexSans,
                        fontSize = 13.sp,
                        color = c.ink,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                    OutlinedTextField(
                        value = state.answers.getOrElse(index) { "" },
                        onValueChange = { onAnswerChange(index, it) },
                        enabled = !state.busy,
                        minLines = 1,
                        placeholder = {
                            Text(
                                stringResource(R.string.ai_wizard_answer_hint),
                                fontFamily = PlexSans,
                                fontSize = 12.sp,
                            )
                        },
                        textStyle = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, color = c.ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(14.dp))
                WizardAction(stringResource(R.string.ai_wizard_confirm_answers), !state.busy, onConfirmAnswers)
            }

            DecomposeStage.COMPARE -> {
                SectionLabel(stringResource(R.string.ai_wizard_compare_section))
                state.approaches.forEachIndexed { index, approach ->
                    val selected = state.selectedApproach == index
                    Column(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) c.accentSoft else c.surface)
                            .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(12.dp))
                            .clickable(enabled = !state.busy) { onSelectApproach(index) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            stringResource(R.string.ai_wizard_approach_label, index + 1) + " · " + approach.title,
                            fontFamily = PlexSans,
                            fontSize = 14.sp,
                            color = c.ink,
                        )
                        if (approach.detail.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                approach.detail,
                                fontFamily = PlexSans,
                                fontSize = 12.5.sp,
                                color = c.ink2,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                WizardAction(
                    stringResource(R.string.ai_wizard_confirm_approach),
                    !state.busy && state.selectedApproach != null,
                    onConfirmApproach,
                )
            }

            DecomposeStage.DESIGN -> {
                SectionLabel(stringResource(R.string.ai_wizard_design_section))
                val total = state.designBlocks.size
                val showing = (state.confirmedBlocks + 1).coerceAtMost(total.coerceAtLeast(1))
                Text(
                    stringResource(R.string.ai_wizard_block_label, showing, total),
                    fontFamily = PlexSans,
                    fontSize = 12.sp,
                    color = c.ink3,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(
                    state.designBlocks.getOrElse(state.confirmedBlocks) { "" },
                    fontFamily = PlexSans,
                    fontSize = 13.5.sp,
                    color = c.ink,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                val last = state.confirmedBlocks + 1 >= total
                WizardAction(
                    if (last) stringResource(R.string.ai_wizard_confirm_last_block)
                    else stringResource(R.string.ai_wizard_confirm_block),
                    !state.busy,
                    onConfirmDesignBlock,
                )
            }

            DecomposeStage.GENERATE -> {
                SectionLabel(stringResource(R.string.ai_wizard_preview_section))
                OutlinedTextField(
                    value = state.orgText,
                    onValueChange = onOrgTextChange,
                    enabled = !state.busy,
                    placeholder = {
                        Text(
                            stringResource(R.string.ai_wizard_preview_hint),
                            fontFamily = PlexMono,
                            fontSize = 12.sp,
                        )
                    },
                    textStyle = TextStyle(fontFamily = PlexMono, fontSize = 12.5.sp, color = c.ink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp)
                        .padding(top = 8.dp),
                )
                Spacer(Modifier.height(14.dp))
                WizardAction(
                    stringResource(R.string.ai_wizard_insert),
                    !state.busy && state.orgText.isNotBlank(),
                    onInsert,
                )
            }

            DecomposeStage.DONE -> {
                WizardAction(stringResource(R.string.ai_wizard_again), !state.busy, onReset)
            }
        }

        val status = state.status
        if (status != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                status.asString(),
                fontFamily = PlexSans,
                fontSize = 13.sp,
                color = if (state.busy) c.ink3 else c.ink2,
            )
        }

        if (!state.busy && state.stage != DecomposeStage.DONE && status != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WizardAction(stringResource(R.string.ai_wizard_retry), true, onRetry)
                WizardAction(stringResource(R.string.ai_open_settings), true, onOpenSettings)
            }
        }

        if (!state.busy && state.stage == DecomposeStage.TITLE && status == null) {
            Spacer(Modifier.height(12.dp))
            WizardAction(stringResource(R.string.ai_open_settings), true, onOpenSettings)
        }
    }
}

@Composable
private fun WizardAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) c.accentSoft else c.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            fontFamily = PlexSans,
            fontSize = 13.sp,
            color = if (enabled) c.accent else c.ink3,
        )
    }
}
