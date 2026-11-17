package com.rrajath.grove.vault

import com.rrajath.grove.settings.GroveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultProviderTest {
    private val local = "content://com.android.externalstorage.documents/tree/primary%3ASync%2Forg"
    private val seafile = "content://com.seafile.seadroid2.documents/tree/abc%3A%2Fbloom"
    private val nextcloud = "content://org.nextcloud.documents/tree/1%2Fnotes"

    @Test
    fun `authority is parsed from tree uris`() {
        assertEquals(VaultProvider.LOCAL_STORAGE_AUTHORITY, VaultProvider.authorityOf(local))
        assertEquals("com.seafile.seadroid2.documents", VaultProvider.authorityOf(seafile))
        assertNull(VaultProvider.authorityOf("file:///sdcard/org"))
        assertNull(VaultProvider.authorityOf("content://"))
    }

    @Test
    fun `only the device's own storage counts as local`() {
        assertFalse(VaultProvider.isNonLocal(local))
        assertFalse(VaultProvider.isNonLocal(null))
        assertTrue(VaultProvider.isNonLocal(seafile))
        assertTrue(VaultProvider.isNonLocal(nextcloud))
    }

    @Test
    fun `dialog shows once per non-local folder after onboarding`() {
        val base = GroveSettings(onboardingDone = true, vaultTreeUri = seafile)
        assertTrue(VaultProvider.shouldShowWarningDialog(base))
        assertFalse(VaultProvider.shouldShowWarningDialog(base.copy(onboardingDone = false)))
        assertFalse(VaultProvider.shouldShowWarningDialog(base.copy(providerWarningAckedUri = seafile)))
        // Answered for one cloud folder, then a different one is picked: warn again.
        assertTrue(VaultProvider.shouldShowWarningDialog(base.copy(vaultTreeUri = nextcloud, providerWarningAckedUri = seafile)))
        assertFalse(VaultProvider.shouldShowWarningDialog(base.copy(vaultTreeUri = local)))
        assertFalse(VaultProvider.shouldShowWarningDialog(base.copy(vaultTreeUri = null)))
    }

    @Test
    fun `banner shows for a non-local folder until dismissed for it`() {
        val base = GroveSettings(vaultTreeUri = seafile)
        assertTrue(VaultProvider.shouldShowBanner(base))
        assertFalse(VaultProvider.shouldShowBanner(base.copy(providerBannerDismissedUri = seafile)))
        assertTrue(VaultProvider.shouldShowBanner(base.copy(vaultTreeUri = nextcloud, providerBannerDismissedUri = seafile)))
        assertFalse(VaultProvider.shouldShowBanner(base.copy(vaultTreeUri = local)))
    }
}
