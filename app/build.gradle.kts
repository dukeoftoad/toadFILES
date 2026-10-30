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
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.6.1"

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
