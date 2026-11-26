package com.rrajath.grove.ui.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.rrajath.grove.search.Snippets
import com.rrajath.grove.ui.theme.GroveColors

/** The search-match highlight: `amber` SemiBold on `amberSoft`, shared by Search and Settings search. */
internal fun searchMatchStyle(c: GroveColors): SpanStyle =
    SpanStyle(color = c.amber, background = c.amberSoft, fontWeight = FontWeight.SemiBold)

/** [text] with every case-insensitive occurrence of [terms] in the [searchMatchStyle] highlight. */
internal fun highlightMatches(text: String, terms: List<String>, c: GroveColors): AnnotatedString =
    buildAnnotatedString {
        append(text)
        Snippets.highlightRanges(text, terms).forEach { range ->
            addStyle(searchMatchStyle(c), range.first, (range.last + 1).coerceAtMost(text.length))
        }
    }
