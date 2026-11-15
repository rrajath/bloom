package com.rrajath.grove.settings

import com.rrajath.grove.vault.storage.VaultLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultLocationTest {

    private val uri = "content://com.android.externalstorage.documents/tree/primary%3Aorg"

    @Test
    fun `a SAF location round-trips as a tagged value`() {
        val encoded = VaultLocation.Saf(uri).encode()

        assertEquals("""{"type":"saf","treeUri":"$uri"}""", encoded)
        assertEquals(VaultLocation.Saf(uri), VaultLocation.decode(encoded))
    }

    @Test
    fun `malformed, blank or unknown-type values decode to nothing`() {
        assertNull(VaultLocation.decode(null))
        assertNull(VaultLocation.decode(""))
        assertNull(VaultLocation.decode("content://not/json"))
        assertNull(VaultLocation.decode("""{"type":"webdav","url":"https://x"}"""))
    }

    @Test
    fun `an install from before the location type migrates its plain tree URI`() {
        assertEquals(VaultLocation.Saf(uri), vaultLocationFrom(tagged = null, legacyTreeUri = uri))
    }

    @Test
    fun `the tagged value wins over a leftover plain URI`() {
        val tagged = VaultLocation.Saf("content://new/tree").encode()
        assertEquals(VaultLocation.Saf("content://new/tree"), vaultLocationFrom(tagged, legacyTreeUri = uri))
    }

    @Test
    fun `no stored value is no vault`() {
        assertNull(vaultLocationFrom(null, null))
        assertNull(vaultLocationFrom(null, ""))
    }

    @Test
    fun `settings expose the tree URI of a SAF location`() {
        assertEquals(uri, GroveSettings(vaultLocation = VaultLocation.Saf(uri)).vaultTreeUri)
        assertNull(GroveSettings().vaultTreeUri)
    }

    @Test
    fun `an export never carries the vault location`() {
        val json = SettingsSerialization.export(GroveSettings(vaultLocation = VaultLocation.Saf(uri)))
        assert(uri !in json && "vault_location" !in json && "treeUri" !in json) { json }
    }
}
