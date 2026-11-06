package com.rrajath.grove.attach

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the attach sheet's English copy; every other test resolves it with getString. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AttachStringsRobolectricTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `english copy matches the design`() {
        val c = context
        assertEquals("Show attachments in:", c.getString(R.string.attach_scope_label))
        assertEquals("This heading", c.getString(R.string.attach_scope_heading))
        assertEquals("This file", c.getString(R.string.attach_scope_file))
        assertEquals("Vault", c.getString(R.string.attach_scope_vault))
        assertEquals("Load more", c.getString(R.string.attach_load_more))
        assertEquals("No attachments yet.", c.getString(R.string.attach_empty_heading))
        assertEquals("No attachments in this file.", c.getString(R.string.attach_empty_file))
        assertEquals("No attachments in the vault.", c.getString(R.string.attach_empty_vault))
        assertEquals("Linked photo.jpg", c.getString(R.string.attach_linked, "photo.jpg"))
        assertEquals("Linked photo.jpg from B", c.getString(R.string.attach_linked_from, "photo.jpg", "B"))
        assertEquals("Can't link a file name with [ or ]", c.getString(R.string.attach_link_bracket_refused))
        assertEquals("Can't link a file name with ::", c.getString(R.string.attach_link_colons_refused))
    }

    @Test fun `scope labels point at their resources`() {
        assertEquals(R.string.attach_scope_heading, AttachScope.HEADING.labelRes)
        assertEquals(R.string.attach_scope_file, AttachScope.FILE.labelRes)
        assertEquals(R.string.attach_scope_vault, AttachScope.VAULT.labelRes)
    }
}
