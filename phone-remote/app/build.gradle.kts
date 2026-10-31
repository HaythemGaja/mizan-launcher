plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mizan.launcherremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mizan.launcherremote"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        // Pinned keystore in persistent workspace storage (house
        // pattern, same as the TV launcher): the default
        // ~/.android/debug.keystore lives under /root (ephemeral)
        // and is wiped on VM replacement -> new signing key ->
        // "App not installed" on update. Absolute path: the
        // Gradle daemon runs as root, so a user.home-relative
        // path would resolve to /root and fail. The keystore
        // file itself must NEVER enter the repo.
        create("pinnedDebug") {
            val keystorePath = System.getenv("SREEBREMOTE_KEYSTORE")?.takeIf { it.isNotBlank() }
                ?: "/home/hatch/workspace/.keystores/sreebremote-debug.keystore"
            storeFile = file(keystorePath)
            storePassword = System.getenv("SREEBREMOTE_STOREPASS") ?: "android"
            keyAlias = "sreebremote"
            keyPassword = System.getenv("SREEBREMOTE_KEYPASS") ?: "android"
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
    // Views only, framework Android + Kotlin stdlib. No Compose,
    // no androidx: this app is one connect screen + one control
    // screen, and it must stay light.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
