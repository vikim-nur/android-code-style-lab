import java.util.Properties

plugins {
    kotlin("jvm") version "2.2.21"
    `java-gradle-plugin`
    `maven-publish`
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
// K2 Analysis API uses the runtime's native 2.4.10 compiler, NOT KGP 2.2.21.
// Wrapper plugin and ktlint remain compiled with KGP 2.2.21.
val typedCompiler by configurations.creating {
    isVisible = false
    isTransitive = false
}
dependencies {
    add(typedCompiler.name, "dev.detekt:detekt-cli:${qualityVersions.getProperty("detekt")}:all")
}
val typedSources = fileTree("../nur-detekt-rules") { include("**/*.kt") }
val typedClasses = layout.buildDirectory.dir("nativeArchitectureClasses")
val compileArchitectureRules by tasks.registering(JavaExec::class) {
    classpath = typedCompiler
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    inputs.files(typedSources)
    inputs.files(typedCompiler)
    outputs.dir(typedClasses)
    doFirst {
        delete(typedClasses)
        setArgs(listOf("-no-stdlib", "-no-reflect", "-classpath", typedCompiler.asPath,
            "-jvm-target", "17", "-d", typedClasses.get().asFile.absolutePath) +
            typedSources.files.sortedBy { it.path }.map { it.absolutePath })
    }
}
val architectureRulesJar by tasks.registering(Jar::class) {
    archiveFileName.set("nur-architecture-rules-${qualityVersions.getProperty("release")}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("rules"))
    dependsOn(compileArchitectureRules)
    from(typedClasses)
    from("../nur-detekt-rules") { include("META-INF/**") }
}
// Package the candidate profile directly from the shared config source.
sourceSets.named("main") { resources.exclude("quality/editorconfig") }
tasks.processResources {
    from(nurRulesJar) { into("quality") }
    from(architectureRulesJar) { into("quality") }
    from("../config/profiles/nur-reference.editorconfig") {
        into("quality")
        rename { "editorconfig" }
    }
}
gradlePlugin {
    plugins {
        create("nurQuality") {
            id = "kg.nur.quality"
            implementationClass = "kg.nur.quality.NurQualityPlugin"
        }
    }
}

// A task-owned filesystem repository for delivery tests, never a remote publication.
publishing {
    repositories {
        maven {
            name = "pilotLocal"
            url = layout.buildDirectory.dir("pilot-maven").get().asFile.toURI()
        }
    }
}
tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
