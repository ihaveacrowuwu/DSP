plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "mv.muraka"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "mv.muraka"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "mv.muraka.MurakaTestRunner"
    }

    // Only English is provided, so ship only English and keep the APK small.
    androidResources {
        localeFilters += listOf("en")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false

            // 10.0.2.2 is the host machine as seen from the Android emulator; the
            // emulator's own `localhost` is the emulated device. A physical phone on the
            // same Wi-Fi needs the machine's LAN IP instead — override with
            // `-PmurakaApiBase=http://192.168.x.x:8090/`.
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${providers.gradleProperty("murakaApiBase").getOrElse("http://10.0.2.2:8090/")}\"",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // HTTPS only. Cleartext is permitted in the debug network-security config and
            // nowhere else (NFR4), so a release build physically cannot talk to the dev
            // stack over plain HTTP.
            buildConfigField("String", "API_BASE_URL", "\"https://muraka.invalid/\"")

            // Debug signing so `assembleRelease` runs locally without a keystore. Replace
            // with a real signing config before any distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/LICENSE*",
                    "/META-INF/DEPENDENCIES",
                    "META-INF/*.kotlin_module",
                )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        htmlReport = true
        disable +=
            setOf(
                // Dependency currency is a reviewed decision recorded in
                // gradle/libs.versions.toml, not something an upstream release should be
                // able to turn into a red build.
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
                // Only English is shipped (androidResources.localeFilters).
                "MissingTranslation",
                // Lint suggests merging mipmap-anydpi-v26 into mipmap-anydpi because
                // minSdk is already 26. Doing so makes AAPT2 fail to resolve
                // @mipmap/ic_launcher at all — verified, not assumed. The -v26 qualifier
                // is also what AGP's own templates emit for adaptive icons.
                "ObsoleteSdkInt",
            )
    }
}

kotlin {
    // Pin the compiler JDK so the build is reproducible across machines. Bytecode still
    // targets 17 (see android.compileOptions) for Android compatibility.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").orNull.toBoolean())
        freeCompilerArgs.addAll(
            // Material 3 Expressive is adopted project-wide, so the opt-in belongs here
            // rather than as an @OptIn on every file that touches MotionScheme or an
            // emphasized type role. ExperimentalMaterial3Api is deliberately NOT opted
            // into globally — those stay per use site so the signal stays visible.
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-Xconsistent-data-class-copy-visibility",
            "-Xannotation-default-target=param-property",
        )
    }
}

dependencies {
    // ── Project modules ────────────────────────────────────────────────────
    // `api`, not `implementation`: domain models appear in this module's own public API
    // (view-model state, composable parameters), so consumers need them transitively.
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.domain)
    api(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.sync)

    // ── Platform / BOM ─────────────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // ── Core & lifecycle ───────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)

    // ── UI (Compose) ───────────────────────────────────────────────────────
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── Dependency injection ───────────────────────────────────────────────
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // ── Capture & position ─────────────────────────────────────────────────
    implementation(libs.bundles.camerax)
    implementation(libs.androidx.exifinterface)
    implementation(libs.play.services.location)

    // ── Background work ────────────────────────────────────────────────────
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // ── Images ─────────────────────────────────────────────────────────────
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    // ── Desugaring ─────────────────────────────────────────────────────────
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // ── Unit tests (JVM, no device needed) ─────────────────────────────────
    testImplementation(libs.bundles.unit.test)
    testImplementation(projects.core.testing)

    // ── Instrumented tests (device / emulator) ─────────────────────────────
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
