package com.rrajath.grove.settings.backup

/** Writes the automatic-backup file into a chosen folder. [SafBackupWriter] in the app, a fake in tests. */
interface BackupWriter {
    /** Replace the backup file in the folder [treeUri] with [text]; a failure carries the cause. */
    suspend fun write(treeUri: String, text: String): Result<Unit>

    /** Whether the backup file is in [treeUri]. Throws when the folder can't be read. */
    suspend fun exists(treeUri: String): Boolean
}

/** Where [AutoBackupManager] records each write's outcome. [com.rrajath.grove.settings.SettingsRepository] in the app. */
interface AutoBackupStatusSink {
    /** Each applies only while [treeUri] is still the backup folder: a late write never paints a newer folder's status. */
    suspend fun recordAutoBackupSuccess(treeUri: String, at: Long, hash: String)
    suspend fun recordAutoBackupFailure(treeUri: String)
}
