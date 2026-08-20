// build-logic must be included FIRST so its convention plugins exist before this
// build resolves plugins. A plain subproject could not do that.
pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Fail loudly if a module declares its own repositories: all dependency
    // resolution goes through this single, auditable block. NFR9 (no service
    // requiring an account) is only checkable if the dependency sources are.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    // Resolves the JDK toolchain from a known registry instead of scanning the machine.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

// `projects.core.model` instead of `project(":core:model")` — a renamed module then
// becomes a compile error rather than a runtime "project not found".
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Muraka"

include(":app")
include(":core:model")
include(":core:common")
include(":core:domain")
include(":core:network")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:sync")
include(":core:designsystem")
include(":core:testing")
