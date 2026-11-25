package com.rrajath.grove.settings.backup

/** Writes the automatic-backup file into a chosen folder. [SafBackupWriter] in the app, a fake in tests. */
interface BackupWriter {
    /** Replace the backup file in the folder [treeUri] with [text]; a failure carries the cause. */
    suspend fun write(treeUri: String, text: String): Result<Unit>
}

/** Where [AutoBackupManager] records each write's outcome. [com.rrajath.grove.settings.SettingsRepository] in the app. */
interface AutoBackupStatusSink {
    suspend fun recordAutoBackupSuccess(at: Long, hash: String)
    suspend fun recordAutoBackupFailure()
}
