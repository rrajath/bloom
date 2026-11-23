package com.rrajath.grove.ui.attach

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.grove

/**
 * Folder paths in attachment popups: PlexMono in `accent`, a touch smaller since
 * Mono runs wide. Heading names stay PlexSans SemiBold, so the two read apart.
 */
@Composable
internal fun folderSpan(): SpanStyle = SpanStyle(fontFamily = PlexMono, color = MaterialTheme.grove.accent, fontSize = 0.95.em)

/** [template] (a raw string resource) with its `%1$s` replaced by [arg] in [style]. */
internal fun withStyledArg(template: String, arg: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    val at = template.indexOf(ARG)
    if (at < 0) {
        append(template)
        return@buildAnnotatedString
    }
    append(template.substring(0, at))
    withStyle(style) { append(arg) }
    append(template.substring(at + ARG.length))
}

private const val ARG = "%1\$s"

/** One argument for [withStyledArgs]: [text] drawn in [style], or plain when it's null. */
internal class StyledArg(val text: String, val style: SpanStyle? = null)

/**
 * [template] (a raw string or plurals resource) with each `%N$s` / `%N$d` replaced
 * by `args[N - 1]`, drawn in that argument's style. Lets a translated sentence keep
 * a monospace folder path wherever the translation puts it.
 */
internal fun withStyledArgs(template: String, vararg args: StyledArg): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in PLACEHOLDER.findAll(template)) {
        append(template.substring(last, m.range.first))
        val arg = args.getOrNull(m.groupValues[1].toInt() - 1)
        when {
            arg == null -> append(m.value)
            arg.style == null -> append(arg.text)
            else -> withStyle(arg.style) { append(arg.text) }
        }
        last = m.range.last + 1
    }
    append(template.substring(last))
}

private val PLACEHOLDER = Regex("""%(\d+)\$[sd]""")
