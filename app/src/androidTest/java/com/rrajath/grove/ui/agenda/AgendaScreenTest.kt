package com.rrajath.grove.ui.agenda

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rrajath.grove.ui.support.ScreenTestEnv
import com.rrajath.grove.ui.support.setGroveContent
import com.rrajath.grove.ui.vault.NoteRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Layer-2 UI coverage for [AgendaScreen].
 *
 * A heading with no TODO keyword is an event, not a task, whether it is placed
 * on the day by a bare active timestamp or by SCHEDULED: the agenda renders it
 * with no "mark done" checkbox, while a real TODO task on the same day keeps one.
 */
@RunWith(AndroidJUnit4::class)
class AgendaScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var env: ScreenTestEnv
    private val vm by lazy { env.agendaViewModel() }

    private val today: LocalDate = LocalDate.now()

    private fun orgDate(d: LocalDate): String =
        "<$d ${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}>"

    private val vault = """
        #+TITLE: Agenda events fixture

        * TODO Ship the release
        SCHEDULED: ${orgDate(today)}
        * Team offsite
        ${orgDate(today)}
        * Dentist appointment
        SCHEDULED: ${orgDate(today)}
    """.trimIndent() + "\n"

    @Before
    fun setUp() {
        env = ScreenTestEnv(vaultFiles = mapOf("events.org" to vault))
    }

    @After
    fun tearDown() {
        env.close()
    }

    private fun content(onOpenNote: (NoteRef) -> Unit = {}) {
        composeRule.setGroveContent {
            AgendaScreen(onBack = {}, onOpenNote = onOpenNote, viewModel = vm)
        }
    }

    @Test
    fun onlyKeywordedTasksGetACheckbox() {
        content()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Team offsite").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Ship the release").assertIsDisplayed()
        composeRule.onNodeWithText("Team offsite").assertIsDisplayed()
        composeRule.onNodeWithText("Dentist appointment").assertIsDisplayed()

        // One checkbox: the TODO task's. Neither the bare-timestamp event nor the
        // keyword-less scheduled heading is a task, so neither gets one.
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("agenda_checkbox", useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }
}
