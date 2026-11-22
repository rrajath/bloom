package com.rrajath.grove.ui.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rrajath.grove.R
import com.rrajath.grove.testing.TestGroveApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGroveApplication::class, sdk = [34])
class UiTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `string resource without and with arguments`() {
        assertEquals(context.getString(R.string.app_name), UiText.res(R.string.app_name).resolve(context))
        assertEquals("Folder from * Trip", UiText.res(R.string.attach_folder_from, "* Trip").resolve(context))
    }

    @Test
    fun `plural picks the form and uses the count as the default argument`() {
        assertEquals("1 filter active", UiText.plural(R.plurals.search_filters_active_count, 1).resolve(context))
        assertEquals("3 filters active", UiText.plural(R.plurals.search_filters_active_count, 3).resolve(context))
    }

    @Test
    fun `nested UiText arguments are resolved first`() {
        val nested = UiText.res(R.string.attach_folder_from, UiText.res(R.string.app_name))
        assertEquals("Folder from ${context.getString(R.string.app_name)}", nested.resolve(context))
    }

    @Test
    fun `raw text passes through`() {
        assertEquals("inbox.org", UiText.raw("inbox.org").resolve(context))
    }
}
