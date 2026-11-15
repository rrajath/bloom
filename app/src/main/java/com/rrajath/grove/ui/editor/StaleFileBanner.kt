package com.rrajath.grove.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.screens.ConflictDiffCard
import com.rrajath.grove.ui.screens.ConflictPrimaryButton
import com.rrajath.grove.ui.screens.ConflictSecondaryButton
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * The editors' "changed on disk while editing" banner: the save was refused
 * because the file moved past the revision the editor loaded. Overwrite writes
 * anyway, Reload drops the edit, Compare opens [StaleCompareDialog].
 */
@Composable
internal fun StaleFileBanner(onOverwrite: () -> Unit, onReload: () -> Unit, onCompare: () -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(c.amberSoft)
            .border(1.dp, c.amber, RoundedCornerShape(11.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "File changed on disk while editing",
            fontFamily = PlexSans, fontSize = 12.5.sp, color = c.ink,
            modifier = Modifier.weight(1f),
        )
        BannerAction("Compare", c.accent, onCompare)
        BannerAction("Overwrite", c.red, onOverwrite)
        BannerAction("Reload", c.accent, onReload)
    }
}

@Composable
private fun BannerAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}

/**
 * Full-screen Compare view behind [StaleFileBanner]: the conflict screen's diff
 * and actions, over the file as it is now against the file as this editor's
 * save would write it. Keep mine is Overwrite, Keep file is Reload, Keep both
 * writes the line merge.
 */
@Composable
internal fun StaleCompareDialog(
    fileName: String,
    compare: StaleCompare,
    onKeepMine: () -> Unit,
    onKeepFile: () -> Unit,
    onKeepBoth: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Its own platform window: re-assert the bar icon appearance (see PlanningDatesScreen).
        val dialogView = LocalView.current
        if (!dialogView.isInEditMode) {
            SideEffect {
                val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(window, dialogView)
                controller.isAppearanceLightStatusBars = !c.isDark
                controller.isAppearanceLightNavigationBars = !c.isDark
            }
        }
        Surface(Modifier.fillMaxSize(), color = c.bg) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                GroveTopBar(
                    leading = { IconGlyph("←", onClick = onDismiss) },
                    title = {
                        Column(Modifier.padding(start = 4.dp)) {
                            Text("Compare versions", style = MaterialTheme.typography.titleMedium, color = c.ink)
                            Text(fileName, fontFamily = PlexMono, fontSize = 11.5.sp, color = c.ink2)
                        }
                    },
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        "The file changed somewhere else while you were editing. Pick what to keep.",
                        fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 1.5.em, color = c.ink,
                    )
                    Spacer(Modifier.height(16.dp))
                    ConflictDiffCard(
                        dateLabel = null,
                        current = compare.fileText,
                        copy = compare.mineText,
                        currentLabel = "IN THE FILE",
                        copyLabel = "YOUR EDIT",
                    )
                    Spacer(Modifier.height(18.dp))
                    ConflictPrimaryButton("Keep both (merge under CONFLICT)", onClick = onKeepBoth)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        ConflictSecondaryButton("Keep file", Modifier.weight(1f), onClick = onKeepFile)
                        Spacer(Modifier.width(10.dp))
                        ConflictSecondaryButton("Keep mine", Modifier.weight(1f), onClick = onKeepMine)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * [StaleCompareDialog] for [viewModel]'s open Compare, if any. [onReload]
 * reloads the editor from the file (the screen's own load), after Keep file
 * and after Keep both has written the merge.
 */
@Composable
internal fun StaleCompareHost(state: EditorUiState, viewModel: EditorViewModel, onReload: () -> Unit) {
    val compare = state.staleCompare ?: return
    StaleCompareDialog(
        fileName = state.fileName,
        compare = compare,
        onKeepMine = { viewModel.closeCompare(); viewModel.save(force = true) },
        onKeepFile = { viewModel.closeCompare(); viewModel.dismissStale(); onReload() },
        onKeepBoth = { viewModel.keepBothStale(onDone = onReload) },
        onDismiss = viewModel::closeCompare,
    )
}
