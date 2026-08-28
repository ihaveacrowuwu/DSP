import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * Hilt for a module that declares `@Module`/`@InstallIn` bindings or `@HiltViewModel`s.
 *
 * Applied per module rather than once at the app: KSP must run in every module that has
 * annotations to process, and a module without bindings should not pay the
 * annotation-processing cost. `:app` still applies `dagger.hilt.android.plugin` itself
 * only the application module assembles the final component.
 */
class MurakaHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

            dependencies {
                add("implementation", libs.findLibrary("hilt-android").get())
                add("ksp", libs.findLibrary("hilt-compiler").get())
            }
        }
    }
}
