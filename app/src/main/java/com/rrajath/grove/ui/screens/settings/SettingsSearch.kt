package com.rrajath.grove.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.search.highlightMatches
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * The settings matching [query]: each spec's label and description, plus its page's
 * title (so "widget" lists every Widget setting). Debug-only pages stay out of release
 * builds, the same gate the hub applies to their rows.
 */
@Composable
internal fun rememberSettingsSearchResults(query: String): List<SettingSpec> {
    val res = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    return remember(query, configuration) {
        matchSettingSpecs(query, SettingSpecs.searchable) { spec ->
            val description = listOfNotNull(spec.descriptionString(res), res.getString(spec.page.title)).joinToString("\n")
            spec.labelString(res) to description
        }
    }
}

/**
 * Settings search results, replacing the hub's page list while a query is typed: one
 * card of rows, each captioned with its page. Tapping a row opens the page and reveals
 * the setting; an [InlineControl] in [inlineControls] changes the value in place instead.
 */
@Composable
internal fun SettingsSearchResults(
    query: String,
    results: List<SettingSpec>,
    settings: GroveSettings,
    inlineControls: Map<String, InlineControl>,
    onOpen: (SettingSpec) -> Unit,
) {
    val c = MaterialTheme.grove
    if (results.isEmpty()) {
        Text(
            stringResource(R.string.settings_search_no_results, query.trim()),
            fontFamily = PlexSans, fontSize = 13.5.sp, color = c.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
        )
        return
    }
    SettingsGroup {
        results.forEachIndexed { i, spec ->
            if (i > 0) RowDivider()
            // Keyed so a row's switch state follows its setting as the result list changes.
            key(spec.id) {
                SettingsSearchResultRow(
                    spec = spec,
                    query = query,
                    control = inlineControls[spec.id].takeIf { spec.inline },
                    settings = settings,
                    onOpen = { onOpen(spec) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSearchResultRow(
    spec: SettingSpec,
    query: String,
    control: InlineControl?,
    settings: GroveSettings,
    onOpen: () -> Unit,
) {
    val c = MaterialTheme.grove
    // The same whole-query, case-insensitive match matchSettingSpecs filters on.
    val terms = listOf(query.trim())
    val pageTitle = stringResource(spec.page.title)
    val label = spec.labelText()
    val description = spec.descriptionText()
    val toggle = (control as? InlineControl.Toggle)?.let { t ->
        rememberSettingsSwitchState(t.checked(settings), t.onToggle)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("settings_search_result:${spec.id}")
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                remember(pageTitle, terms, c) { highlightMatches(pageTitle, terms, c) },
                fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                fontSize = 11.sp, color = c.ink3,
                modifier = Modifier.padding(bottom = 1.dp),
            )
            Text(
                remember(label, terms, c) { highlightMatches(label, terms, c) },
                fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp, color = c.ink,
            )
            description?.let {
                Text(
                    remember(it, terms, c) { highlightMatches(it, terms, c) },
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink2,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (control is InlineControl.Choice) {
                SegmentedControl(
                    options = control.labels(),
                    selectedIndex = control.selectedIndex(settings),
                    onSelect = control.onSelect,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
        when {
            toggle != null -> {
                Spacer(Modifier.width(12.dp))
                SettingsSwitch(toggle)
            }
            control == null -> {
                Spacer(Modifier.width(12.dp))
                Text("›", fontFamily = PlexMono, fontSize = 14.sp, color = c.ink2)
            }
        }
    }
}
