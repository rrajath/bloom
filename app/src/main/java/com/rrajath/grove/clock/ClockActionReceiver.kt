package com.rrajath.grove.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.rrajath.grove.GroveApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * The clock notification's (and the clock widget's) Clock out / Cancel, run
 * headlessly through [ClockService]. The notification itself is never touched
 * here: it follows the index, so it goes away once the write is reindexed, and
 * stays when the write didn't happen (the clock was closed elsewhere first, or
 * the vault never came up), leaving the user something to retry.
 */
class ClockActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clock = clockFrom(intent) ?: return
        val app = context.applicationContext as GroveApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                val result = when (intent.action) {
                    ACTION_CLOCK_OUT -> app.clockService.clockOut(clock)
                    ACTION_CANCEL -> app.clockService.cancel(clock)
                    else -> null
                }
                if (result == ClockResult.NotFound) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "That clock was already stopped", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CLOCK_OUT = "com.rrajath.grove.action.CLOCK_OUT"
        const val ACTION_CANCEL = "com.rrajath.grove.action.CLOCK_CANCEL"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_LINE = "line"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_START = "start"

        fun intent(context: Context, action: String, clock: RunningClock): Intent =
            Intent(context, ClockActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_FILE, clock.fileName)
                .putExtra(EXTRA_LINE, clock.lineIndex)
                .putExtra(EXTRA_TITLE, clock.title)
                .putExtra(EXTRA_START, clock.start.toString())

        private fun clockFrom(intent: Intent): RunningClock? {
            val file = intent.getStringExtra(EXTRA_FILE) ?: return null
            val start = intent.getStringExtra(EXTRA_START)
                ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
            return RunningClock(file, intent.getIntExtra(EXTRA_LINE, -1), intent.getStringExtra(EXTRA_TITLE).orEmpty(), null, start)
        }
    }
}
