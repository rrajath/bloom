package com.rrajath.grove.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.settings.GroveSettings
import com.rrajath.grove.ui.theme.GroveTheme
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "GroveWidget"

/**
 * The Agenda widget's view picker, opened by tapping the widget's title: a small
 * list ("Agenda", then the user's saved searches) overlaid on whatever app the
 * user was in, built the same way as [WidgetQuickAddActivity] (scrim that
 * dismisses on tap, a sheet that swallows taps) so the two read as one family.
 * Picking an item stores it in that widget instance's own Glance state (see
 * [WIDGET_VIEW_KEY]) and redraws just that widget.
 */
class WidgetViewPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.w(TAG, "view picker opened without an appWidgetId")
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val app = LocalContext.current.applicationContext as GroveApplication
            var theme by remember { mutableStateOf<GroveSettings?>(null) }
            LaunchedEffect(Unit) { theme = app.settingsRepository.settings.first() }
            theme?.let { s ->
                GroveTheme(theme = s.theme) {
                    ViewPickerSheet(appWidgetId, onDismiss = ::finish)
                }
            }
        }
    }

    companion object {
        /**
         * The widget id rides in the data URI as well as the extra: PendingIntents
         * are matched without their extras, so two Agenda widgets with
         * otherwise-identical intents would share one PendingIntent and both
         * open the picker for whichever widget was drawn last.
         */
        fun intent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetViewPickerActivity::class.java)
                .setData(Uri.fromParts("bloom-widget", "view-picker", appWidgetId.toString()))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
}

@Composable
private fun ViewPickerSheet(appWidgetId: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GroveApplication
    val agendaLabel = stringResource(R.string.widget_view_agenda)

    var glanceId by remember { mutableStateOf<GlanceId?>(null) }
    var items by remember { mutableStateOf<List<WidgetViews.PickerItem>?>(null) }
    LaunchedEffect(Unit) {
        val id = runCatching { GlanceAppWidgetManager(app).getGlanceIdBy(appWidgetId) }.getOrNull()
        if (id == null) {
            // The widget was removed while its PendingIntent was still live.
            Log.w(TAG, "no Glance widget for appWidgetId $appWidgetId")
            onDismiss()
            return@LaunchedEffect
        }
        val selectedId = LedgerWidget().getAppWidgetState<Preferences>(app, id)[WIDGET_VIEW_KEY]
        glanceId = id
        items = WidgetViews.pickerItems(agendaLabel, app.searchRepository.savedSearches.first(), selectedId)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onDismiss),
        )
        val list = items ?: return@Box
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.grove.surface)
                // Swallow taps on the sheet's own empty space so they don't fall
                // through to the scrim and dismiss; rows consume their own taps first.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(13.dp),
        ) {
            Text(
                stringResource(R.string.widget_view_picker_title),
                color = MaterialTheme.grove.ink3,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = PlexSans,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 7.dp),
            )
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                list.forEach { item ->
                    PickerRow(item) {
                        val id = glanceId ?: return@PickerRow
                        // The write and redraw run on the app scope so finishing
                        // this activity right away can't cancel them.
                        app.appScope.launch {
                            updateAppWidgetState(app, id) { prefs ->
                                if (item.searchId == null) prefs.remove(WIDGET_VIEW_KEY)
                                else prefs[WIDGET_VIEW_KEY] = item.searchId
                            }
                            LedgerWidget().update(app, id)
                        }
                        onDismiss()
                    }
                }
            }
        }
    }
}

/**
 * One picker item. The current view gets the active-chip treatment the
 * quick-add composer's chips use (`accentSoft` fill, `accent` text) plus a
 * trailing check, so the selection reads at a glance.
 */
@Composable
private fun PickerRow(item: WidgetViews.PickerItem, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (item.selected) c.accentSoft else Color.Transparent)
            .semantics { selected = item.selected }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.label,
            color = if (item.selected) c.accent else c.ink,
            fontSize = 15.sp,
            fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = PlexSans,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.selected) {
            Spacer(Modifier.width(8.dp))
            Text(
                "✓",
                color = c.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = PlexSans,
            )
        }
    }
}
