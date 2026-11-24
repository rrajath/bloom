package com.rrajath.grove.ui.util

import com.rrajath.grove.R
import com.rrajath.grove.org.OrgTags

/** The message shown for a rejected heading tag. */
fun OrgTags.Problem.text(): UiText = UiText.res(
    when (this) {
        OrgTags.Problem.EMPTY -> R.string.tag_error_empty
        OrgTags.Problem.WHITESPACE -> R.string.tag_error_spaces
        OrgTags.Problem.COLON -> R.string.tag_error_colons
        OrgTags.Problem.CHARSET -> R.string.tag_error_charset
    },
)
