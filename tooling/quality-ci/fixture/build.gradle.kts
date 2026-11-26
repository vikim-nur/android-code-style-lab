plugins {
    kotlin("jvm") version "2.2.21" apply false
    id("kg.nur.quality") version "@QUALITY_VERSION@"
}
allprojects {
    repositories { mavenCentral() }
}
