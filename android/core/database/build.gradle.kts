plugins {
    alias(libs.plugins.muraka.android.library)
    alias(libs.plugins.muraka.android.room)
    alias(libs.plugins.muraka.hilt)
}

android {
    namespace = "mv.muraka.core.database"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        // MigrationTestHelper reads the exported schema JSON from the APK's assets, so the
        // committed schemas/ directory is registered as an androidTest asset source.
        getByName("androidTest") { assets.srcDir(files("$projectDir/schemas")) }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)

    // Instrumented, not JVM: these tests exercise real SQLite, real WAL journalling and
    // real Room codegen — which is precisely where the durability guarantees live.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    // Explicit, unlike in :app where Espresso drags them in: without the runner the test
    // APK has no AndroidJUnitRunner and the instrumentation crashes before discovering a
    // test — reported by Gradle as "0 tests", which reads like a source-set problem.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
