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
        versionCode = 48
        versionName = "3.22"
    }
    // Debug builds work on a fresh checkout. Existing installs can be updated
    // only with their original signing key; opt in through environment variables.
    val signingPath = System.getenv("MIZAN_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (signingPath != null) {
            create("provided") {
                storeFile = file(signingPath)
                storePassword = System.getenv("MIZAN_STOREPASS") ?: "android"
                keyAlias = System.getenv("MIZAN_KEY_ALIAS") ?: "mizanlauncher"
                keyPassword = System.getenv("MIZAN_KEYPASS") ?: "android"
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
    // Views only, framework Android + Kotlin stdlib. No Compose, no media3:
    // the whole point is a launcher that stays light on a slow TV box.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
