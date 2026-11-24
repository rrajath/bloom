package com.rrajath.grove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rrajath.grove.R
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.ScrollJumpButtons
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.util.backArrowGlyph
import com.rrajath.grove.ui.vault.SyncLogViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// i18n: numeric log timestamp, same in every language
private val LOG_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

/** Sync log (Settings → Sync → View Log, PRD §13). */
@Composable
fun SyncLogScreen(
    onBack: () -> Unit,
    viewModel: SyncLogViewModel = viewModel(factory = SyncLogViewModel.Factory),
) {
    val c = MaterialTheme.grove
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                leading = { IconGlyph(backArrowGlyph(), onClick = onBack) },
                title = {
                    Text(
                        stringResource(R.string.sync_log_title),
                        style = MaterialTheme.typography.titleLarge, color = c.ink,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
            )
        },
    ) { padding ->
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                Column(Modifier.padding(vertical = 5.dp)) {
                    Text(
                        Instant.ofEpochMilli(entry.timestamp)
                            .atZone(ZoneId.systemDefault())
                            .format(LOG_TIME),
                        fontFamily = PlexMono, fontSize = 10.5.sp, color = c.ink3,
                    )
                    Text(
                        entry.message,
                        fontFamily = PlexMono, fontSize = 12.5.sp, color = c.ink,
                    )
                }
            }
            if (entries.size < total) {
                item(key = "load-more") {
                    Text(
                        pluralStringResource(R.plurals.sync_log_load_more, total - entries.size, total - entries.size),
                        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp, color = c.accent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { viewModel.loadMore() }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
        ScrollJumpButtons(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
        }
    }
}
