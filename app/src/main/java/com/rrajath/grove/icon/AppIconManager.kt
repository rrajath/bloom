// i18n: activity-alias class names from the manifest, not UI text
package com.rrajath.grove.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.rrajath.grove.R
import com.rrajath.grove.settings.ThemePreference

/**
 * Switches the launcher icon between the default mark and a themed variant by
 * enabling exactly one `activity-alias` declared in AndroidManifest.xml and
 * disabling the rest. See "Sync App Icon with Theme" in Settings > Appearance.
 */
object AppIconManager {

    const val DEFAULT_ALIAS = ".IconDefault"

    // Alias classes resolve against the manifest package, not the applicationId:
    // in debug builds packageName is "com.rrajath.grove.debug" (applicationIdSuffix)
    // while the alias class stays "com.rrajath.grove.IconDefault".
    private const val MANIFEST_PACKAGE = "com.rrajath.grove"

    val THEME_ALIASES: Map<ThemePreference, String> = mapOf(
        ThemePreference.LIGHT to ".IconLight",
        ThemePreference.DARK to ".IconDark",
        ThemePreference.TOKYONIGHT to ".IconTokyoNight",
        ThemePreference.TOKYODAY to ".IconTokyoDay",
        ThemePreference.CATPPUCCIN to ".IconCatppuccin",
        ThemePreference.CATPPUCCINLATTE to ".IconCatppuccinLatte",
        ThemePreference.ROSEPINEDAWN to ".IconRosePineDawn",
        ThemePreference.ROSEPINEMOON to ".IconRosePineMoon",
        ThemePreference.CHALK to ".IconChalk",
        ThemePreference.COBALT to ".IconCobalt",
        ThemePreference.DELFT to ".IconDelft",
        ThemePreference.SAGE to ".IconSage",
        ThemePreference.ASHROSE to ".IconAshRose",
        ThemePreference.OBSIDIAN to ".IconObsidian",
        ThemePreference.LANTERN to ".IconLantern",
        ThemePreference.LIGHTHOUSE to ".IconLighthouse",
        ThemePreference.MOSS to ".IconMoss",
        ThemePreference.WISTERIA to ".IconWisteria",
        ThemePreference.BLOOMLIGHT to ".IconBloomLight",
        ThemePreference.BLOOMDARK to ".IconBloomDark",
    )

    val ALL_ALIASES: List<String> = listOf(DEFAULT_ALIAS) + THEME_ALIASES.values

    /** Mark color of the default launcher icon (`ic_launcher_foreground.xml`). */
    private const val DEFAULT_MARK_COLOR = 0xFFC06A6E.toInt()

    // Mark colors of the per-theme `ic_launcher_foreground_*.xml` drawables.
    private val THEME_MARK_COLORS: Map<ThemePreference, Int> = mapOf(
        ThemePreference.LIGHT to 0xFF8A5A2B.toInt(),
        ThemePreference.DARK to 0xFFCB9D62.toInt(),
        ThemePreference.TOKYONIGHT to 0xFF7AA2F7.toInt(),
        ThemePreference.TOKYODAY to 0xFF2E7DE9.toInt(),
        ThemePreference.CATPPUCCIN to 0xFFCBA6F7.toInt(),
        ThemePreference.CATPPUCCINLATTE to 0xFF8839EF.toInt(),
        ThemePreference.ROSEPINEDAWN to 0xFF907AA9.toInt(),
        ThemePreference.ROSEPINEMOON to 0xFFC4A7E7.toInt(),
        ThemePreference.CHALK to 0xFF3F4A52.toInt(),
        ThemePreference.COBALT to 0xFF1D5AA8.toInt(),
        ThemePreference.DELFT to 0xFF2A5D9E.toInt(),
        ThemePreference.SAGE to 0xFF4E6B53.toInt(),
        ThemePreference.ASHROSE to 0xFF7D5560.toInt(),
        ThemePreference.OBSIDIAN to 0xFF9FB0BB.toInt(),
        ThemePreference.LANTERN to 0xFFE0C069.toInt(),
        ThemePreference.LIGHTHOUSE to 0xFFE8C777.toInt(),
        ThemePreference.MOSS to 0xFF8FAA87.toInt(),
        ThemePreference.WISTERIA to 0xFFA99BC4.toInt(),
        ThemePreference.BLOOMLIGHT to 0xFFC06A6E.toInt(),
        ThemePreference.BLOOMDARK to 0xFFEBA8A2.toInt(),
    )

    /**
     * The color a notification should tint the Grove mark with, for the same
     * (enabled, theme) pair [targetAlias] resolves.
     *
     * Android renders a notification's small icon as an alpha mask and tints it
     * with `NotificationCompat.Builder.setColor`, so this (not the drawable)
     * is what actually makes the notification icon follow the launcher icon.
     * Every `ic_launcher_foreground_*` variant is the identical five-spoke path
     * differing only in fill, so matching the color matches the icon.
     */
    fun markColor(enabled: Boolean, theme: ThemePreference): Int =
        if (enabled) THEME_MARK_COLORS[theme] ?: DEFAULT_MARK_COLOR else DEFAULT_MARK_COLOR

    /**
     * The alias that should be enabled for a given (enabled, theme) pair: pure
     * mapping logic, split out from [applyIcon] so it's JVM-testable without a
     * Context/PackageManager.
     */
    fun targetAlias(enabled: Boolean, theme: ThemePreference): String =
        if (enabled) THEME_ALIASES[theme] ?: DEFAULT_ALIAS else DEFAULT_ALIAS

    /**
     * Whether [applyIcon] has any launcher-alias work to do given the alias
     * [currentAlias] currently enabled. False whenever it already matches the
     * target for (enabled, theme) — the common case on every app-backgrounding,
     * where the icon theme hasn't changed since last time. Split out pure so the
     * skip is JVM-testable without a PackageManager. See PERFORMANCE_AUDIT_2026-08-27 #5.
     */
    fun iconChangeNeeded(currentAlias: String, enabled: Boolean, theme: ThemePreference): Boolean =
        currentAlias != targetAlias(enabled, theme)

    // Per-theme adaptive-icon mipmaps (`res/mipmap-anydpi/ic_launcher_<key>.xml`),
    // for surfaces that need the actual icon image rather than just its mark
    // color (e.g. the Ledger widget's header tile) — everything else here only
    // ever needed [markColor] or the alias name.
    private val THEME_MIPMAPS: Map<ThemePreference, Int> = mapOf(
        ThemePreference.LIGHT to R.mipmap.ic_launcher_light,
        ThemePreference.DARK to R.mipmap.ic_launcher_dark,
        ThemePreference.TOKYONIGHT to R.mipmap.ic_launcher_tokyonight,
        ThemePreference.TOKYODAY to R.mipmap.ic_launcher_tokyoday,
        ThemePreference.CATPPUCCIN to R.mipmap.ic_launcher_catppuccin,
        ThemePreference.CATPPUCCINLATTE to R.mipmap.ic_launcher_catppuccinlatte,
        ThemePreference.ROSEPINEDAWN to R.mipmap.ic_launcher_rosepinedawn,
        ThemePreference.ROSEPINEMOON to R.mipmap.ic_launcher_rosepinemoon,
        ThemePreference.CHALK to R.mipmap.ic_launcher_chalk,
        ThemePreference.COBALT to R.mipmap.ic_launcher_cobalt,
        ThemePreference.DELFT to R.mipmap.ic_launcher_delft,
        ThemePreference.SAGE to R.mipmap.ic_launcher_sage,
        ThemePreference.ASHROSE to R.mipmap.ic_launcher_ashrose,
        ThemePreference.OBSIDIAN to R.mipmap.ic_launcher_obsidian,
        ThemePreference.LANTERN to R.mipmap.ic_launcher_lantern,
        ThemePreference.LIGHTHOUSE to R.mipmap.ic_launcher_lighthouse,
        ThemePreference.MOSS to R.mipmap.ic_launcher_moss,
        ThemePreference.WISTERIA to R.mipmap.ic_launcher_wisteria,
        ThemePreference.BLOOMLIGHT to R.mipmap.ic_launcher_bloomlight,
        ThemePreference.BLOOMDARK to R.mipmap.ic_launcher_bloomdark,
    )

    /** Same (enabled, theme) mapping as [markColor]/[targetAlias], as the actual icon mipmap. */
    fun mipmapRes(enabled: Boolean, theme: ThemePreference): Int =
        if (enabled) THEME_MIPMAPS[theme] ?: R.mipmap.ic_launcher else R.mipmap.ic_launcher

    /**
     * The launcher-alias [ComponentName] that is *actually* enabled right
     * now. Dynamic shortcuts must bind to this via
     * `ShortcutInfoCompat.Builder.setActivity`; binding to the alias a
     * pending theme change will switch to (rather than the one currently
     * enabled) makes `ShortcutManagerCompat.setDynamicShortcuts` throw
     * `IllegalStateException: ... is not main activity`, since [applyIcon]
     * runs later (deferred to ON_STOP — see GroveApp.kt) and hasn't enabled
     * that alias yet.
     */
    fun currentAliasComponent(context: Context): ComponentName {
        val pm = context.packageManager
        val enabledAlias = ALL_ALIASES.firstOrNull { alias ->
            val state = pm.getComponentEnabledSetting(
                ComponentName(context.packageName, "$MANIFEST_PACKAGE$alias"),
            )
            when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                // COMPONENT_ENABLED_STATE_DEFAULT: fall back to the manifest's
                // android:enabled, which is true only for DEFAULT_ALIAS.
                else -> alias == DEFAULT_ALIAS
            }
        } ?: DEFAULT_ALIAS
        return ComponentName(context.packageName, "$MANIFEST_PACKAGE$enabledAlias")
    }

    /**
     * Enables the alias matching [theme] when [enabled] is true (icon follows
     * the selected theme); otherwise enables the default alias. Disables every
     * other alias so exactly one launcher icon is ever active.
     */
    fun applyIcon(context: Context, enabled: Boolean, theme: ThemePreference) {
        // Themed alias icons only exist as <adaptive-icon> (API 26+); below
        // that only the default alias has a resolvable icon resource, so
        // treat "enabled" as always false regardless of the stored setting
        // (e.g. a setting imported from a backup taken on a 26+ device).
        val themingSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val effectivelyEnabled = enabled && themingSupported
        val target = targetAlias(effectivelyEnabled, theme)
        // ON_STOP fires on every backgrounding, but the icon theme rarely
        // changed since the last apply. Bail before touching PackageManager at
        // all when the currently-enabled alias is already the target, instead of
        // pushing one no-op Binder IPC per alias every time (PERFORMANCE_AUDIT #5).
        val currentAlias = currentAliasComponent(context).className.removePrefix(MANIFEST_PACKAGE)
        if (!iconChangeNeeded(currentAlias, effectivelyEnabled, theme)) return
        val pm = context.packageManager
        for (alias in ALL_ALIASES) {
            val state = if (alias == target) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, "$MANIFEST_PACKAGE$alias"),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
