package com.rrajath.grove.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.rrajath.grove.icon.AppIconManager
import com.rrajath.grove.settings.ThemePreference
import com.rrajath.grove.ui.components.monogramLetter
import com.rrajath.grove.ui.nav.DeepLinks
import com.rrajath.grove.ui.theme.GroveBloomLightColors
import com.rrajath.grove.ui.theme.groveColorsFor

/**
 * Publishes one dynamic launcher long-press shortcut per configured capture
 * template, so Settings-managed templates (add/edit/remove/reorder) are
 * always reflected without a manifest change. Replaces the old static
 * res/xml/shortcuts.xml, which only covered the two built-in templates and
 * stopped showing once MainActivity was no longer the enabled LAUNCHER
 * component (see AppIconManager's activity-alias icon switching).
 */
object ShortcutSyncer {

    private const val ICON_SIZE_PX = 108

    /**
     * [theme]/[iconThemed] mirror the same fields AppIconManager uses for the
     * launcher icon itself, so shortcut glyphs stay visually consistent with
     * whichever icon (default Bloom Light mark, or theme-tinted) is currently shown.
     */
    fun sync(context: Context, templates: List<CaptureTemplate>, theme: ThemePreference, iconThemed: Boolean) {
        val colors = if (iconThemed) groveColorsFor(theme) else GroveBloomLightColors
        val bg = colors.accent.toArgb()
        val fg = colors.accentInk.toArgb()
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).takeIf { it > 0 } ?: 4
        // Bind explicitly to whichever alias actually owns the launcher slot
        // right now. A pending theme change hasn't switched it yet (that
        // happens later, at ON_STOP), so binding to the *target* alias here
        // makes ShortcutManagerCompat reject the publish. GroveApp.kt's
        // ON_STOP handler re-syncs against the new alias right after
        // AppIconManager.applyIcon actually enables it.
        val ownerAlias = AppIconManager.currentAliasComponent(context)
        val shortcuts = templates.take(max).map { toShortcut(context, it, bg, fg, ownerAlias) }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    /**
     * Tells the launcher that [templateId]'s capture shortcut was just
     * invoked, so it can rank frequently-used capture shortcuts higher.
     * Called from the bloom://capture/{id} deep-link handler.
     */
    fun reportShortcutUsed(context: Context, templateId: String) {
        ShortcutManagerCompat.reportShortcutUsed(context, templateId)
    }

    /**
     * Pins a single template's capture shortcut to the homescreen, via
     * whichever system picker/confirmation the launcher shows for
     * requestPinShortcut. Returns false if the current launcher doesn't
     * support pinning at all -- callers should tell the user in that case,
     * since no dialog will appear to explain the no-op.
     */
    fun requestPin(context: Context, template: CaptureTemplate, theme: ThemePreference, iconThemed: Boolean): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val colors = if (iconThemed) groveColorsFor(theme) else GroveBloomLightColors
        val bg = colors.accent.toArgb()
        val fg = colors.accentInk.toArgb()
        val ownerAlias = AppIconManager.currentAliasComponent(context)
        val shortcut = toShortcut(context, template, bg, fg, ownerAlias)
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    private fun toShortcut(
        context: Context,
        template: CaptureTemplate,
        bg: Int,
        fg: Int,
        ownerAlias: android.content.ComponentName,
    ): ShortcutInfoCompat {
        val intent = Intent(Intent.ACTION_VIEW, DeepLinks.captureTemplate(template.id).toUri())
            .setClassName(context.packageName, "com.rrajath.grove.MainActivity")
        return ShortcutInfoCompat.Builder(context, template.id)
            .setShortLabel(template.name)
            .setLongLabel("New ${template.name}")
            .setIcon(IconCompat.createWithBitmap(glyphBitmap(monogramLetter(template.name), bg, fg)))
            .setIntent(intent)
            .setActivity(ownerAlias)
            .build()
    }

    private fun glyphBitmap(glyph: String, bg: Int, fg: Int): Bitmap {
        val bitmap = createBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        canvas.drawCircle(ICON_SIZE_PX / 2f, ICON_SIZE_PX / 2f, ICON_SIZE_PX / 2f, bgPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            textSize = ICON_SIZE_PX * 0.5f
            textAlign = Paint.Align.CENTER
        }
        val yPos = ICON_SIZE_PX / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(glyph, ICON_SIZE_PX / 2f, yPos, textPaint)
        return bitmap
    }
}
