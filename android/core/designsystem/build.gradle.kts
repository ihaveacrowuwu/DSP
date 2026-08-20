plugins {
    alias(libs.plugins.muraka.android.library)
    alias(libs.plugins.muraka.android.library.compose)
}

android { namespace = "mv.muraka.core.designsystem" }

// Depends on :core:model so components can take a Condition or a Prediction directly,
// and on :core:common for ApiError. Deliberately NOT on :core:domain — the design
// system renders values, it does not call repositories.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
}
