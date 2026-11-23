package com.rrajath.grove.widget

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.MainActivity
import com.rrajath.grove.R
import com.rrajath.grove.ui.nav.DeepLinks

/**
 * Home-screen "Capture" widget (PRD §8): one tap opens the capture picker
 * via the bloom://capture deep link.
 */
class CaptureWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val intent = Intent(Intent.ACTION_VIEW, DeepLinks.capture().toUri())
                    .setClass(context, MainActivity::class.java)
                // The mark is drawn from a vector (ic_brand_asterisk), not a "✱"
                // text glyph: U+2731 is absent from many device system fonts, so
                // the glyph rendered as blank or tofu on those phones.
                val markColor = ColorProvider(Color(0xFFFDF7F3), Color(0xFF25223A))
                Row(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(17.dp)
                        .background(ColorProvider(Color(0xFFC06A6E), Color(0xFFEBA8A2)))
                        .clickable(actionStartActivity(intent))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_brand_asterisk),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(markColor),
                        modifier = GlanceModifier.size(15.dp),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        context.getString(R.string.widget_capture_label),
                        style = TextStyle(
                            color = markColor,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                        ),
                    )
                }
            }
        }
    }
}

class CaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaptureWidget()
}
