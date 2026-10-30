package com.rrajath.grove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import com.rrajath.grove.ui.components.GroveDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.data.FavoriteNote
import com.rrajath.grove.search.SavedSearch
import com.rrajath.grove.ui.components.BrandMark
import com.rrajath.grove.ui.components.agendaIcon
import com.rrajath.grove.ui.components.dailiesIcon
import com.rrajath.grove.ui.components.favoriteIcon
import com.rrajath.grove.ui.components.notebookIcon
import com.rrajath.grove.ui.components.savedSearchIcon
import com.rrajath.grove.ui.components.searchIcon
import com.rrajath.grove.ui.components.settingsIcon
import com.rrajath.grove.ui.nav.Routes
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.GroveLightColors
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.NoteRef

/** Navigation drawer per design spec §3. */
@Composable
fun GroveDrawerContent(
    currentRoute: String?,
    vaultPath: String,
    savedSearches: List<SavedSearch>,
    favorites: List<FavoriteNote> = emptyList(),
    /** When false, the header logo stays the default light mark regardless of the active theme. */
    logoFollowsTheme: Boolean = true,
    /** Settings § Roam Features master switch: the Dailies row only shows when this is on. */
    roamFeaturesEnabled: Boolean = false,
    /** Today's date, short form ("Wed 23"), shown as the Dailies row's trailing label. */
    todayShortLabel: String = "",
    onNavigate: (String) -> Unit,
    onDeleteSavedSearch: (SavedSearch) -> Unit,
    onRenameSavedSearch: (String, String) -> Unit = { _, _ -> },
    onMoveSavedSearch: (String, Int) -> Unit = { _, _ -> },
    onDeleteFavorite: (FavoriteNote) -> Unit = {},
    onRenameFavorite: (FavoriteNote, String) -> Unit = { _, _ -> },
    onMoveFavorite: (FavoriteNote, Int) -> Unit = { _, _ -> },
) {
    val c = MaterialTheme.grove
    var searchMenuTarget by remember { mutableStateOf<SavedSearch?>(null) }
    var renameSearchTarget by remember { mutableStateOf<SavedSearch?>(null) }
    var favMenuTarget by remember { mutableStateOf<FavoriteNote?>(null) }
    var renameFavoriteTarget by remember { mutableStateOf<FavoriteNote?>(null) }

    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        // Header (pinned)
        Column(Modifier.padding(22.dp)) {
            if (logoFollowsTheme) {
                BrandMark(tileSize = 42.dp)
            } else {
                BrandMark(
                    tileSize = 42.dp,
                    tileColor = GroveLightColors.accentSoft,
                    barColor = GroveLightColors.accent,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.app_name), fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = c.ink)
            Text(vaultPath, fontFamily = PlexMono, fontSize = 12.sp, color = c.ink2)
        }
        HorizontalDivider(color = c.line)

        // Nav items scroll when they overflow the drawer height.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            DrawerItem(icon = searchIcon(), label = "Search", active = false) { onNavigate(Routes.search()) }
            DrawerItem(icon = notebookIcon(), label = "Notebooks", active = currentRoute == Routes.NOTEBOOKS) { onNavigate(Routes.NOTEBOOKS) }

            SectionLabel("SEARCHES")
            savedSearches.forEachIndexed { index, search ->
                Box {
                    DrawerItem(
                        icon = savedSearchIcon(), label = search.name, active = false,
                        onLongClick = { searchMenuTarget = search },
                    ) { onNavigate(Routes.search(search.query)) }
                    DrawerActionMenu(
                        expanded = searchMenuTarget?.id == search.id,
                        onDismissRequest = { searchMenuTarget = null },
                        canMoveUp = index > 0,
                        canMoveDown = index < savedSearches.lastIndex,
                        onMoveUp = { onMoveSavedSearch(search.id, -1) },
                        onMoveDown = { onMoveSavedSearch(search.id, 1) },
                        onRename = { renameSearchTarget = search },
                        onDelete = { onDeleteSavedSearch(search) },
                    )
                }
            }

            if (favorites.isNotEmpty()) {
                SectionLabel("FAVORITES")
                favorites.forEachIndexed { index, fav ->
                    Box {
                        DrawerItem(
                            icon = favoriteIcon(), label = fav.title, active = false,
                            onLongClick = { favMenuTarget = fav },
                        ) { onNavigate(Routes.note(NoteRef(fav.fileName, fav.lineIndex, fav.customId).encode())) }
                        DrawerActionMenu(
                            expanded = favMenuTarget == fav,
                            onDismissRequest = { favMenuTarget = null },
                            canMoveUp = index > 0,
                            canMoveDown = index < favorites.lastIndex,
                            onMoveUp = { onMoveFavorite(fav, -1) },
                            onMoveDown = { onMoveFavorite(fav, 1) },
                            onRename = { renameFavoriteTarget = fav },
                            onDelete = { onDeleteFavorite(fav) },
                        )
                    }
                }
            }

            HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
            if (roamFeaturesEnabled) {
                DailiesDrawerRow(todayShortLabel = todayShortLabel) { onNavigate(Routes.dailies()) }
            }
            DrawerItem(icon = agendaIcon(), label = "Agenda", active = false) { onNavigate(Routes.AGENDA) }
            DrawerItem(
                icon = settingsIcon(),
                label = "Settings",
                active = false,
                badge = { NewDot(NewAnchors.DRAWER_SETTINGS) },
            ) { onNavigate(Routes.SETTINGS) }
            Spacer(Modifier.height(8.dp))
        }
    }

    renameSearchTarget?.let { target ->
        RenameDialog(
            title = "Rename search",
            initialName = target.name,
            onConfirm = { onRenameSavedSearch(target.id, it); renameSearchTarget = null },
            onDismiss = { renameSearchTarget = null },
        )
    }

    renameFavoriteTarget?.let { target ->
        RenameDialog(
            title = "Rename favorite",
            initialName = target.title,
            onConfirm = { onRenameFavorite(target, it); renameFavoriteTarget = null },
            onDismiss = { renameFavoriteTarget = null },
        )
    }
}

/** Long-press action menu shared by the drawer's Saved Searches and Favorites rows. */
@Composable
private fun DrawerActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.grove
    // Inset to match DrawerItem's 12dp horizontal padding, so the menu isn't flush with the screen edge.
    GroveDropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, offset = DpOffset(12.dp, 0.dp)) {
        DropdownMenuItem(
            text = { Text("Move up", fontFamily = PlexSans, color = if (canMoveUp) c.ink else c.ink3) },
            enabled = canMoveUp,
            onClick = { onDismissRequest(); onMoveUp() },
        )
        DropdownMenuItem(
            text = { Text("Move down", fontFamily = PlexSans, color = if (canMoveDown) c.ink else c.ink3) },
            enabled = canMoveDown,
            onClick = { onDismissRequest(); onMoveDown() },
        )
        DropdownMenuItem(
            text = { Text("Rename", fontFamily = PlexSans, color = c.ink) },
            onClick = { onDismissRequest(); onRename() },
        )
        DropdownMenuItem(
            text = { Text("Delete", fontFamily = PlexSans, color = c.red) },
            onClick = { onDismissRequest(); onDelete() },
        )
    }
}

@Composable
private fun RenameDialog(title: String, initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.grove
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, color = c.ink) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Name", fontFamily = PlexSans, color = c.ink3) },
                textStyle = TextStyle(fontFamily = PlexSans, color = c.ink),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Rename", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink2) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = MaterialTheme.grove.ink3,
        modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * The Dailies drawer row: like [DrawerItem] but with a trailing short-date label
 * and a permanent accent inset border (1c mockup) rather than the active-route
 * filled background every other row uses.
 */
@Composable
private fun DailiesDrawerRow(todayShortLabel: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp)) {
            androidx.compose.material3.Icon(
                dailiesIcon(),
                contentDescription = null,
                tint = c.ink2,
                modifier = Modifier.size(24.dp),
            )
        }
        Text("Dailies", fontFamily = PlexSans, fontSize = 14.5.sp, color = c.ink, modifier = Modifier.weight(1f))
        if (todayShortLabel.isNotBlank()) {
            Text(todayShortLabel, fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    glyph: String? = null,
    label: String,
    active: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) c.accentSoft else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp)) {
            if (icon != null) {
                androidx.compose.material3.Icon(
                    icon,
                    contentDescription = null,
                    tint = if (active) c.accent else c.ink2,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Text(glyph.orEmpty(), fontFamily = PlexMono, fontSize = 18.sp, color = if (active) c.accent else c.ink2)
            }
        }
        Text(
            label,
            fontFamily = PlexSans,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 14.5.sp,
            color = if (active) c.accent else c.ink,
        )
        if (badge != null) {
            Box(Modifier.padding(start = 8.dp)) { badge() }
        }
    }
}
