package com.rrajath.grove.ui.dailies

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.org.LocalDates
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyDatePickerSheet(
    initialMonth: LocalDate,
    existingDates: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.grove
    var month by remember { mutableStateOf(YearMonth.from(initialMonth)) }
    val today = LocalDate.now()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonthArrow("‹", "Previous month") { month = month.minusMonths(1) }
                Text(
                    LocalDates.format(month, "yMMMM", "MMMM yyyy"),
                    fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink,
                )
                MonthArrow("›", "Next month") { month = month.plusMonths(1) }
            }
            val firstOfMonth = month.atDay(1)
            val leadingBlanks = (firstOfMonth.dayOfWeek.value + 6) % 7 // Monday-first grid, per the mockup
            val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
            // Always six rows (the most any month needs), so the sheet keeps one height
            // as the user pages between months.
            val cells: List<LocalDate?> = (List(leadingBlanks) { null } + days).let { it + List(42 - it.size) { null } }
            LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.padding(vertical = 8.dp)) {
                items(cells) { date ->
                    if (date == null) {
                        Box(Modifier.aspectRatio(1f))
                    } else {
                        val hasEntry = date in existingDates
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (date == today) {
                                        Modifier.border(1.dp, c.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { onPick(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    fontFamily = PlexSans,
                                    fontWeight = if (date == today) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 13.5.sp,
                                    color = if (date == today) c.accent else c.ink,
                                )
                                Box(
                                    Modifier
                                        .padding(top = 2.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(if (hasEntry) c.accent else androidx.compose.ui.graphics.Color.Transparent),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Square touch target, so the ripple reads as a square button rather than a tall text line. */
@Composable
private fun MonthArrow(glyph: String, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontFamily = PlexSans, fontSize = 20.sp, color = c.ink2)
    }
}
