// i18n: all user-visible text comes from string resources
package com.rrajath.grove.ui.screens.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.ai.InboxAppender
import com.rrajath.grove.R
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * State for the one-shot AI capture entry point, hosted at the page level so a
 * draft survives rotation while the sheet is only mounted while open.
 */
class AiCaptureController internal constructor(
    val viewModel: QuickCaptureViewModel,
    val ui: QuickCaptureViewModel.UiState,
    val open: Boolean,
    val setOpen: (Boolean) -> Unit,
)

/**
 * Create and remember an [AiCaptureController] for the current page. Pass an
 * existing [injected] view model when the host already owns one (e.g. the list
 * screen keeps it as a parameter for tests); otherwise a page-scoped one is
 * created.
 */
@Composable
fun rememberAiCapture(injected: QuickCaptureViewModel? = null): AiCaptureController {
    val created: QuickCaptureViewModel = viewModel(factory = QuickCaptureViewModel.Factory)
    val vm = injected ?: created
    val ui by vm.ui.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val setOpen = remember { { value: Boolean -> open = value } }
    return remember(vm, ui, open, setOpen) { AiCaptureController(vm, ui, open, setOpen) }
}

/**
 * Standalone entry used by the capture picker's AI row. The picker route pops
 * itself before navigating here, so closing this sheet returns to the list.
 */
@Composable
fun AiCaptureSheetRoute(
    onClosed: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val controller = rememberAiCapture()
    LaunchedEffect(Unit) { controller.setOpen(true) }
    AiCaptureSheet(
        controller = controller,
        targetFile = InboxAppender.TARGET_FILE,
        onOpenSettings = onOpenSettings,
        onClosed = onClosed,
    )
}

/** The sparkle action placed in a screen's [com.rrajath.grove.ui.components.GroveTopBar] actions. */
@Composable
fun AiCaptureAction(controller: AiCaptureController) {
    IconGlyph(
        Icons.Default.AutoAwesome,
        contentDescription = stringResource(R.string.ai_quick_open),
        onClick = { controller.setOpen(true) },
    )
}

/**
 * The bottom sheet hosting the capture composer. Mount only while
 * [AiCaptureController.open] is true.
 *
 * A successful insert does not close the sheet: the draft is cleared and the
 * status line confirms the write, so a run of TODOs is type → generate →
 * insert → type again. The sheet's drag handle (or a swipe / tap outside)
 * closes it, and an unfinished draft stays in the page-scoped view model for
 * the next open.
 *
 * @param targetFile file to append to — inbox.org on the list screen, the open
 *   file on a note/file screen.
 * @param beforeWrite flush an editor's unsaved buffer under its save lock; when
 *   it returns false the insert is aborted so a stale buffer cannot overwrite
 *   the appended block.
 * @param afterWrite re-sync an editor/document after the disk write (e.g. reload
 *   a whole-file buffer so it picks up the appended block).
 * @param onClosed called when the sheet is dismissed; route hosts use it to pop
 *   themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCaptureSheet(
    controller: AiCaptureController,
    targetFile: String,
    onOpenSettings: () -> Unit,
    beforeWrite: suspend () -> Boolean = { true },
    afterWrite: (String) -> Unit = {},
    onClosed: () -> Unit = {},
) {
    if (!controller.open) return
    val c = MaterialTheme.grove
    val inserted = controller.ui.inserted

    LaunchedEffect(inserted) {
        if (inserted) controller.viewModel.resetAfterInsert()
    }

    ModalBottomSheet(
        onDismissRequest = {
            controller.setOpen(false)
            onClosed()
        },
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 26.dp)) {
            Text(
                stringResource(R.string.ai_quick_sheet_title),
                fontFamily = PlexSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = c.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.ai_quick_sheet_subtitle),
                fontFamily = PlexSans,
                fontSize = 12.sp,
                color = c.ink2,
            )
            Spacer(Modifier.height(12.dp))
            QuickCaptureBar(
                state = controller.ui,
                onTextChange = controller.viewModel::onTextChange,
                onGranularityChange = controller.viewModel::onGranularityChange,
                onGenerate = controller.viewModel::generate,
                onOrgTextChange = controller.viewModel::onOrgTextChange,
                onInsert = { controller.viewModel.insert(targetFile, beforeWrite, afterWrite) },
                onClear = controller.viewModel::clear,
                onOpenSettings = onOpenSettings,
                embedded = true,
            )
        }
    }
}
