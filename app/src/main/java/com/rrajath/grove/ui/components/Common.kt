package com.rrajath.grove.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.settings.ThemePreference
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove

/**
 * Small rounded pill badge ("Modified", "1 conflict", "Recommended"…). Filled
 * by default (solid [bg]); pass [outline] = true for a transparent-fill,
 * [fg]-bordered chip instead: used to tell tag pills apart from filled
 * TODO-state pills at a glance.
 */
@Composable
fun Pill(
    text: String,
    fg: Color,
    bg: Color,
    modifier: Modifier = Modifier,
    outline: Boolean = false,
    onClick: (() -> Unit)? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .then(if (outline) Modifier.border(1.dp, fg, RoundedCornerShape(20.dp)) else Modifier.background(bg))
            .then(clickMod)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = fg,
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            maxLines = maxLines,
            overflow = if (maxLines < Int.MAX_VALUE) androidx.compose.ui.text.style.TextOverflow.Clip else androidx.compose.ui.text.style.TextOverflow.Visible,
        )
    }
}

/** The outline star icon (design/icons/star.svg) used for favorites, tinted per call site. */
@Composable
fun favoriteIcon(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_star)

/** Solid variant of [favoriteIcon], used where the star must read as filled rather than outlined. */
@Composable
fun favoriteIconFilled(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_star_filled)

/** The magnifier icon (design/icons/search_48px.xml), tinted per call site. */
@Composable
fun searchIcon(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_search)

/** Settings glyph (design/icons/settings.svg), used in the drawer. */
@Composable
fun settingsIcon(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_settings)

/** The Agenda icon (design/icons/calendar_view_day_48px.xml), tinted per call site. */
@Composable
fun agendaIcon(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_calendar_view_day)

/** The saved-search icon (design/icons/filter_center_focus_48px.xml), tinted per call site. */
@Composable
fun savedSearchIcon(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_filter_center_focus)

/** The notebook icon (design/icons/book_48px.xml), tinted per call site. */
@Composable
fun notebookIcon(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_book)

/** The Dailies icon (design/icons/note_stack_add, weight 200), tinted per call site. */
@Composable
fun dailiesIcon(): ImageVector = ImageVector.vectorResource(id = R.drawable.ic_note_stack_add)

/**
 * Amber filled star marking a favorited heading (outline rows, read-mode headings).
 *
 * The star glyph sits centered in its icon box with ~28.75% empty margin on each
 * side (it doesn't touch the box's own edges), so a plain end-aligned icon reads as
 * short of a flush right edge (e.g. the property drawer below it); nudge right by
 * that margin to compensate.
 */
@Composable
fun FavoriteStar(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Icon(
        favoriteIconFilled(),
        contentDescription = null,
        tint = MaterialTheme.grove.amber,
        modifier = modifier.size(size).offset(x = size * 0.2875f),
    )
}

/**
 * Segmented control per design spec (surface-2 container, accent active pill).
 * Labels render at 11.5sp (design system `labelSmall` scale) so options like
 * "Medium" fit without ellipsis at the control's usual widths. An option can
 * render as an [optionIcons] glyph instead of its text label (e.g. a "None"
 * icon) by supplying a non-null [ImageVector] at that index. [leadingIcons]
 * instead keeps the label and puts a 13dp glyph before it (Look and Feel's
 * Light/Dark/System sun, moon and phone).
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    optionIcons: List<ImageVector?>? = null,
    leadingIcons: List<ImageVector?>? = null,
) = SegmentedControlImpl(options, selectedIndex, onSelect, modifier, optionIcons, onControlClick = null, leadingIcons)

/**
 * The Read/Edit mode switch every note, file, Dailies and capture top bar carries:
 * a two-option [SegmentedControl] with eye/pencil icons. The whole control is one
 * tap target that flips the mode, since the icon segments alone are small. It
 * owns an 8dp end inset (plus the top bar's own 8dp, the 16dp end gutter that
 * Capture's Save and the editor FABs share) so every screen's toggle lines up the same way.
 *
 * Mode-switch policy (never saving, seeding a new file, ...) stays with the
 * caller's [onToggle].
 */
@Composable
fun ReadEditToggle(
    isEditing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) = SegmentedControlImpl(
    options = listOf("Read", "Edit"),
    selectedIndex = if (isEditing) 1 else 0,
    onSelect = {},
    modifier = modifier
        .padding(end = 8.dp)
        .width(IntrinsicSize.Min)
        .testTag("read_edit_toggle")
        .semantics(mergeDescendants = true) {
            stateDescription = if (isEditing) "Edit mode" else "Read mode"
        },
    optionIcons = listOf(Icons.Outlined.Visibility, Icons.Outlined.Edit),
    onControlClick = onToggle,
)

/** [onControlClick] non-null makes the whole control one click target instead of per-segment ones. */
@Composable
private fun SegmentedControlImpl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    optionIcons: List<ImageVector?>?,
    onControlClick: (() -> Unit)?,
    leadingIcons: List<ImageVector?>? = null,
) {
    val c = MaterialTheme.grove
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .then(if (onControlClick != null) Modifier.clickable(onClick = onControlClick) else Modifier)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selectedIndex
            val tint = if (active) c.accentInk else c.ink2
            val icon = optionIcons?.getOrNull(i)
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) c.accent else Color.Transparent)
                    .then(if (onControlClick == null) Modifier.clickable { onSelect(i) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = tint,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leadingIcons?.getOrNull(i)?.let {
                            Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                        }
                        Text(
                            label,
                            color = tint,
                            fontFamily = PlexSans,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 56dp app bar row per design spec: leading slot, title block, trailing actions.
 * Consumes the status-bar inset itself: Scaffold does not pad the topBar slot,
 * so without this the bar sits under the status bar in edge-to-edge mode.
 *
 * An optional [subtitle] renders as a second row beneath the main bar, full
 * width: for content (e.g. a breadcrumb trail) that would otherwise compete
 * for space with wide [actions] in the fixed-height row.
 */
@Composable
fun GroveTopBar(
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    title: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    subtitle: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Box(Modifier.weight(1f)) { title() }
            actions()
        }
        subtitle?.let {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) { it() }
        }
    }
}

private data class ThemeSwatch(
    val pref: ThemePreference,
    val label: String,
    val bg: Color,
    val ink: Color,
    val dots: List<Color>,
)

/**
 * Preview colors per design/GroveThemes.dc.html `themeList()`: intentionally hardcoded rather
 * than derived from [com.rrajath.grove.ui.theme.GroveColors], since e.g. the dark theme's chip
 * uses its surface color rather than its bg for legibility. Ordered light themes first (alphabetical
 * by label), then dark themes (alphabetical by label): Settings § Appearance.
 */
private val ThemeSwatches = listOf(
    // Light
    ThemeSwatch(ThemePreference.ASHROSE, "Ash Rose", Color(0xFFFAF6F6), Color(0xFF241F21), listOf(Color(0xFF7D5560), Color(0xFF526B54), Color(0xFF4D6478))),
    ThemeSwatch(ThemePreference.BLOOMLIGHT, "Bloom Light", Color(0xFFF8F3EE), Color(0xFF4A4466), listOf(Color(0xFFC06A6E), Color(0xFF3F8A8F), Color(0xFFB4637A))),
    ThemeSwatch(ThemePreference.CATPPUCCINLATTE, "Catppuccin Latte", Color(0xFFEFF1F5), Color(0xFF4C4F69), listOf(Color(0xFF8839EF), Color(0xFFD20F39), Color(0xFF40A02B))),
    ThemeSwatch(ThemePreference.CHALK, "Chalk", Color(0xFFFFFFFF), Color(0xFF1C1F22), listOf(Color(0xFF3F4A52), Color(0xFF4A6B56), Color(0xFF4A647D))),
    ThemeSwatch(ThemePreference.COBALT, "Cobalt", Color(0xFFFFFFFF), Color(0xFF151A21), listOf(Color(0xFF1D5AA8), Color(0xFF3F6F52), Color(0xFF8A6F2C))),
    ThemeSwatch(ThemePreference.DELFT, "Delft", Color(0xFFEEF1F6), Color(0xFF1A222D), listOf(Color(0xFF2A5D9E), Color(0xFF456F57), Color(0xFF8A7030))),
    ThemeSwatch(ThemePreference.LIGHT, "Grove Light", Color(0xFFF3EDE1), Color(0xFF2A251F), listOf(Color(0xFF8A5A2B), Color(0xFF4F7A3A), Color(0xFF3F6F86))),
    ThemeSwatch(ThemePreference.ROSEPINEDAWN, "Rosé Pine Dawn", Color(0xFFFAF4ED), Color(0xFF575279), listOf(Color(0xFFD7827E), Color(0xFF286983), Color(0xFF907AA9))),
    ThemeSwatch(ThemePreference.SAGE, "Sage", Color(0xFFF6F8F4), Color(0xFF1F2622), listOf(Color(0xFF4E6B53), Color(0xFF4C6A78), Color(0xFF8A7333))),
    ThemeSwatch(ThemePreference.TOKYODAY, "Tokyo Day", Color(0xFFE1E2E7), Color(0xFF3760BF), listOf(Color(0xFF2E7DE9), Color(0xFF9854F1), Color(0xFF587539))),
    // Dark
    ThemeSwatch(ThemePreference.BLOOMDARK, "Bloom Dark", Color(0xFF25223A), Color(0xFFE2DFF2), listOf(Color(0xFFEBA8A2), Color(0xFF9FCFC4), Color(0xFFE57D99))),
    ThemeSwatch(ThemePreference.CATPPUCCIN, "Catppuccin Mocha", Color(0xFF1E1E2E), Color(0xFFCDD6F4), listOf(Color(0xFFCBA6F7), Color(0xFFF38BA8), Color(0xFFA6E3A1))),
    ThemeSwatch(ThemePreference.DARK, "Grove Dark", Color(0xFF201C15), Color(0xFFECE4D5), listOf(Color(0xFFCB9D62), Color(0xFF8FB46A), Color(0xFF7FB0C4))),
    ThemeSwatch(ThemePreference.LANTERN, "Lantern", Color(0xFF17181A), Color(0xFFE8E6E2), listOf(Color(0xFFE0C069), Color(0xFF8FAE86), Color(0xFF8AA8BD))),
    ThemeSwatch(ThemePreference.LIGHTHOUSE, "Lighthouse", Color(0xFF0F1723), Color(0xFFE3E8EF), listOf(Color(0xFFE8C777), Color(0xFF86A9CC), Color(0xFF8BB08F))),
    ThemeSwatch(ThemePreference.MOSS, "Moss", Color(0xFF000000), Color(0xFFE2E8DE), listOf(Color(0xFF8FAA87), Color(0xFF86A8AD), Color(0xFFC0A76C))),
    ThemeSwatch(ThemePreference.OBSIDIAN, "Obsidian", Color(0xFF000000), Color(0xFFE6E8EA), listOf(Color(0xFF9FB0BB), Color(0xFF86A58E), Color(0xFF8FABC0))),
    ThemeSwatch(ThemePreference.ROSEPINEMOON, "Rosé Pine Moon", Color(0xFF232136), Color(0xFFE0DEF4), listOf(Color(0xFFEA9A97), Color(0xFF3E8FB0), Color(0xFF9CCFD8))),
    ThemeSwatch(ThemePreference.TOKYONIGHT, "Tokyo Night", Color(0xFF1A1B26), Color(0xFFC0CAF5), listOf(Color(0xFF7AA2F7), Color(0xFFBB9AF7), Color(0xFF7DCFFF))),
    ThemeSwatch(ThemePreference.WISTERIA, "Wisteria", Color(0xFF131019), Color(0xFFE6E2EC), listOf(Color(0xFFA99BC4), Color(0xFF8FA6C6), Color(0xFF8BA88F))),
)

/**
 * Theme picker as a collapsed trigger + inline expanding list, per the Settings
 * screen in design/Grove.dc.html (`themeTriggerStyle` / theme dropdown rows).
 * The trigger shows the active theme's three dots, its name, and a chevron that
 * rotates while open; the list expands in place below it (no popup).
 * [dark] limits the list to one family (Light or Dark mode); null lists all.
 */
@Composable
fun ThemeDropdownPicker(
    selected: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean? = null,
) {
    val c = MaterialTheme.grove
    var open by rememberSaveable { mutableStateOf(false) }
    val active = ThemeSwatches.first { it.pref == selected }
    val chevronAngle by animateFloatAsState(if (open) 180f else 0f, label = "themeChevron")

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface2)
                .border(1.dp, if (open) c.accent else c.line, RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(horizontal = 11.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeDots(active.dots)
            Text(
                active.label,
                color = c.ink,
                fontFamily = PlexSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "▾",
                color = c.ink3,
                fontSize = 16.5.sp,
                modifier = Modifier.graphicsLayer { rotationZ = chevronAngle },
            )
        }
        AnimatedVisibility(open) {
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(c.surface2)
                    .padding(6.dp)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ThemeSwatches.filter { dark == null || it.pref.isDark == dark }.forEach { swatch ->
                    ThemeDropdownRow(
                        swatch,
                        active = swatch.pref == selected,
                        onClick = {
                            onSelect(swatch.pref)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * System mode's theme pair (Settings § Look and Feel): two side-by-side tiles,
 * one per family, each previewing its theme's swatch and name. The tile in use
 * right now ([systemIsDark]) carries an "In use" pill. Tapping a tile expands
 * that family's list full width below both tiles, with [ThemeDropdownPicker]'s
 * list chrome; one list is open at a time.
 */
@Composable
fun ThemePairPicker(
    light: ThemePreference,
    dark: ThemePreference,
    systemIsDark: Boolean,
    onSelectLight: (ThemePreference) -> Unit,
    onSelectDark: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    // null = closed, false = light list, true = dark list.
    var openDark by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Holds the last open family while the list animates closed.
    var shownDark by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (isDark in listOf(false, true)) {
                ThemePairTile(
                    swatch = ThemeSwatches.first { it.pref == if (isDark) dark else light },
                    label = if (isDark) "Dark" else "Light",
                    icon = if (isDark) Icons.Filled.DarkMode else Icons.Outlined.LightMode,
                    inUse = isDark == systemIsDark,
                    open = openDark == isDark,
                    onClick = {
                        openDark = if (openDark == isDark) null else isDark
                        shownDark = isDark
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        AnimatedVisibility(openDark != null) {
            val selected = if (shownDark) dark else light
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(c.surface2)
                    .padding(6.dp)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ThemeSwatches.filter { it.pref.isDark == shownDark }.forEach { swatch ->
                    ThemeDropdownRow(
                        swatch,
                        active = swatch.pref == selected,
                        onClick = {
                            if (shownDark) onSelectDark(swatch.pref) else onSelectLight(swatch.pref)
                            openDark = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePairTile(
    swatch: ThemeSwatch,
    label: String,
    icon: ImageVector,
    inUse: Boolean,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface2)
            .border(1.dp, if (open) c.accent else c.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.ink2, modifier = Modifier.size(13.dp))
            Text(
                label,
                color = c.ink2,
                fontFamily = PlexSans,
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp,
                modifier = Modifier.weight(1f),
            )
            if (inUse) {
                Text(
                    "In use",
                    color = c.accent,
                    fontFamily = PlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(c.accentSoft)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(swatch.bg)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            ThemeDots(swatch.dots)
        }
        Text(
            swatch.label,
            color = c.ink,
            fontFamily = PlexSans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThemeDropdownRow(swatch: ThemeSwatch, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(swatch.bg)
            .border(2.dp, if (active) swatch.dots[0] else Color.Transparent, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeDots(swatch.dots)
        Text(
            swatch.label,
            color = swatch.ink,
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = swatch.dots[0],
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun ThemeDots(dots: List<Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        dots.forEach { dot ->
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
        }
    }
}

/**
 * Text-options dropdown (Settings § Agenda swipe-left/swipe-right action pickers): a collapsed
 * trigger row sharing [ThemeDropdownPicker]'s chrome, expanding into a list of options with a
 * checkmark on the active one. Unlike [ThemeDropdownPicker] this is a general-purpose picker for
 * any small list of string options, no swatches.
 */
@Composable
fun DropdownPicker(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.grove
    var open by rememberSaveable { mutableStateOf(false) }
    val chevronAngle by animateFloatAsState(if (open) 180f else 0f, label = "dropdownChevron")

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface2)
                .border(1.dp, if (open) c.accent else c.line, RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(horizontal = 11.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.getOrElse(selectedIndex) { "" },
                color = c.ink,
                fontFamily = PlexSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "▾",
                color = c.ink3,
                fontSize = 16.5.sp,
                modifier = Modifier.graphicsLayer { rotationZ = chevronAngle },
            )
        }
        AnimatedVisibility(open) {
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(c.surface2)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEachIndexed { i, label ->
                    val active = i == selectedIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) c.accentSoft else Color.Transparent)
                            .clickable {
                                onSelect(i)
                                open = false
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            color = if (active) c.accentInk else c.ink,
                            fontFamily = PlexSans,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = c.accentInk,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Amber-tinted FAB drop shadow (design system: `rgba(138,90,43,0.40)`). */
private val FabShadowColor = Color(0x668A5A2B)

/**
 * Icon-only FAB used on the note editor screens to open the metadata sheet —
 * the affordance that used to be the top-bar "☰" glyph. 54dp square with 18dp
 * corners, accent fill, amber-tinted drop shadow. The caller positions it
 * (typically the bottom-end of the content area).
 *
 * [compact] is the 44x36dp, 10dp-corner, shadowless variant that sits inside the
 * editor suggestion strip, sized to match Capture's Save button. Flipping it
 * morphs size, corners, shadow and glyph over [EditorFabMorphMillis] rather
 * than snapping.
 */
@Composable
fun EditorMenuFab(onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = MaterialTheme.grove
    val t by animateFloatAsState(if (compact) 1f else 0f, tween(EditorFabMorphMillis), label = "fabMorph")
    val shape = RoundedCornerShape(lerp(18.dp, 10.dp, t))
    Box(
        modifier
            .size(width = lerp(54.dp, EditorFabCompactWidth, t), height = lerp(54.dp, 36.dp, t))
            .shadow(
                elevation = lerp(10.dp, 0.dp, t),
                shape = shape,
                clip = false,
                ambientColor = FabShadowColor,
                spotColor = FabShadowColor,
            )
            .clip(shape)
            .background(c.accent)
            .clickable(onClickLabel = "Open metadata", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("☰", fontFamily = PlexSans, fontSize = (20f - 4f * t).sp, color = c.accentInk)
    }
}

/** Duration of the [EditorMenuFab] morph and the layout that moves with it. */
const val EditorFabMorphMillis = 220

/** Width of the compact [EditorMenuFab]; the suggestion strip reserves this much. */
val EditorFabCompactWidth = 44.dp
