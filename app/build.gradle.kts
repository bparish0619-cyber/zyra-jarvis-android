// ZyraJarvis app module.
//
// What this app is: a native Kotlin client for the OpenJarvis backend
// ("Zyra on OpenJarvis") running on Brandon's UGREEN NAS. One Activity,
// no WebView, no third-party libraries: Kotlin stdlib + Android framework
// only (networking is raw HttpURLConnection, JSON is org.json).
//
// Signing: debug builds only for now. CI builds assembleDebug, which is
// signed with the standard Android debug key and installs fine for
// personal use. Add a release signing config here if that ever changes.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.northbrand.zyrajarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.northbrand.zyrajarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3" // CHANGE-ME on each release; bump versionCode too
    }

    buildTypes {
        release {
            // No shrinking: the app is tiny and shrinking buys nothing.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
