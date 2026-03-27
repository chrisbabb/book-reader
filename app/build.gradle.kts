import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load API keys from local.properties (gitignored, never committed)
val localProps = rootProject.file("local.properties")
val secrets = Properties().apply {
    if (localProps.exists()) load(localProps.inputStream())
}

android {
    namespace = "com.bookreader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bookreader.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "ANTHROPIC_API_KEY",
            "\"${secrets.getProperty("ANTHROPIC_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "OPENAI_API_KEY",
            "\"${secrets.getProperty("OPENAI_API_KEY", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit — on-device, no internet required
    implementation(libs.mlkit.text.recognition)
    implementation("com.google.mlkit:object-detection:17.0.2")

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // HTTP client for Claude and OpenAI APIs
    implementation(libs.okhttp)
}
