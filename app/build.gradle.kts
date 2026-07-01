plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read version + signing info from environment variables (CI injects these).
// Local builds fall back to the defaults below.
val versionNameFromEnv: String? = System.getenv("VERSION_NAME")
val versionCodeFromEnv: String? = System.getenv("VERSION_CODE")
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val keyAlias: String? = System.getenv("ANDROID_KEY_ALIAS")
val keyPassword: String? = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "com.faqxd.livesub.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.faqxd.livesub.android"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeFromEnv?.toIntOrNull() ?: 1
        versionName = versionNameFromEnv ?: "1.0.0"
    }

    signingConfigs {
        // Only register the release signing config when a keystore is
        // provided (e.g. via CI secrets). Otherwise `assembleRelease` will
        // produce an unsigned APK, and users should use `assembleDebug`.
        if (!keystorePath.isNullOrEmpty() &&
            !keystorePassword.isNullOrEmpty() &&
            !keyAlias.isNullOrEmpty() &&
            !keyPassword.isNullOrEmpty()
        ) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAlias
                keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only sign if the keystore env vars were set.
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Material Components
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20240303")
}
