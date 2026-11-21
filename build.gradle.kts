// Top-level build file. Plugin versions are managed in gradle/libs.versions.toml.
// AGP 9 provides built-in Kotlin support — no separate kotlin-android plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
