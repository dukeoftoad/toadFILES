plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.toadfiles.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.toadfiles.app"
        // v1.8.1: minimum raised from Android 8.0 (API 26) to Android 14 (API 34).
        minSdk = 34
        targetSdk = 36
        versionCode = 19
        versionName = "1.9.0"

        // Exposed to the About page so the Android-compatibility rows can never drift
        // from the values the build actually uses.
        buildConfigField("int", "MIN_SDK_VERSION", "34")
        buildConfigField("int", "TARGET_SDK_VERSION", "36")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is driven by env vars so CI can sign without the key ever
    // landing in git. Without them a local release build is simply unsigned.
    val ksFile: java.io.File? = System.getenv("TOADFILES_KEYSTORE_FILE")?.let(::File)
    val ksStorePassword: String = System.getenv("TOADFILES_KEYSTORE_PASSWORD").orEmpty()
    val ksKeyAlias: String = System.getenv("TOADFILES_KEY_ALIAS").orEmpty()
    val ksKeyPassword: String = System.getenv("TOADFILES_KEY_PASSWORD").orEmpty()
    val signingReady = ksFile != null && ksStorePassword.isNotEmpty() &&
        ksKeyAlias.isNotEmpty() && ksKeyPassword.isNotEmpty()

    signingConfigs {
        create("release") {
            storeFile = ksFile
            storePassword = ksStorePassword
            keyAlias = ksKeyAlias
            keyPassword = ksKeyPassword
            enableV1Signing = signingReady
            enableV2Signing = signingReady
            enableV3Signing = signingReady
            enableV4Signing = signingReady
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign release builds when the keystore env vars are present (CI),
            // stay unsigned otherwise.
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = false
    }
    testOptions {
        // Robolectric needs the merged resources (Compose UI tests, real strings/layouts).
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "1536m" }
        }
    }
}

// AGP 9 built-in Kotlin auto-aligns jvmTarget with compileOptions (Java 17).

// Bundle the repo changelog into the APK: the in-app What's New page parses this asset
// at runtime (com.toadfiles.app.ui.whatsnew.Changelog), so release notes live in exactly
// one place — CHANGELOG.md at the repo root — and can never drift from the app copy.
// The generated file is gitignored; never edit it by hand.
val syncChangelog = tasks.register<Copy>("syncChangelog") {
    from(rootProject.file("CHANGELOG.md")) {
        rename { "changelog.md" }
    }
    into("src/main/assets")
}
tasks.named("preBuild") { dependsOn(syncChangelog) }

// Robolectric requires a JDK whose FileDescriptor internals it knows — JDK 17 LTS is the
// safe runtime for unit tests (JDK 21.0.13+ changed internals and crashes the shadows).
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.video) // video thumbnails for the Categories/Recent grids
    implementation(libs.kotlinx.coroutines.core)
    // 7z read support (pure Java). ZIP stays on java.util.zip.
    implementation("org.apache.commons:commons-compress:1.27.1")
    // XZ for Java: commons-compress needs it for LZMA-based 7z entries at runtime.
    implementation("org.tukaani:xz:1.10")

    // ---- Local JVM unit tests (JUnit + Robolectric + Compose UI tests on the JVM) ----
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.espresso.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)

    // ---- Instrumented tests (device/emulator) ----
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
