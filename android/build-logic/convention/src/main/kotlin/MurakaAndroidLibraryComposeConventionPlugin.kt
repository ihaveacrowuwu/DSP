import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Adds Compose to an Android library. Applied *alongside*
 * [MurakaAndroidLibraryConventionPlugin], never instead of it.
 *
 * Separate from the base library plugin because `:core:model`, `:core:common` and
 * `:core:domain` must never see Compose - and a module that cannot import
 * `@Composable` cannot accidentally put UI in the domain.
 */
class MurakaAndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

            extensions.configure<LibraryExtension> {
                buildFeatures { compose = true }
            }

            dependencies {
                val bom = libs.findLibrary("androidx-compose-bom").get()
                add("implementation", platform(bom))
                add("androidTestImplementation", platform(bom))
                add("implementation", libs.findBundle("compose").get())
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            }

            // Material 3 Expressive is adopted project-wide (mobile-shared/design-language.md
            // asks for MotionScheme springs), so the opt-in lives here rather than as an
            // @OptIn on every file. It must be on the *Compose* plugin: a module without
            // material3 cannot resolve the marker.
            tasks.withType<KotlinCompile>().configureEach {
                compilerOptions {
                    freeCompilerArgs.add(
                        "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                    )
                }
            }
        }
    }
}
