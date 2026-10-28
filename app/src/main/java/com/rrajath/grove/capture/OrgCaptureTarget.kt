package com.rrajath.grove.capture

/**
 * The Emacs org-capture `:target` (plus `:prepend` / `:tree-type`) equivalent
 * to [location] in [file], shown under the template editor's "Insert at"
 * options so org users can see what a template does in their own terms.
 */
fun orgCaptureTarget(file: String, location: TargetLocation): String {
    val f = orgString(file)
    fun olp(path: List<String>) = path.joinToString(" ") { orgString(it) }
    return when (location) {
        is TargetLocation.TopOfFile -> ":target (file $f) :prepend t"
        is TargetLocation.BottomOfFile -> ":target (file $f)"
        is TargetLocation.UnderHeading -> {
            val key = location.headingKey
            when {
                key != null -> ":target (id ${orgString(key)})"
                location.path.isNotEmpty() -> ":target (file+olp $f ${olp(location.path)})"
                location.title != null -> ":target (file+headline $f ${orgString(location.title)})"
                else -> ":target (file+olp $f)"
            }
        }
        is TargetLocation.Datetree -> {
            val target = if (location.path.isEmpty()) ":target (file+datetree $f)"
            else ":target (file+olp+datetree $f ${olp(location.path)})"
            target + when (location.treeType) {
                DatetreeType.DAY -> ""
                DatetreeType.WEEK -> " :tree-type week"
                DatetreeType.MONTH -> " :tree-type month"
            }
        }
    }
}

/** [s] as an Emacs Lisp string literal. */
private fun orgString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
