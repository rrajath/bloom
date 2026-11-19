package com.rrajath.grove.ui.screens

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rrajath.grove.ui.support.setGroveContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer-2 UI coverage for [OnboardingScreen].
 * The screen is
 * stateless — no ViewModel, no vault — so it renders directly with recorded
 * callbacks. The SAF folder picker result itself is a Layer-3 (Maestro)
 * concern; here we only assert the CTA opens it without prematurely firing
 * either callback.
 *
 * The screen is one `verticalScroll` Column, so on a short viewport (the
 * default CI emulator AVD) the actions sit below the fold — every action node
 * is reached with `performScrollTo()` before being asserted or clicked.
 *
 * Espresso-Intents stubs every outgoing intent so the real SAF `OpenDocumentTree`
 * activity (DocumentsUI) never launches. That keeps the host activity RESUMED
 * for the whole test: a real cross-process picker, torn down while the next
 * test's process is forking, gets that process SIGKILLed by ActivityManager
 * ("remove task") and surfaces as a spurious crash on CI's slow emulator.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        Intents.init()
        // Swallow every intent this screen might fire; nothing real launches.
        Intents.intending(anyIntent())
            .respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    private fun content(
        onDone: () -> Unit = {},
        onFolderPicked: (String) -> Unit = {},
    ) {
        composeRule.setGroveContent {
            OnboardingScreen(onDone = onDone, onFolderPicked = onFolderPicked)
        }
    }

    @Test
    fun rendersTheBrandCopyAndBothActions() {
        content()
        composeRule.onNodeWithText("Your org-mode notes, at home on your phone.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose a local folder").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("I'll set this up later").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingLaterSkipsOnboarding() {
        var done = false
        var picked: String? = null
        content(onDone = { done = true }, onFolderPicked = { picked = it })

        composeRule.onNodeWithText("I'll set this up later").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) { done }
        assertNull("folder was not picked", picked)
    }

    @Test
    fun tappingChooseFolderOpensThePickerWithoutFiringCallbacks() {
        var done = false
        var picked: String? = null
        content(onDone = { done = true }, onFolderPicked = { picked = it })

        composeRule.onNodeWithText("Choose a local folder").performScrollTo().performClick()
        composeRule.waitForIdle()

        // The CTA fires the SAF folder-picker intent...
        intended(hasAction(Intent.ACTION_OPEN_DOCUMENT_TREE))
        // ...but the stubbed RESULT_CANCELED means neither callback runs.
        assertEquals(false, done)
        assertNull(picked)
    }
}
