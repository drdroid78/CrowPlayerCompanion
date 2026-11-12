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
        versionCode = 12
        versionName = "0.6.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        // Direct-install HarmonyOS companion: retain target 32 until a separate target migration.
        // Keep the Play publishing requirement visible without blocking this device build.
        warning += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.withType<Test>().configureEach {
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

