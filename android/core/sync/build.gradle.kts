plugins {
    alias(libs.plugins.muraka.android.library)
    alias(libs.plugins.muraka.hilt)
}

android { namespace = "mv.muraka.core.sync" }

// Owns the drain loop: WorkManager scheduling, the retry curve and the reconciliation
// pass. Kept out of :core:data so the repositories stay testable without WorkManager.
dependencies {
    api(projects.core.domain)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.bundles.unit.test)
    testImplementation(projects.core.testing)
}
