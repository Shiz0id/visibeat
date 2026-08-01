plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.visibeat.app"
    compileSdk = 34

    // The model is already compressed weights. Deflating it again costs
    // install-time CPU to save nothing, and — more to the point — a compressed
    // asset cannot be read straight out of the APK, which matters because the
    // graph's weights live in the separate .onnx.data file beside it.
    androidResources {
        noCompress += listOf("onnx", "data")
    }

    defaultConfig {
        applicationId = "com.visibeat.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        // Pre-1.0 on purpose: this is a sideloaded beta, and the number a tester
        // reads in Settings should match the tag it was cut from.
        versionName = "0.1.0-beta.1"

        // ONNX Runtime ships a native library per ABI, and they are large: the
        // four together are 62 MB of this APK, of which x86 and x86_64 — 35 MB —
        // exist only for emulators. Phones are arm.
        //
        // This is the right filter for a sideloaded APK. Shipping through Play
        // as an App Bundle would split per-ABI automatically and this could be
        // dropped, but the bundle does nothing for a debug build on a cable.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":music-ui"))
    implementation(project(":ingest"))
    implementation(project(":view-engine"))
    implementation(project(":music-db"))
    implementation(project(":core-db"))
    implementation(project(":musicbrainz"))
    implementation(project(":radio"))
    implementation(project(":radio-onnx"))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.coil.compose)
    
    // Playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    
    // Color extraction
    implementation(libs.palette.ktx)
}
