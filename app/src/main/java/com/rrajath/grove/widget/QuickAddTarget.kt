package com.rrajath.grove.widget

import com.rrajath.grove.settings.GroveSettings

/**
 * Where the widget quick-add composer saves a task: a file plus an optional
 * heading path inside it (empty = bottom of the file). Set in Settings § Widget;
 * until then it follows the file of Settings § Sharing's "Shared content target"
 * (never its heading), which is
 * what the composer always used before it had a target of its own.
 */
data class QuickAddTarget(val fileName: String, val headingPath: List<String>) {

    /** "tasks › Work › Inbox": shown in the composer and on the Settings § Widget row. */
    val label: String get() = (listOf(fileName.removeSuffix(".org")) + headingPath).joinToString(" › ")

    companion object {
        fun from(settings: GroveSettings): QuickAddTarget {
            val file = settings.quickAddTargetFile
                ?: return QuickAddTarget(resolveShareTargetFile(settings), emptyList())
            return QuickAddTarget(file, splitPath(settings.quickAddHeadingPath))
        }

        private fun splitPath(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

        /**
         * Settings § Sharing's "Shared content target", resolved exactly like
         * [com.rrajath.grove.ui.AppViewModel.consumeSharedContent] does for a
         * shared link/text.
         */
        private fun resolveShareTargetFile(settings: GroveSettings): String {
            val target = settings.shareTargetFile.trim().ifBlank { GroveSettings.DEFAULT_SHARE_TARGET }
            return if (target.endsWith(".org")) target else "$target.org"
        }
    }
}
