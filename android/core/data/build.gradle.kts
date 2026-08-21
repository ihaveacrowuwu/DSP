plugins {
    alias(libs.plugins.muraka.android.library)
    alias(libs.plugins.muraka.hilt)
}

android {
    namespace = "mv.muraka.core.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

// The only module that depends on BOTH the domain interfaces and every concrete data
// source. That is precisely its job: it implements the former using the latter, and
// nothing above it needs to know which sources exist.
dependencies {
    api(projects.core.domain)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)
    // For the Bitmap.scale KTX extension used when downscaling before upload.
    implementation(libs.androidx.core.ktx)
    // Used directly by the sync engine for the cached-detail blob. Retrofit and OkHttp
    // arrive transitively through :core:network, which exposes them as `api` because
    // they appear in MurakaApi's own signatures.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.unit.test)
    testImplementation(projects.core.testing)

    // Instrumented, not JVM. The sync engine's collaborators are a Room database, a
    // Keystore-backed DataStore and the filesystem — all three are Android, and two of
    // them (PhotoStore, SessionTokenStore) are final classes with no interface, so
    // faking them would mean either a mocking library or opening production types for
    // the test's convenience. Running against the real ones is both less code and
    // better evidence: these tests answer "does the offline queue behave", and the
    // queue IS the database.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlinx.serialization.json)
}
