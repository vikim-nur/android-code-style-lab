plugins { kotlin("jvm") version "2.2.21" }
group = "kg.nur.quality"
version = "0.1.0-prototype"
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
dependencies {
    compileOnly("com.pinterest.ktlint:ktlint-cli:1.8.0:all") { isTransitive = false }
}
