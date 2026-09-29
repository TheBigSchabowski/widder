plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release key lives outside the project. The same key must sign every version, otherwise
// Android refuses to install an update over the old one.
val keyDir = File(System.getProperty("user.home"), ".android")
val releaseKey = File(keyDir, "widder-release.jks")
val releasePass = File(keyDir, "widder-release.pass")

android {
    namespace = "widder.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.privat.widder"
        minSdk = 26
        targetSdk = 36
        versionCode = (property("app.versionCode") as String).toInt()
        versionName = property("app.version") as String
    }

    signingConfigs {
        if (releaseKey.exists() && releasePass.exists()) {
            create("release") {
                val pass = releasePass.readText().trim()
                storeFile = releaseKey
                storePassword = pass
                keyAlias = "widder"
                keyPassword = pass
            }
        }
    }

    buildTypes {
        release {
            // Without an own key the release APK is signed with the debug key, so it installs anyway.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
}
