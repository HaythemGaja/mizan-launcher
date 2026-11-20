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
        versionCode = 2
        versionName = "1.1"
    }
    // Debug builds work on a fresh checkout. Existing installs can be updated
    // only with their original signing key; opt in through environment variables.
    val signingPath = System.getenv("SREEBREMOTE_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (signingPath != null) {
            create("provided") {
                storeFile = file(signingPath)
                storePassword = System.getenv("SREEBREMOTE_STOREPASS") ?: "android"
                keyAlias = System.getenv("SREEBREMOTE_KEY_ALIAS") ?: "sreebremote"
                keyPassword = System.getenv("SREEBREMOTE_KEYPASS") ?: "android"
            }
        }
    }
    buildTypes {
        debug {
            if (signingPath != null) signingConfig = signingConfigs.getByName("provided")
        }
        release {
            isMinifyEnabled = false
            if (signingPath != null) signingConfig = signingConfigs.getByName("provided")
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
    testImplementation("junit:junit:4.13.2")
    // Views only, framework Android + Kotlin stdlib. No Compose,
    // no androidx: this app is one connect screen + one control
    // screen, and it must stay light.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
