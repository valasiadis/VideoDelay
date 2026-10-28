plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

// Uses a JDK 21 toolchain (the system Java here is a JRE without javac).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Release signing comes from the environment (set by the GitHub release workflow).
// Without it, release builds fall back to the debug key so they still install locally.
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE_FILE")

android {
    namespace = "space.valasiadis.videodelay"
    compileSdk = 37

    defaultConfig {
        applicationId = "space.valasiadis.videodelay"
        // Pixel 10 Pro XL ships with Android 16; keep a little headroom for older Pixels.
        minSdk = 33
        targetSdk = 37
        // Set by the release workflow from the git tag (v1.2.3 -> "1.2.3", 10203).
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Installs next to the release app instead of colliding with it.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    // Don't embed Google's encrypted dependency report in the APK (only useful to the Play Store).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
