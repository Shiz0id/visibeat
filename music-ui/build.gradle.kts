plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.visibeat.musicui"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8" // Approximate for Kotlin 1.9.22
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // For RadioOrigin only — the seed-intent enum that travels on
    // PlaybackBinding. No ONNX here: that lives in :radio-onnx.
    implementation(project(":radio"))
    implementation(project(":view-engine"))
    implementation(project(":music-db"))
    implementation(project(":core-db"))
    
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.ui.icons.extended)
    implementation(libs.material3)
    
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(libs.room.ktx)
    implementation(libs.coil.compose)
    
    // Playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // Color extraction
    implementation(libs.palette.ktx)

    debugImplementation(libs.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(project(":view-engine"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    // Deliberately no compose-ui-test/Espresso: its UiController reflects into
    // InputManager.getInstance, which Android 16 removed. The render test drives a
    // ComposeView directly instead.
    androidTestImplementation(libs.androidx.test.core)
    debugImplementation(libs.ui.test.manifest)
}
