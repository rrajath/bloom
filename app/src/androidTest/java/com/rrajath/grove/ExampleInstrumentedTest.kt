package com.rrajath.grove

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test. The debug variant carries an
        // applicationIdSuffix so it can sit beside a release install, so the
        // package name is the base id plus the variant suffix.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "unexpected package ${appContext.packageName}",
            appContext.packageName.removeSuffix(".webdav") == "com.rrajath.grove",
        )
    }
}