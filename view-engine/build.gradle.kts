plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.visibeat.viewengine"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

// Export the schema so migrations can be written against Room's own DDL rather
// than hand-guessed. Room validates the post-migration schema on open and throws
// if it differs by so much as an index name.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":music-db"))
    implementation(project(":core-db"))
    implementation(libs.core.ktx)
    // Runtime only — no Compose compiler here, no UI. This exists so the row
    // types below can be annotated @Immutable. Without stability metadata the
    // Compose compiler assumes the worst for every type crossing out of this
    // module, which made 59 of 107 composables in the app non-skippable.
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    // The query engine has pure, device-independent logic worth pinning down —
    // bucket windowing above all, which was silently returning the wrong end.
    testImplementation(libs.junit)
}
