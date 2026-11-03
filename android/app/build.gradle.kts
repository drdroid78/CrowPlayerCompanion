plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.crowplayer.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.crowplayer.companion"
        minSdk = 26
        targetSdk = 32
        versionCode = 6
        versionName = "0.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
