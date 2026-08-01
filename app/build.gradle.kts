plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.codvika.voiceassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.codvika.voiceassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":whisper"))
    implementation(project(":llama"))
    // Official prebuilt sherpa-onnx AAR, fetched by scripts/fetch_models.sh
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Encrypts the optional cloud API key at rest (Settings.kt).
    implementation("androidx.security:security-crypto:1.0.0")
}
