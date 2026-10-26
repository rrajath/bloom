package com.rrajath.grove.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The view picker's Agenda subtitle comes from a plural resource, so it needs real resources. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetTextRobolectricTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `agenda subtitle summarizes the days-ahead window`() {
        assertEquals("Overdue + next 7 days", agendaWindowSubtitle(resources, 7))
        assertEquals("Overdue + next 14 days", agendaWindowSubtitle(resources, 14))
    }

    @Test
    fun `agenda subtitle uses the singular for one day`() {
        assertEquals("Overdue + next 1 day", agendaWindowSubtitle(resources, 1))
    }

    @Test
    fun `a window below one day reads as one day`() {
        assertEquals("Overdue + next 1 day", agendaWindowSubtitle(resources, 0))
    }
}
