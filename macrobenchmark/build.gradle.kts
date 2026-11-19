plugins {
    // AGP 9 has built-in Kotlin support — no separate kotlin.android plugin
    // (the same reason :app doesn't apply one).
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// Layer 4 of the test suite — Macrobenchmark + Baseline Profile.
//
// A `com.android.test` module: builds a standalone instrumentation APK that
// measures :app (installed separately). Nothing here ships in the app. The
// androidx.baselineprofile plugin derives the `benchmarkRelease` /
// `nonMinifiedRelease` variants that pair with :app's.
android {
    namespace = "com.rrajath.grove.macrobenchmark"
    compileSdk = 36

    defaultConfig {
        // Macrobenchmark's own floor is 24; match the app's 34.
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":app"
    // Self-instrumenting: the test APK does not instrument :app's process, so
    // :app can be a non-debuggable, minified, profileable build and the numbers
    // reflect something close to what ships.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.rules)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
}
