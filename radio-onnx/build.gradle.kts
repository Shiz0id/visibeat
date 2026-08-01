plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.visibeat.radio.onnx"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The model is already compressed weights; letting the packer deflate it
    // again costs install-time CPU and, more importantly, means the file cannot
    // be read straight out of the APK and has to be copied to disk first.
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation(project(":radio"))

    // Requires one online Gradle sync — see settings.gradle.kts.
    //
    // The full package pulls in every execution provider. If NNAPI is not being
    // used, `onnxruntime-mobile` is a much smaller artifact, at the cost of
    // supporting a reduced operator set that the model must be checked against.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
