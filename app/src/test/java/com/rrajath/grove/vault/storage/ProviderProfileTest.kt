package com.rrajath.grove.vault.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProfileTest {

    @Test
    fun `profiles are found by authority, debug builds included`() {
        assertEquals(ProviderProfiles.LOCAL, ProviderProfiles.forAuthority("com.android.externalstorage.documents"))
        assertEquals(ProviderProfiles.SEAFILE, ProviderProfiles.forAuthority("com.seafile.seadroid2.documents"))
        assertEquals(ProviderProfiles.SEAFILE, ProviderProfiles.forAuthority("com.seafile.seadroid2.debug.documents"))
        assertEquals(ProviderProfiles.NEXTCLOUD, ProviderProfiles.forAuthority("org.nextcloud.documents"))
    }

    @Test
    fun `an unknown or missing authority gets the default profile`() {
        assertEquals(ProviderProfiles.DEFAULT, ProviderProfiles.forAuthority("com.example.documents"))
        assertEquals(ProviderProfiles.DEFAULT, ProviderProfiles.forAuthority(null))
    }

    @Test
    fun `no two profiles claim the same authority`() {
        val claimed = ProviderProfiles.all.flatMap { it.authorities }
        assertEquals(claimed.size, claimed.toSet().size)
    }

    @Test
    fun `only local storage has path ids and case-insensitive names`() {
        assertTrue(ProviderProfiles.LOCAL.pathDocIds && ProviderProfiles.LOCAL.caseInsensitiveNames)
        (ProviderProfiles.all - ProviderProfiles.LOCAL + ProviderProfiles.DEFAULT).forEach {
            assertTrue(it.id, !it.pathDocIds && !it.caseInsensitiveNames)
        }
    }

    /** Profiles hold only what flags can't say: a field duplicating a COLUMN_FLAGS capability would drift from it. */
    @Test
    fun `no profile field duplicates a discoverable capability`() {
        fun fieldsOf(c: Class<*>) = c.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && '$' !in it.name }
            .map { it.name.lowercase() }
        val discoverable = fieldsOf(StorageCapabilities::class.java).toSet()
        val profileFields = fieldsOf(ProviderProfile::class.java)
        assertTrue("found no capability fields; the filter is wrong", discoverable.size >= 5)

        val clashes = profileFields.filter { field ->
            field in discoverable || field.startsWith("supports") || field.startsWith("can")
        }
        assertTrue("ProviderProfile declares flag-discoverable fields: $clashes", clashes.isEmpty())
    }
}
