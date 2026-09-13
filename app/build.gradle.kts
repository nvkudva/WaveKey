import com.android.build.api.variant.ApplicationVariant
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
}

/**
 * WaveKey: which architectures to build.
 *
 * A phone needs one. Building four (plus the universal APK that is a copy of
 * all of them) is three quarters of the native compile thrown away, so the
 * phone build passes `-Pabi=arm64-v8a`. It stays opt-in because the emulator
 * QA suite runs on x86_64 and the default has to keep working for it.
 */
val abiProperty = findProperty("abi") as String?

/** True when the build was narrowed with -Pabi, i.e. it targets one device. */
val abiNarrowed: Boolean = abiProperty != null

val buildAbis: List<String> = abiProperty
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wavekey.keyboard" // WaveKey: rebrand; namespace stays helium314.keyboard.* for upstream rebaseability
        minSdk = 24
        targetSdk = 36
        // WaveKey's own numbering, not HeliBoard's. The fork carries upstream
        // 4.1 as its base (see README), but what a user installs and what the
        // release page names is this.
        versionCode = 10000
        versionName = "1.0-beta"
        ndk {
            abiFilters.clear()
            // Left empty when -Pabi narrowed the build: AGP rejects an ABI named
            // in both abiFilters and the split filters ("Conflicting
            // configuration"), and the split alone already decides what is built.
            if (!abiNarrowed) abiFilters.addAll(buildAbis)
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        // WaveKey: the emulator UI QA suite (app/src/androidTest)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // WaveKey: release builds must be installable. The keystore lives
    // outside the repo; without it (CI, a fresh clone) the release build still
    // works and comes out unsigned, exactly as upstream's does.
    signingConfigs {
        create("wavekey") {
            // The keystore moved with the name; the old path still works so a
            // machine that has not caught up keeps signing. The passwords have
            // no default: an unsigned APK is uninstallable, so a missing
            // variable has to stop the build rather than produce one.
            val home = System.getProperty("user.home")
            val store = file("$home/.wavekey/release.jks")
                .takeIf { it.exists() } ?: file("$home/.supervoiceboard/release.jks")
            if (store.exists()) {
                storeFile = store
                val wantsRelease = gradle.startParameter.taskNames.any { it.contains("Release") }
                for (name in listOf("WAVEKEY_STORE_PASSWORD", "WAVEKEY_KEY_PASSWORD")) {
                    if (wantsRelease && System.getenv(name).isNullOrEmpty()) {
                        error("$name is not set, and $store cannot be opened without it")
                    }
                }
                storePassword = System.getenv("WAVEKEY_STORE_PASSWORD")
                keyAlias = System.getenv("WAVEKEY_KEY_ALIAS") ?: "wavekey"
                keyPassword = System.getenv("WAVEKEY_KEY_PASSWORD")
            }
        }
    }

    // WaveKey: both test suites run against debugNoMinify — "debug" here is
    // minified, and R8 renames the very things the instrumented tests look for.
    //
    // AGP 9 made this choose the unit tests too, not just the instrumented ones,
    // so there is exactly one variant with tests now. HeliBoard's `runTests`
    // build type existed only to be named by the three tests that skip on CI;
    // they name this one instead.
    testBuildType = "debugNoMinify"

    buildTypes {
        release {
            isMinifyEnabled = true
            // WaveKey: safe-mode shrinking (the default), so resources
            // reached by name at runtime survive; 117 locale folders and the
            // whole Compose resource set ship in every one of the five ABI APKs.
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            // WaveKey: sign when the local keystore is present
            signingConfigs.getByName("wavekey").storeFile?.let {
                signingConfig = signingConfigs.getByName("wavekey")
            }
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("debugNoMinify") { // for faster builds in IDE
            matchingFallbacks += "debug"
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    // WaveKey: ABI splits mean several outputs per variant,
                    // so the ABI goes in the name; the universal APK has none.
                    val abi = output.filters
                        .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                        ?.identifier
                    val suffix = if (abi == null) "universal" else abi
                    output.outputFileName =
                        "WaveKey_${defaultConfig.versionName}-${variant.buildType}-$suffix.apk"
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    // WaveKey: the ASR and refiner runtimes bring ~31 MB of native code
    // per ABI, which turns a 21 MB universal APK into a 93 MB one. Release builds
    // are split per ABI so a phone downloads one architecture, not four; the
    // universal APK is still produced for anyone who wants it (W6.2).
    splits {
        abi {
            isEnable = true
            reset()
            include(*buildAbis.toTypedArray())
            // A universal APK next to a single-ABI build is the same file twice.
            isUniversalApk = !abiNarrowed
        }
    }

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Kept on under minSdk 24 for the java.time and stream APIs that are
        // still above the floor, and because turning it off is a silent change
        // in what compiles rather than a loud one.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        target {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
        // Without this Gradle prints only "First failure:", so a red lint run says
        // nothing about the other sixty-odd errors and the report is only reachable
        // as a CI artifact.
        textReport = true
        // Not file("stdout"): AGP 8 takes that literally and writes a file called
        // "stdout" in the module root. The CI step prints this path on failure.
        textOutput = file("build/reports/lint-results-debug.txt")
        // The fork inherited ~100 locale files from HeliBoard and adds its own
        // strings untranslated, so every new string is an error in every locale.
        // That is a known state of the fork, not a defect that should stop a
        // build; it stays a warning so it is still visible in the report.
        warning += "MissingTranslation"
    }
}

dependencies {
    // WaveKey: the voice layer and the out-of-process refiner
    implementation(project(":core"))
    implementation(project(":voice"))
    implementation(project(":llm"))

    // androidx
    implementation("androidx.core:core-ktx:1.18.0") // 1.19.0 wants compileSdk 37
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.11.01")) // newer wants compileSdk 37
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8") // 2.10 wants compileSdk 37
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")

    // WaveKey: on-device UI QA. Compose for the settings surface,
    // UiAutomator for the keyboard itself, which lives in another window.
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.11.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
