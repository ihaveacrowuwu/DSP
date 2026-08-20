plugins {
    alias(libs.plugins.muraka.android.library)
    alias(libs.plugins.muraka.hilt)
}

android { namespace = "mv.muraka.core.data" }

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

    testImplementation(libs.bundles.unit.test)
    testImplementation(projects.core.testing)
}
