package com.rrajath.grove.capture

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DefaultTemplatesLocalizedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `english resources reproduce the built-in names`() {
        assertEquals(DefaultTemplates.all, DefaultTemplates.localized(context))
    }

    @Test
    @Config(qualifiers = "en-rXA")
    fun `names follow the UI language and only the names change`() {
        val localized = DefaultTemplates.localized(context)
        val byId = localized.associateBy { it.id }
        // en-XA is the accented pseudolocale: translated text differs from the English.
        assertEquals(false, byId.getValue("builtin-journal").name == "Journal Entry")
        assertEquals("TODO", byId.getValue("builtin-todo").name)
        assertEquals(
            DefaultTemplates.all.map { it.copy(name = "") },
            localized.map { it.copy(name = "") },
        )
    }
}
