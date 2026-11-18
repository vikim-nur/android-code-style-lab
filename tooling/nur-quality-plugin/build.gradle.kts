import java.util.Properties

plugins {
    kotlin("jvm") version "2.2.21"
    `java-gradle-plugin`
}

val qualityVersions = Properties().apply {
    file("src/main/resources/quality/versions.properties").inputStream().use { load(it) }
}
group = "kg.nur.quality"
version = qualityVersions.getProperty("release")
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

// The plugin and the rules share one source repository; do not maintain a copied JAR.
val nurRules by sourceSets.creating {
    resources.srcDir("../nur-ktlint-rules/src/main/resources")
}
kotlin.sourceSets.named("nurRules") {
    kotlin.srcDir("../nur-ktlint-rules/src/main/kotlin")
}
dependencies {
    add(nurRules.compileOnlyConfigurationName,
        "com.pinterest.ktlint:ktlint-cli:${qualityVersions.getProperty("ktlint")}:all") {
        isTransitive = false
    }
}
val nurRulesJar by tasks.registering(Jar::class) {
    archiveFileName.set("nur-style-rules-${qualityVersions.getProperty("release")}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("rules"))
    from(nurRules.output)
}
tasks.processResources {
    from(nurRulesJar) { into("quality") }
}
gradlePlugin {
    plugins {
        create("nurQuality") {
            id = "kg.nur.quality"
            implementationClass = "kg.nur.quality.NurQualityPlugin"
        }
    }
}
