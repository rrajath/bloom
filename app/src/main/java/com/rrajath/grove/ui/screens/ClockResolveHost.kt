package com.rrajath.grove.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.ui.components.ClockResolveDialog
import com.rrajath.grove.ui.vault.DocumentViewModel
import com.rrajath.grove.ui.vault.headlineAtLine
import java.time.LocalDateTime

/** Shows [DocumentViewModel.clockResolve]'s dialog over Read mode or the Outline, for a heading of [doc]. */
@Composable
internal fun ClockResolveHost(viewModel: DocumentViewModel, doc: OrgDocument?) {
    val resolve by viewModel.clockResolve.collectAsStateWithLifecycle()
    val r = resolve ?: return
    val target = doc?.headlineAtLine(r.lineIndex) ?: return
    val now = remember(r) { LocalDateTime.now().withSecond(0).withNano(0) }
    ClockResolveDialog(
        clocks = r.clocks,
        now = now,
        onResolve = { fixes -> viewModel.clockIn(target, fixes) },
        onDismiss = viewModel::dismissClockResolve,
    )
}
