// `build-logic` is an **included build**, not a module of the app.
//
// It compiles the convention plugins in `:convention`, which the app's modules then
// apply. Being a separate build is what lets those plugins exist before the main
// build's plugin resolution happens — a plain subproject could not.

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    // Reuse the app's single version catalog rather than duplicating versions. This is
    // the whole point: AGP and Kotlin are pinned once, in one file, for both builds.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
