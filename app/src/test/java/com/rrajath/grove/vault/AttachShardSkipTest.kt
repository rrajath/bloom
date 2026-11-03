package com.rrajath.grove.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachShardSkipTest {

    @Test
    fun `shard shapes`() {
        assertTrue(isAttachShard("3F"))
        assertTrue(isAttachShard("ab"))
        assertTrue(isAttachShard("202611"))
        assertTrue(isAttachShard("__"))
        assertFalse(isAttachShard("notes"))
        assertFalse(isAttachShard("2026AB"))
        assertFalse(isAttachShard("a"))
        assertFalse(isAttachShard("2026111"))
    }

    @Test
    fun `only shards directly inside a data folder are skipped`() {
        assertTrue(isSkippedVaultDir("3F", "data", skipAttachShards = true))
        assertTrue(isSkippedVaultDir("202611", "Work/data", skipAttachShards = true))
        assertTrue(isSkippedVaultDir("__", "data", skipAttachShards = true))
        assertFalse(isSkippedVaultDir("notes", "data", skipAttachShards = true))
        assertFalse(isSkippedVaultDir("3F", "", skipAttachShards = true))
        assertFalse(isSkippedVaultDir("3F", "metadata", skipAttachShards = true))
        assertFalse(isSkippedVaultDir("3F", "data/3F", skipAttachShards = true))
    }

    @Test
    fun `dot dirs are always skipped, shards only when enabled`() {
        assertTrue(isSkippedVaultDir(".stversions", "", skipAttachShards = false))
        assertTrue(isSkippedVaultDir(".git", "data", skipAttachShards = true))
        assertFalse(isSkippedVaultDir("3F", "data", skipAttachShards = false))
    }
}
