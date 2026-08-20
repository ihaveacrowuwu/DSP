import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * A **pure Kotlin/JVM** library module. Applied by `:core:model`, `:core:common`,
 * `:core:domain` and `:core:testing`.
 *
 * This is the load-bearing plugin of the whole modularisation. A module using it has
 * **no Android SDK on its compile classpath at all**, so:
 *
 * ```kotlin
 * import androidx.room.Entity    // ← does not resolve. Compile error.
 * import android.content.Context // ← does not resolve. Compile error.
 * ```
 *
 * The architecture rule "the domain imports nothing platform-specific" stops being a
 * convention policed by review and becomes a fact about the build.
 */
class MurakaJvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("java-library")
                apply("org.jetbrains.kotlin.jvm")
            }

            extensions.configure<JavaPluginExtension> {
                // Toolchain 21 matches :app, so no second JDK is downloaded.
                toolchain.languageVersion.set(JavaLanguageVersion.of(JDK_VERSION))

                // Java must emit the SAME bytecode version as Kotlin below. Setting only
                // the toolchain makes `compileJava` target 21 while `compileKotlin` targets
                // 17, and Gradle rejects the mismatch with "Inconsistent JVM-target
                // compatibility". 17 is the target because :app consumes this module.
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            tasks.withType<KotlinCompile>().configureEach {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    allWarningsAsErrors.set(
                        providers.gradleProperty("warningsAsErrors").orNull.toBoolean(),
                    )
                    freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
                }
            }
        }
    }

    private companion object {
        const val JDK_VERSION = 21
    }
}
