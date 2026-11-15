package com.rrajath.grove.testing

import com.rrajath.grove.vault.FileStore
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FakeFileStore] must override every [FileStore] member in its own source,
 * defaults included. A default it inherits compiles to a bridge that does
 * `invokespecial FileStore.member`; D8 desugars that default out of FileStore in
 * the app APK (minSdk 23) but leaves the bridge in the instrumented-test APK
 * pointing at the now-abstract interface method, so the call throws
 * AbstractMethodError on the emulator while every JVM test passes.
 *
 * Reflection can't tell that bridge from a real override (both are methods
 * declared on FakeFileStore), so this reads the fake's source. It runs on the
 * JVM, so a missing override fails on every push, not only in the nightly
 * ui-tests job.
 */
class FakeFileStoreOverridesTest {

    @Test
    fun overridesEveryFileStoreMemberInSource() {
        // Gradle runs unit tests with the module directory (app/) as the working dir.
        val source = File("src/testFixtures/java/com/rrajath/grove/testing/FakeFileStore.kt").readText()

        val members = FileStore::class.java.declaredMethods
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
            .map { it.name }
            .toSortedSet()
        assertTrue("found no FileStore members; the filter is wrong", members.size > 10)

        val missing = members.filterNot { name ->
            Regex("""override\s+(suspend\s+)?fun\s+$name\s*\(""").containsMatchIn(source)
        }

        assertTrue(
            "FakeFileStore must explicitly override these FileStore members " +
                "(see the note in FakeFileStore): $missing",
            missing.isEmpty(),
        )
    }
}
