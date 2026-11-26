plugins { java }
repositories { mavenCentral() }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(11) }
dependencies {
    compileOnly("com.google.devtools.ksp:symbol-processing-api:2.3.12")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
}
tasks.jar { archiveFileName.set("synthetic-ksp-processor.jar") }
