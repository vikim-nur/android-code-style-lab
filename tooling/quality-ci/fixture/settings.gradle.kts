pluginManagement {
    repositories {
        maven { url = uri("@PILOT_REPOSITORY@") }
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "quality-ci-synthetic"
include(":contracts", ":ui", ":domain")
