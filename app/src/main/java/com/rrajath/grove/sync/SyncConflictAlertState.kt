package com.rrajath.grove.sync

import android.content.Context
import androidx.core.content.edit

/**
 * Tracks whether the current unresolved-conflict episode has already been
 * alerted on, so [SyncManager.notifyConflicts] can edge-trigger on the
 * 0 -> 1+ transition instead of re-posting on every sync pass. Backed by its
 * own SharedPreferences file (not [com.rrajath.grove.settings.SettingsRepository]
 * -- this isn't a user setting and shouldn't appear in Import/Export) so it
 * survives the process dying between sync passes (periodic WorkManager runs
 * in a fresh process).
 */
class SyncConflictAlertState(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)

    fun setActive(active: Boolean) {
        prefs.edit { putBoolean(KEY_ACTIVE, active) }
    }

    companion object {
        private const val PREFS_NAME = "grove_sync_state"
        private const val KEY_ACTIVE = "conflict_alert_active"
    }
}
