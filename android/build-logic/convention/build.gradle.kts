plugins {
    // Lets us write Gradle plugins in Kotlin with the same DSL the build scripts use.
    `kotlin-dsl`
}

group = "mv.muraka.buildlogic"

// Must match the bytecode target of the main build. A mismatch produces "class file
// has wrong version" errors that point nowhere useful.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly, not implementation: these plugins are already on the classpath of
    // the build that applies our conventions. Bundling them risks two AGP versions
    // loaded at once.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

// Each convention plugin gets an id that modules reference as
// `alias(libs.plugins.muraka.…)`. Registering them here makes those aliases resolvable.
gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "muraka.jvm.library"
            implementationClass = "MurakaJvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "muraka.android.library"
            implementationClass = "MurakaAndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "muraka.android.library.compose"
            implementationClass = "MurakaAndroidLibraryComposeConventionPlugin"
        }
        register("hilt") {
            id = "muraka.hilt"
            implementationClass = "MurakaHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "muraka.android.room"
            implementationClass = "MurakaAndroidRoomConventionPlugin"
        }
    }
}
