plugins {
    alias(libs.plugins.muraka.jvm.library)
}

// Pure Kotlin. ApiError, DispatcherProvider, the UUIDv7 generator and the clock all
// have to be usable from :core:domain, which cannot see Android — so neither can this.
dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
