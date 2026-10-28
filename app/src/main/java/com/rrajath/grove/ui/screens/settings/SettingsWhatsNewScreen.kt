package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rrajath.grove.BuildConfig
import com.rrajath.grove.ui.components.GroveTopBar
import com.rrajath.grove.ui.components.Pill
import com.rrajath.grove.ui.screens.IconGlyph
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.whatsnew.ChangelogVersion
import com.rrajath.grove.org.LocalDates
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings § About → What's New (design/Grove.dc.html lines 2054-2138, the "Release notes"
 * card view). One card per shipped release, newest first: a `PlexMono` version number and
 * date header with an "installed" pill on the running build, then the release's changes
 * grouped into category rows — a fixed-width colour-coded tag (ADDED / CHANGED / FIXED / …)
 * beside a column of item lines. Uncategorised bullets (the format most recent CHANGELOG.md
 * entries use) render as a tag-less column.
 *
 * The prototype's Release notes / Highlights / Changelog mode chips are intentionally left
 * out. Content comes from the bundled CHANGELOG.md asset via
 * [com.rrajath.grove.whatsnew.ChangelogParser.shippedReleases]; opening this screen also
 * marks the current build seen (see GroveNavigation), so it and the launch-time
 * [com.rrajath.grove.ui.screens.WhatsNewDialog] share one "seen" state.
 */
@Composable
fun SettingsWhatsNewScreen(versions: List<ChangelogVersion>, onBack: () -> Unit) {
    val c = MaterialTheme.grove
    Scaffold(
        containerColor = c.bg,
        topBar = {
            GroveTopBar(
                leading = { IconGlyph("←", onClick = onBack) },
                title = {
                    Text(
                        "What's New",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.ink,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            if (versions.isEmpty()) {
                Text(
                    "Release notes will appear here.",
                    fontFamily = PlexSans, fontSize = 13.sp, color = c.ink3,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
                return@Column
            }
            versions.forEach { version ->
                ReleaseCard(version)
                Spacer(Modifier.height(13.dp))
            }
        }
    }
}

@Composable
private fun ReleaseCard(version: ChangelogVersion) {
    val c = MaterialTheme.grove
    val prettyDate = remember(version.date) { version.date?.let(::formatIsoDate) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(15.dp))
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                version.title,
                fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp, color = c.ink,
            )
            if (prettyDate != null) {
                Spacer(Modifier.width(9.dp))
                Text(prettyDate, fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3)
            }
            if (version.versionCode == BuildConfig.VERSION_CODE) {
                Spacer(Modifier.weight(1f))
                Pill(text = "installed", fg = c.green, bg = c.greenSoft)
            }
        }
        version.subsections.forEach { section ->
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                if (section.heading.isNotBlank()) {
                    CategoryTag(section.heading)
                }
                Column(
                    Modifier.weight(1f).padding(top = 1.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    section.items.forEach { item ->
                        Text(
                            item,
                            fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 1.55.em, color = c.ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTag(heading: String) {
    val c = MaterialTheme.grove
    val (fg, bg) = when (heading) {
        "Added" -> c.green to c.greenSoft
        "Changed" -> c.blue to c.blueSoft
        "Fixed" -> c.amber to c.amberSoft
        "Removed", "Security" -> c.red to c.redSoft
        "Deprecated" -> c.amber to c.amberSoft
        else -> c.ink2 to c.surface2
    }
    Box(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            heading.uppercase(Locale.US),
            fontFamily = PlexSans, fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp, letterSpacing = 0.4.sp, color = fg,
            textAlign = TextAlign.Center,
        )
    }
}

private val ISO = DateTimeFormatter.ISO_LOCAL_DATE
private val DISPLAY: DateTimeFormatter get() = LocalDates.formatter("yMMMd", "d MMM yyyy")

/** "2026-09-09" -> "9 Sep 2026"; anything that isn't a plain ISO date is shown verbatim. */
private fun formatIsoDate(raw: String): String =
    runCatching { LocalDate.parse(raw, ISO).format(DISPLAY) }.getOrDefault(raw)
