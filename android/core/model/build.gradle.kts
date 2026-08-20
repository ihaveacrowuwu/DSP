plugins {
    alias(libs.plugins.muraka.jvm.library)
}

// No dependencies. None. That is the point of this module.
//
// It is a pure Kotlin/JVM library, so the Android SDK, Room, Retrofit and Compose are
// all absent from its compile classpath. A domain model physically cannot import a
// framework type, and the wire enums here (`Condition`, `SightingStatus`, …) are the
// same strings the Go API speaks — see mobile-shared/integration.md.
//
// If you want to add a dependency here, the type you are adding probably does not
// belong in the domain.

dependencies {
    testImplementation(libs.junit)
}
