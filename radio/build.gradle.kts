plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.visibeat.radio"
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
}

dependencies {
    // The embedding table and its DAO live with the rest of the schema, the
    // same way ArtistImageEntity does while musicbrainz holds the work.
    implementation(project(":music-db"))
    implementation(project(":core-db"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // ONNX Runtime is deliberately NOT here. It lives in :radio-onnx behind the
    // EmbeddingModel interface, so this module — the DSP, the vector index and
    // the queue rules — compiles and unit-tests with no native dependency and
    // no model file. That is what lets the parts worth testing be tested.

    testImplementation(libs.junit)
}
