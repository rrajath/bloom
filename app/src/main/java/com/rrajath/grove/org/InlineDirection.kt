package com.rrajath.grove.org

/**
 * Text direction of org inline text whose math fragments are drawn as typeset formulas.
 *
 * A formula's LaTeX source is full of strong left-to-right letters (`$x$`, `\alpha`), but
 * once typeset it says nothing about the language of the sentence around it. So a Persian
 * item that starts with a formula must still read right-to-left. The text engine's own
 * "first strong character" rule can't know that, because the source stays in the string as
 * the formula placeholder's alternate text; these helpers decide from the text with the
 * formulas left out.
 */
object InlineDirection {

    /**
     * Whether the first strong character outside the formulas is right-to-left. Text with none is LTR.
     * [drawn] says which math tokens are typeset; one that isn't is shown as its source, which counts as text.
     */
    fun startsRtl(tokens: List<InlineToken>, drawn: (InlineToken) -> Boolean = ::isMath): Boolean =
        firstStrong(shown(tokens, drawn)) == true

    /**
     * The direction every line of [tokens] agrees on (true = RTL, false = LTR), or null when
     * no line has a strong character outside its formulas or the lines disagree, in which case
     * the text engine should decide each line itself.
     */
    fun uniformRtl(tokens: List<InlineToken>, drawn: (InlineToken) -> Boolean = ::isMath): Boolean? {
        var result: Boolean? = null
        for (line in shown(tokens, drawn).split('\n')) {
            val rtl = firstStrong(line) ?: continue
            if (result != null && result != rtl) return null
            result = rtl
        }
        return result
    }

    /** The text as displayed: link labels rather than targets, and a space where a formula is. */
    private fun shown(tokens: List<InlineToken>, drawn: (InlineToken) -> Boolean): String =
        tokens.joinToString("") { if (drawn(it)) " " else it.text }

    private fun isMath(token: InlineToken): Boolean = token.type == InlineType.MATH

    private fun firstStrong(text: CharSequence): Boolean? {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
            i += Character.charCount(cp)
        }
        return null
    }
}
