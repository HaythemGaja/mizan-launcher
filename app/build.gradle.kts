plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.calm.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.calm.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }
    // Pinned signing key (same pattern as Sreeb): the keystore file lives
    // only on the builder's machine, never in the repo. Builds run as root,
    // so the path must be absolute — user.home would resolve to /root.
    // CALM_KEYSTORE overrides the path when set.
    signingConfigs {
        create("pinned") {
            val keystorePath = System.getenv("CALM_KEYSTORE")?.takeIf { it.isNotBlank() }
                ?: "/home/hatch/workspace/.keystores/calm-debug.keystore"
            storeFile = file(keystorePath)
            storePassword = "calm-debug-pass"
            keyAlias = "calm"
            keyPassword = "calm-debug-pass"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("pinned")
        }
        release {
            signingConfig = signingConfigs.getByName("pinned")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// No third-party libraries: framework Views and the Kotlin stdlib only.
// Playback: Media3 ExoPlayer with HLS, the same engine IPTV apps use for live streams.
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
