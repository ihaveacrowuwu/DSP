plugins {
    alias(libs.plugins.muraka.android.library)
    alias(libs.plugins.muraka.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "mv.muraka.core.network"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.domain)
    implementation(projects.core.datastore)
    implementation(libs.bundles.networking)
    implementation(libs.kotlinx.coroutines.android)

    // `api`, not `implementation`: MurakaApi's own signatures return `retrofit2.Response`
    // and take `okhttp3.MultipartBody.Part`, so every consumer needs those types to call
    // it at all. Hiding them behind `implementation` compiles here and fails in :core:data.
    api(libs.retrofit)
    api(libs.okhttp)

    testImplementation(libs.bundles.unit.test)
    // The authenticator and error mapper are tested against a real HTTP server rather
    // than a mocked OkHttp: mocking the client would test OkHttp, not our code.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
}
