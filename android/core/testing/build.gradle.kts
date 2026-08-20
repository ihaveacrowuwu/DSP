plugins {
    alias(libs.plugins.muraka.jvm.library)
}

// Test doubles shared between modules. Pure Kotlin, because the fakes implement domain
// interfaces and those cannot see Android either.
dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.domain)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
}
