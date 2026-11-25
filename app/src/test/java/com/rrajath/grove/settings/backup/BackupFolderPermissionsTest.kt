package com.rrajath.grove.settings.backup

import com.rrajath.grove.settings.GroveSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFolderPermissionsTest {

    private val vault = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Forg"
    private val other = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FBloom"

    @Test
    fun `the old folder is never released when it is the vault`() {
        assertFalse(BackupFolderPermissions.shouldRelease(vault, GroveSettings(vaultTreeUri = vault)))
    }

    @Test
    fun `the vault check reads the current vault, which may have moved to the old backup folder`() {
        // Backup was picked while the vault was elsewhere; the vault later switched to the old backup folder.
        val now = GroveSettings(vaultTreeUri = other)
        assertFalse(BackupFolderPermissions.shouldRelease(other, now))
    }

    @Test
    fun `differently encoded URIs for the vault still count as the vault`() {
        val decoded = "content://com.android.externalstorage.documents/tree/primary:Documents/org"
        val upperScheme = "CONTENT://com.android.externalstorage.documents/tree/primary%3aDocuments%2forg"
        assertFalse(BackupFolderPermissions.shouldRelease(decoded, GroveSettings(vaultTreeUri = vault)))
        assertFalse(BackupFolderPermissions.shouldRelease(upperScheme, GroveSettings(vaultTreeUri = vault)))
    }

    @Test
    fun `an unrelated old folder is released`() {
        assertTrue(BackupFolderPermissions.shouldRelease(other, GroveSettings(vaultTreeUri = vault)))
    }

    @Test
    fun `with no vault set an unrelated old folder is released`() {
        assertTrue(BackupFolderPermissions.shouldRelease(other, GroveSettings(vaultTreeUri = null)))
    }

    @Test
    fun `unknown settings mean no release`() {
        assertFalse(BackupFolderPermissions.shouldRelease(other, null))
    }

    @Test
    fun `the folder still used as the backup folder is not released`() {
        assertFalse(BackupFolderPermissions.shouldRelease(other, GroveSettings(vaultTreeUri = vault, autoBackupTreeUri = other)))
    }
}
