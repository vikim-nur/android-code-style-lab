pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "android-code-style-lab"
include("ktlint-rules")
project(":ktlint-rules").projectDir = file("tooling/nur-ktlint-rules")
