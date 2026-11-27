// ZyraJarvis: project-wide plugin versions live here.
// Mirrors Brandon's leica-looks-android setup (AGP 8.6.1 + Kotlin 2.0.20
// + Gradle 8.9), which is the combination his GitHub Actions builds use.
//
// CHANGE-ME: to upgrade the build, bump these versions and the Gradle
// version in gradle/wrapper/gradle-wrapper.properties together.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
