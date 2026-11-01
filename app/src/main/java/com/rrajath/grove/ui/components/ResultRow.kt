package com.rrajath.grove.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.theme.priorityColor

/**
 * Shared keyword-pill/priority/title/snippet layout for a Search or Agenda
 * result row (design spec §9). Screen-specific concerns stay with the caller:
 * Search passes match-highlighted [titleText]/[snippetText] plus its own
 * date-pill/tag [metaContent] row; Agenda passes plain link-styled text plus
 * its own file/heading breadcrumb, and wraps the whole thing in a swipe
 * gesture. Neither the outer tap target/padding nor the divider between rows
 * lives here: callers own those so each screen's list keeps its own
 * click/swipe handling and edge-to-edge row spacing.
 */
@Composable
fun ResultRowContent(
    keyword: String?,
    isDone: Boolean,
    priority: String?,
    titleText: AnnotatedString,
    snippetText: AnnotatedString?,
    modifier: Modifier = Modifier,
    titleWeight: FontWeight = FontWeight.SemiBold,
    metaContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = MaterialTheme.grove
    // An RTL title mirrors the row (keyword and priority on the right), like Outline
    // rows; title and snippet each take their own direction.
    val titleRtl = remember(titleText.text) { textStartsRtl(titleText.text) }
    CompositionLocalProvider(LocalLayoutDirection provides if (titleRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            keyword?.let { kw ->
                Pill(kw, fg = if (isDone) c.green else c.amber, bg = if (isDone) c.greenSoft else c.amberSoft)
                Spacer(Modifier.width(7.dp))
            }
            priority?.let { p ->
                Text(
                    "[#$p]", fontFamily = PlexMono, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    color = c.priorityColor(p[0]),
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                titleText, style = TextStyle(textDirection = TextDirection.Content),
                fontFamily = PlexSans, fontWeight = titleWeight, fontSize = 14.5.sp,
                color = if (isDone) c.ink3 else c.ink,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
            )
        }
        if (snippetText != null && snippetText.text.isNotEmpty()) {
            Text(
                snippetText, style = TextStyle(textDirection = TextDirection.Content),
                fontFamily = PlexSans, fontSize = 12.5.sp, lineHeight = 1.5.em, color = c.ink2,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            )
        }
        metaContent?.invoke(this)
    }
    }
}
