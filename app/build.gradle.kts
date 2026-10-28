import java.io.File
import java.security.KeyStore

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Derives `benchmarkRelease` / `nonMinifiedRelease` build types off `release`
    // and wires the profile produced by :macrobenchmark's BaselineProfileGenerator
    // into the release APK. Harmless while no profile exists yet.
    alias(libs.plugins.androidx.baselineprofile)
}

// versionName is the single source of truth for the app's version: a
// manually-bumped SemVer string read as-is from the `versionName` key in
// gradle.properties. versionCode is derived from it numerically —
// MAJOR * 10000 + MINOR * 100 + PATCH — so "1.2.3" becomes 10203. Minor and
// patch therefore each occupy two decimal digits and must stay within 0-99.
// Nothing is read from git or written back into the repo; scripts/release.sh
// bumps both keys in gradle.properties when cutting a release.
val manualVersionName = providers.gradleProperty("versionName").get()

val derivedVersionCode: Int = run {
    val segments = manualVersionName.trim().split(".")
    require(segments.size == 3) { "versionName '$manualVersionName' must be MAJOR.MINOR.PATCH" }
    val (major, minor, patch) = segments.map {
        it.toIntOrNull() ?: error("versionName '$manualVersionName' has a non-numeric segment: '$it'")
    }
    require(minor in 0..99 && patch in 0..99) {
        "versionName '$manualVersionName': minor and patch must each be 0-99 " +
            "(they occupy two decimal digits each in versionCode)"
    }
    major * 10000 + minor * 100 + patch
}

// gradle.properties also carries the derived number as a literal `versionCode`
// so F-Droid's checkupdates can read it with a regex (UpdateCheckData) instead
// of evaluating this script. It must always equal the derived value; the build
// fails on a mismatch rather than silently shipping either one.
val storedVersionCode: Int = run {
    val raw = providers.gradleProperty("versionCode").get()
    val stored = raw.trim().toIntOrNull() ?: error("versionCode '$raw' in gradle.properties is not a number")
    require(stored == derivedVersionCode) {
        "versionCode=$stored in gradle.properties doesn't match versionName $manualVersionName " +
            "(expected $derivedVersionCode). Use scripts/release.sh to bump versions."
    }
    stored
}

// Bundles the repo's CHANGELOG.md into the APK as a raw asset (read at runtime by the
// What's New modal) instead of hand-duplicating its content into a resource: this keeps
// the single source of truth in the root CHANGELOG.md, so it can't drift from what CI
// actually cuts into GitHub Releases. A plain Copy task's output is a File, but the
// Variant API's addGeneratedSourceDirectory needs a DirectoryProperty, hence this
// dedicated task instead of a stock Copy/Sync.
abstract class CopyChangelogTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    abstract val inputFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun run() {
        val dest = outputDir.get().asFile
        dest.mkdirs()
        inputFile.get().asFile.copyTo(File(dest, "CHANGELOG.md"), overwrite = true)
    }
}

// Copies the canonical `.org` test fixtures (src/testFixtures/resources/fixtures,
// also read by OrgFixtures on the test classpath) into an APK's assets so the
// test-vault hook (DebugTestVault) can seed a vault from identical content with
// no SAF picker. Wired only for the variants with BuildConfig.TEST_HOOKS on
// (see `testHookVariants` in androidComponents) — plain `release` never carries
// these. See internal/test-suite-03-e2e-maestro.md and -04-performance-macrobenchmark.md.
abstract class CopyTestFixturesTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    abstract val inputDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun run() {
        val dest = File(outputDir.get().asFile, "fixtures")
        dest.deleteRecursively()
        dest.mkdirs()
        inputDir.get().asFile.listFiles { f -> f.isFile && f.extension == "org" }
            ?.forEach { it.copyTo(File(dest, it.name), overwrite = true) }
    }
}

// Release signing comes from the environment (CI secrets). We validate the
// keystore and alias up front so a missing or misconfigured secret degrades to
// an unsigned release build instead of failing packaging, and a local
// `assembleRelease` (no env set) likewise stays unsigned, as before.
class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

val releaseSigning: ReleaseSigning? = run {
    val path = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() } ?: return@run null
    val storePassword = System.getenv("SIGNING_STORE_PASSWORD").orEmpty()
    val keyAlias = System.getenv("SIGNING_KEY_ALIAS").orEmpty()
    val keyPassword = System.getenv("SIGNING_KEY_PASSWORD").orEmpty()
    val store = file(path)
    fun disable(reason: String): ReleaseSigning? {
        logger.warn("Release signing disabled: $reason. Building an unsigned release APK.")
        return null
    }
    if (!store.exists()) return@run disable("keystore '$path' not found")
    if (keyAlias.isBlank()) return@run disable("SIGNING_KEY_ALIAS is empty")
    val keystore = listOf("PKCS12", "JKS").firstNotNullOfOrNull { type ->
        runCatching {
            KeyStore.getInstance(type).apply {
                store.inputStream().use { load(it, storePassword.toCharArray()) }
            }
        }.getOrNull()
    } ?: return@run disable("could not open keystore (wrong store password or unknown format)")
    if (!keystore.containsAlias(keyAlias)) return@run disable("alias '$keyAlias' not found in keystore")
    val keyUsable = runCatching {
        keystore.getKey(keyAlias, keyPassword.toCharArray()) != null
    }.getOrDefault(false)
    if (!keyUsable) return@run disable("wrong key password for alias '$keyAlias'")
    ReleaseSigning(store, storePassword, keyAlias, keyPassword)
}

android {
    namespace = "com.rrajath.grove"
    compileSdk {
        version = release(36)
    }
    // Pinned so every build environment strips prebuilt native libs (from
    // dependencies like androidx.datastore/androidx.sqlite-bundled) with the
    // same NDK, byte-for-byte — otherwise an unpinned/auto-selected NDK
    // differs between CI and F-Droid's build server, breaking F-Droid's
    // reproducible-build comparison against the CI-published APK even though
    // Grove has no native source of its own. Keep in sync with fdroiddata's
    // `ndk:` build entry for this app.
    ndkVersion = "27.3.13750724"

    // AGP embeds a "Dependency metadata" block in the APK Signing Block by
    // default (Play Console's dependency-transparency feature). F-Droid's
    // scanner rejects any such non-standard signing block, so it's disabled
    // here for both the APK and the AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.rrajath.grove"
        minSdk = 23
        targetSdk = 36
        versionCode = storedVersionCode
        versionName = manualVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Each instrumented test in its own process (see `execution` below), and
        // wipe app data (DataStore, files) between them so the Compose UI suite
        // is order-independent.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"

        // Gates the test-only launch hooks (DebugTestVault: a direct-directory
        // vault + fixture seeding from intent extras). Off by default so any
        // unlisted variant is safe; turned on per build type below for `debug`
        // and `benchmark`. `release` stays false — R8 then dead-strips the hook.
        buildConfigField("boolean", "TEST_HOOKS", "false")
    }

    signingConfigs {
        create("release") {
            // Wired only when the CI secrets resolve to a valid keystore + alias;
            // otherwise the config stays empty and the release builds unsigned.
            releaseSigning?.let {
                storeFile = it.storeFile
                storePassword = it.storePassword
                keyAlias = it.keyAlias
                keyPassword = it.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Distinct applicationId so a debug build from Android Studio can be
            // installed side-by-side with the CI-signed release build on the same device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "TEST_HOOKS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "TEST_HOOKS", "false")
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // `benchmarkRelease` (minified, profileable — what :macrobenchmark
        // measures) and `nonMinifiedRelease` (what generateBaselineProfile
        // collects from) are created by the androidx.baselineprofile plugin off
        // `release`. Both need the launch hooks on so the benchmark journeys can
        // reach a seeded Notebooks screen with no SAF picker — set below in
        // androidComponents (the plugin registers them after this block).
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // minSdk 23 puts java.time (LocalDate/Instant/DayOfWeek/DateTimeFormatter,
        // used throughout the org date-parsing/scheduling code) below its native
        // floor of API 26; desugaring backports it to API 21+ instead.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Shared test fakes/fixtures (FakeFileStore, FakeSettingsRepository, OrgFixtures,
    // TestVaultSeeder, in-memory Room helper) live in src/testFixtures and are
    // consumed by both the JVM (test/) and instrumented (androidTest/) suites.
    // See internal/test-suite-00-overview.md § Shared test infrastructure.
    testFixtures {
        enable = true
    }

    testOptions {
        unitTests {
            // Robolectric integration tests need real merged resources and
            // non-throwing stubs for un-shadowed android.* calls.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Day/month names follow the device locale (orgLocale()); pin it so
            // tests asserting "Mon"/"October" pass on any developer machine.
            all {
                it.systemProperty("user.language", "en")
                it.systemProperty("user.country", "US")
            }
        }
        // Run each instrumented test in an isolated process via AndroidX Test
        // Orchestrator — the Layer-2 Compose UI suite otherwise flakes on a
        // leaked Choreographer looper once ~30 test activities share one process.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    lint {
        // Version-availability nags, not defects: informational only, depend on a
        // network lookup, and go stale on their own. Bumping AGP/Kotlin/Compose/
        // libraries is a deliberate, separately-tested change, not a lint fix.
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "OldTargetApi",
        )
    }
}

composeCompiler {
    // Value types (domain models, java.time) that are immutable in practice but
    // the compiler can't verify. See internal/PERFORMANCE_AUDIT_2026-08-27.md #6.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose_stability.conf"))
}

androidComponents {
    // Variants that carry the test-only launch hooks (direct-directory vault +
    // fixture seeding): `debug` for Maestro, and the two release-shaped variants
    // the androidx.baselineprofile plugin derives for the :macrobenchmark module.
    val testHookVariants = setOf("debug", "benchmarkRelease", "nonMinifiedRelease")

    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val copyTask = tasks.register<CopyChangelogTask>("copy${variantName}ChangelogAsset") {
            inputFile.set(rootProject.file("CHANGELOG.md"))
            outputDir.set(layout.buildDirectory.dir("generated/assets/changelog/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTask) { it.outputDir }

        if (variant.name in testHookVariants) {
            // release's buildConfigField gave these `false` via inheritance.
            variant.buildConfigFields?.put(
                "TEST_HOOKS",
                com.android.build.api.variant.BuildConfigField("boolean", "true", "test launch hooks"),
            )
            val fixturesTask = tasks.register<CopyTestFixturesTask>("copy${variantName}TestFixtures") {
                inputDir.set(layout.projectDirectory.dir("src/testFixtures/resources/fixtures"))
                outputDir.set(layout.buildDirectory.dir("generated/assets/testfixtures/${variant.name}"))
            }
            variant.sources.assets?.addGeneratedSourceDirectory(fixturesTask) { it.outputDir }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.glance.appwidget)
    // Enables CompilationMode.Partial (baseline-profile) benchmarks and installs
    // a packaged baseline profile at app startup once one is generated.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.java.diff.utils)

    // Producer of the baseline profile packaged above (see the androidx.baselineprofile
    // plugin). `./gradlew :app:generateBaselineProfile` runs the generator on a
    // connected device and writes app/src/main/baselineProfiles/.
    baselineProfile(project(":macrobenchmark"))

    // testFixtures compiles against main only; fakes implement production
    // interfaces. androidx.test.core is `api` so the in-memory Room helper's
    // default ApplicationProvider context is visible to consumers.
    testFixturesApi(libs.androidx.test.core)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    // The Compose compiler plugin is applied to every Kotlin compilation in this
    // module, testFixtures included, so its runtime must be on that classpath
    // even though no fixture is a @Composable.
    testFixturesImplementation(platform(libs.androidx.compose.bom))
    testFixturesImplementation(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    // Desktop SQLite natives so BundledSQLiteDriver (and thus GroveDatabase +
    // FTS5) works under Robolectric on the host JVM. See internal/LEARNINGS.md
    // 2026-09-07.
    testRuntimeOnly(libs.androidx.sqlite.bundled.jvm)
    // A slice of the Layer-2 Compose UI tests also runs under Robolectric
    // (@GraphicsMode NATIVE) on the per-push JVM job — see
    // internal/test-suite-02-ui-compose.md § "Robolectric subset".
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(testFixtures(project(":app")))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(testFixtures(project(":app")))
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Print just the versionName so CI can tag releases from one source of
// truth: `./gradlew -q printVersionName` → "1.0.0". The value itself comes
// from gradle.properties, not from anything computed here.
tasks.register("printVersionName") {
    doLast { println(manualVersionName) }
}

// Print just the versionCode (`./gradlew -q printVersionCode` → "10203"): the
// `versionCode` from gradle.properties, already checked against versionName
// (MAJOR*10000 + MINOR*100 + PATCH), as stamped into the APK.
tasks.register("printVersionCode") {
    doLast { println(storedVersionCode) }
}
