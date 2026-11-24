package com.rrajath.grove.ui.screens.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.R
import com.rrajath.grove.settings.FontSizePreference
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.settings.ThemeMode
import com.rrajath.grove.settings.ThemePreference
import com.rrajath.grove.ui.components.SegmentedControl
import com.rrajath.grove.ui.components.ThemeDropdownPicker
import com.rrajath.grove.ui.components.ThemePairPicker
import com.rrajath.grove.ui.newbadge.MarkNewFeatureSeen
import com.rrajath.grove.ui.newbadge.NewAnchors
import com.rrajath.grove.ui.newbadge.NewDot
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.theme.systemDarkThemeSupported

/** Settings § Look and Feel (was the "APPEARANCE" section before the settings-page split). */
@Composable
fun SettingsAppearanceScreen(
    settings: GroveSettings,
    onBack: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetLightTheme: (ThemePreference) -> Unit,
    onSetDarkTheme: (ThemePreference) -> Unit,
    onSetSyncAppIconWithTheme: (Boolean) -> Unit,
    onSetAppFontSize: (FontSizePreference) -> Unit,
) {
    val c = MaterialTheme.grove
    val selection = settings.themeSelection
    val systemIsDark = isSystemInDarkTheme()
    val mode = selection.effectiveMode(systemDarkThemeSupported)
    SettingsPageScaffold(title = stringResource(R.string.settings_page_appearance_title), onBack = onBack) {
        // Leaving this screen retires the "Text size" NEW dot from its whole
        // trail (menu glyph, drawer, Settings hub row, the block itself).
        MarkNewFeatureSeen(NewAnchors.SETTINGS_APPEARANCE_TEXT_SIZE)
        MarkNewFeatureSeen(NewAnchors.SETTINGS_APPEARANCE_THEME)
        SettingsGroup {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_appearance_theme),
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                    )
                    NewDot(NewAnchors.SETTINGS_APPEARANCE_THEME)
                }
                // Android 6-9 have no system dark theme, so System isn't offered there.
                val modes = if (systemDarkThemeSupported) ThemeMode.entries else listOf(ThemeMode.LIGHT, ThemeMode.DARK)
                SegmentedControl(
                    options = modes.map { stringResource(it.labelRes) },
                    selectedIndex = modes.indexOf(mode),
                    onSelect = { onSetThemeMode(modes[it]) },
                    leadingIcons = modes.map { it.icon },
                    modifier = Modifier.fillMaxWidth(),
                )
                when (mode) {
                    ThemeMode.LIGHT -> ThemeDropdownPicker(
                        selected = selection.light,
                        onSelect = onSetLightTheme,
                        dark = false,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    ThemeMode.DARK -> ThemeDropdownPicker(
                        selected = selection.dark,
                        onSelect = onSetDarkTheme,
                        dark = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    ThemeMode.SYSTEM -> {
                        ThemePairPicker(
                            light = selection.light,
                            dark = selection.dark,
                            systemIsDark = systemIsDark,
                            onSelectLight = onSetLightTheme,
                            onSelectDark = onSetDarkTheme,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                        Text(
                            stringResource(R.string.settings_appearance_system_hint, stringResource(R.string.app_name)),
                            fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            // Themed launcher icons only exist as <adaptive-icon> (API 26+); on
            // older devices there's nothing for this setting to actually do.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                RowDivider()
                SyncAppIconRow(
                    checked = settings.syncAppIconWithTheme,
                    theme = selection.resolve(systemIsDark, systemDarkThemeSupported),
                    followsSystem = mode == ThemeMode.SYSTEM,
                    onToggle = onSetSyncAppIconWithTheme,
                )
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_appearance_text_size),
                        fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                        fontSize = 14.5.sp, color = c.ink,
                    )
                    NewDot(NewAnchors.SETTINGS_APPEARANCE_TEXT_SIZE)
                }
                Text(
                    stringResource(R.string.settings_appearance_text_size_hint),
                    fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 2.dp),
                )
                SegmentedControl(
                    options = FontSizePreference.entries.map { stringResource(it.labelRes) },
                    selectedIndex = settings.appFontSize.ordinal,
                    onSelect = { onSetAppFontSize(FontSizePreference.entries[it]) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }
        }
    }
}

/**
 * stringResource(R.string.settings_appearance_sync_icon) toggle row plus a live 60x60 launcher-icon preview
 * (design/Grove.dc.html lines 827-841: the tile mirrors `iconSpokesLauncher`,
 * `spokeSet(16, 7)`). The preview shows [theme]'s colors when [checked], the
 * default light mark otherwise. With [followsSystem] the icon trails the phone's
 * light/dark switch (the alias only changes at ON_STOP), so a hint says so.
 */
@Composable
private fun SyncAppIconRow(
    checked: Boolean,
    theme: ThemePreference,
    followsSystem: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val c = MaterialTheme.grove
    val previewColors = if (checked) com.rrajath.grove.ui.theme.groveColorsFor(theme) else com.rrajath.grove.ui.theme.GroveBloomLightColors
    Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle(!checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_appearance_sync_icon),
                fontFamily = PlexSans, fontWeight = FontWeight.Medium,
                fontSize = 14.5.sp, color = c.ink,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = c.accent,
                    checkedThumbColor = c.accentInk,
                    uncheckedTrackColor = c.surface3,
                    uncheckedThumbColor = c.surface,
                ),
            )
        }
        if (checked && followsSystem) {
            Text(
                stringResource(R.string.settings_appearance_sync_icon_hint, stringResource(R.string.app_name)),
                fontFamily = PlexSans, fontSize = 12.sp, color = c.ink3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .shadow(3.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = ShadowColor, spotColor = ShadowColor)
                    .clip(RoundedCornerShape(16.dp))
                    .background(previewColors.accentSoft)
                    .border(1.dp, previewColors.line, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(32.dp)) {
                    val spokeLength = size.minDimension / 2f
                    val spokeWidth = size.minDimension * (7f / 32f)
                    val centerOffset = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                    val topLeft = androidx.compose.ui.geometry.Offset(centerOffset.x - spokeWidth / 2f, centerOffset.y)
                    val spokeSize = androidx.compose.ui.geometry.Size(spokeWidth, spokeLength)
                    val cornerRadius = androidx.compose.ui.geometry.CornerRadius(spokeWidth / 2f)
                    for (angle in listOf(36f, 108f, 180f, 252f, 324f)) {
                        rotate(angle, pivot = centerOffset) {
                            drawRoundRect(
                                color = previewColors.accent,
                                topLeft = topLeft,
                                size = spokeSize,
                                cornerRadius = cornerRadius,
                            )
                        }
                    }
                }
            }
        }
    }
}

@get:StringRes
private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.LIGHT -> R.string.settings_appearance_mode_light
        ThemeMode.DARK -> R.string.settings_appearance_mode_dark
        ThemeMode.SYSTEM -> R.string.settings_appearance_mode_system
    }

private val ThemeMode.icon: ImageVector
    get() = when (this) {
        ThemeMode.LIGHT -> Icons.Outlined.LightMode
        ThemeMode.DARK -> Icons.Filled.DarkMode
        ThemeMode.SYSTEM -> Icons.Outlined.Smartphone
    }

private val ShadowColor = androidx.compose.ui.graphics.Color(0x243C2D19) // rgba(60,45,25,0.14)
