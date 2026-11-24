package com.rrajath.grove.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachConfigTest {

    @Test fun `rootFor resolves against the org file's directory`() {
        assertEquals("Notes/data", AttachConfig.Default.rootFor("Notes/travel.org"))
        assertEquals("data", AttachConfig.Default.rootFor("travel.org"))
        assertEquals("Notes/attach", AttachConfig(idDir = "attach/").rootFor("Notes/travel.org"))
        assertEquals("Notes/media/files", AttachConfig(idDir = "media/files/").rootFor("Notes/travel.org"))
        assertEquals(".attach", AttachConfig(idDir = ".attach/").rootFor("travel.org"))
    }

    @Test fun `attachDirLabel follows the config`() {
        assertEquals("Notes/data/", attachDirLabel("Notes/travel.org", AttachConfig.Default))
        assertEquals("Notes/attach/", attachDirLabel("Notes/travel.org", AttachConfig(idDir = "attach/")))
        assertEquals("media/files/", attachDirLabel("travel.org", AttachConfig(idDir = "media/files/")))
    }

    @Test fun `vault root folder is the same for every org file`() {
        val config = AttachConfig(idDir = "/attach/")
        assertTrue(config.isVaultRoot)
        assertFalse(AttachConfig(idDir = "attach/").isVaultRoot)
        assertEquals("attach", config.rootFor("inbox.org"))
        assertEquals("attach", config.rootFor("projects/work.org"))
        assertEquals("media/files", AttachConfig(idDir = "/media/files/").rootFor("a/b/c.org"))
    }

    @Test fun `attachDirLabel marks a vault root folder with a leading slash`() {
        assertEquals("/attach/", attachDirLabel("projects/work.org", AttachConfig(idDir = "/attach/")))
        assertEquals("/attach/", attachDirLabel("inbox.org", AttachConfig(idDir = "/attach/")))
        assertEquals("/media/files/", attachDirLabel("x.org", AttachConfig(idDir = "/media/files/")))
    }

    @Test fun `forFolder sets the id dir`() {
        assertEquals("attach/", AttachConfig.forFolder("attach/").idDir)
        assertEquals(AttachConfig.Default, AttachConfig.forFolder("data/"))
        assertEquals("/attach/", AttachConfig.forFolder("/attach/").idDir)
    }
}
