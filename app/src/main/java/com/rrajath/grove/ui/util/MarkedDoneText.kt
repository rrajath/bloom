package com.rrajath.grove.ui.util

import com.rrajath.grove.R
import com.rrajath.grove.org.formatOccurrence
import java.time.LocalDate

/**
 * The mark-done snackbar text: "Marked done", plus "Next occurrence Jun 5th" for a
 * recurring task ([next] is the date it moved to). The year is added
 * ("Jan 3rd, 2027") only when [next] falls in a different year than [today].
 */
fun markedDoneMessage(next: LocalDate?, today: LocalDate): UiText =
    if (next == null) UiText.res(R.string.snack_marked_done)
    else UiText.res(R.string.snack_marked_done_next, formatOccurrence(next, today))
