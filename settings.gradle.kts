pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VisiBeat"
include(":app")
include(":core-db")
include(":music-db")
include(":view-engine")
include(":music-ui")
include(":ingest")
project(":ingest").projectDir = file("music-db/ingest")
include(":musicbrainz")
include(":radio")
include(":radio-onnx")
