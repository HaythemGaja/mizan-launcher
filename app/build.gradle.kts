plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mizan.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mizan.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 39
        versionName = "3.16"
    }
    signingConfigs {
        // Pinned debug keystore in persistent workspace storage. The default
        // ~/.android/debug.keystore lives under /root (ephemeral) and is wiped
        // on VM replacement -> new signing key -> "App not installed" on
        // update. Absolute path: the Gradle daemon runs as root, so a
        // user.home-relative path would resolve to /root and fail.
        create("pinnedDebug") {
            // The release/update build must keep ONE signing key or the
            // TV refuses the update ("App not installed"). The default
            // is the original build-VM path; set MIZAN_KEYSTORE to point
            // a checkout at its own copy (see .gitignore).
            val keystorePath = System.getenv("MIZAN_KEYSTORE")?.takeIf { it.isNotBlank() }
                ?: "/home/hatch/workspace/.keystores/mizanlauncher-debug.keystore"
            storeFile = file(keystorePath)
            storePassword = "android"
            keyAlias = "mizanlauncher"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("pinnedDebug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pinnedDebug")
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

dependencies {
    // Views only, framework Android + Kotlin stdlib. No Compose, no media3:
    // the whole point is a launcher that stays light on a slow TV box.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
