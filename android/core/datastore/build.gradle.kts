plugins {
    alias(libs.plugins.muraka.android.library)
    alias(libs.plugins.muraka.hilt)
}

android {
    namespace = "mv.muraka.core.datastore"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

// Session tokens live here, encrypted with an AndroidKeyStore key. See
// KeystoreCipher.kt for why this is hand-rolled rather than androidx.security-crypto.
dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
