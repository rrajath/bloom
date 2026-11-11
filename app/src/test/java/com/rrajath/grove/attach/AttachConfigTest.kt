package com.rrajath.grove.attach

import org.junit.Assert.assertEquals
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

    @Test fun `forFolder sets the id dir`() {
        assertEquals("attach/", AttachConfig.forFolder("attach/").idDir)
        assertEquals(AttachConfig.Default, AttachConfig.forFolder("data/"))
    }
}
