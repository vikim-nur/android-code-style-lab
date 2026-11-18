package kg.nur.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import java.io.File

/** Root-only prototype: isolated CLI processes avoid mixing the engines' Kotlin runtimes. */
class NurQualityPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "Apply kg.nur.quality only to the root project" }
        val root = project.projectDir
        ManagedQualityFiles.sync(root.toPath())
        val versions = ManagedQualityFiles.versions()
        val ktlint = project.configurations.create("nurKtlint") { it.isVisible = false }
        val detekt = project.configurations.create("nurDetekt") { it.isVisible = false }
        val compose = project.configurations.create("nurComposeRules") { it.isVisible = false; it.isTransitive = false }
        project.dependencies.add(ktlint.name, "com.pinterest.ktlint:ktlint-cli:${versions.getProperty("ktlint")}:all")
        project.dependencies.add(detekt.name, "io.gitlab.arturbosch.detekt:detekt-cli:${versions.getProperty("detekt")}:all")
        for (artifact in listOf("detekt", "common-detekt")) {
            project.dependencies.add(compose.name, "io.nlopez.compose.rules:$artifact:${versions.getProperty("composeRules")}")
        }
        ktlint.isTransitive = false
        detekt.isTransitive = false
        val sources = project.fileTree(root) {
            it.include("**/src/**/*.kt", "*.kt")
            it.exclude("**/build/**", "**/.gradle/**", "**/.quality/**", "**/.git/**")
        }
        val rules = File(root, ".quality/rules/nur-style-rules-${versions.getProperty("release")}.jar")
        val reportDir = project.layout.buildDirectory.dir("reports/quality")
        project.tasks.register("qualitySync") {
            it.group = "quality"
            it.description = "Restore managed IDE and quality configuration; also performed on plugin application."
            it.doLast { ManagedQualityFiles.sync(root.toPath()) }
        }
        fun formatter(name: String, format: Boolean) = project.tasks.register(name, JavaExec::class.java) { task ->
            task.group = "quality"
            task.description = if (format) "Apply the shared ktlint formatter." else "Check formatting without changing sources."
            task.classpath = ktlint
            task.mainClass.set("com.pinterest.ktlint.Main")
            task.workingDir(root)
            task.maxHeapSize = "512m"
            task.inputs.files(sources).withPropertyName("sources")
            task.inputs.files(project.fileTree(root) { it.include("**/.editorconfig"); it.exclude("**/build/**", "**/.gradle/**", "**/.quality/**") }).withPropertyName("editorConfigs")
            task.inputs.file(rules).withPropertyName("customRules")
            val marker = reportDir.map { it.file("$name.ok") }
            if (!format) task.outputs.file(marker)
            task.onlyIf { !sources.isEmpty }
            task.doFirst {
                marker.get().asFile.delete()
                task.setArgs(buildList {
                    add("--ruleset=${rules.absolutePath}")
                    if (format) add("--format")
                    add("--log-level=none")
                    addAll(sources.files.sortedBy { it.path }.map { it.relativeTo(root).invariantSeparatorsPath })
                })
            }
            if (!format) task.doLast {
                marker.get().asFile.apply { parentFile.mkdirs(); writeText("passed\n") }
            }
        }
        val check = formatter("qualityFormatCheck", false)
        formatter("qualityFormat", true)
        val analysis = project.tasks.register("qualityDetekt", JavaExec::class.java) { task ->
            task.group = "quality"
            task.description = "Run syntax-based Detekt and Compose rules, without detekt-formatting or autocorrect."
            task.classpath = detekt
            task.mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
            task.workingDir(root)
            task.maxHeapSize = "512m"
            task.inputs.files(sources).withPropertyName("sources")
            task.inputs.file(File(root, ".quality/detekt.yml")).withPropertyName("config")
            task.inputs.files(compose).withPropertyName("composeRules")
            val report = reportDir.map { it.file("detekt.xml") }
            task.outputs.file(report)
            task.onlyIf { !sources.isEmpty }
            task.doFirst {
                report.get().asFile.parentFile.mkdirs()
                task.setArgs(listOf("--input", sources.files.sortedBy { it.path }.joinToString(",") { it.absolutePath },
                    "--config", File(root, ".quality/detekt.yml").absolutePath,
                    "--plugins", compose.files.sortedBy { it.path }.joinToString(",") { it.absolutePath },
                    "--report", "checkstyle:${report.get().asFile.absolutePath}", "--base-path", root.absolutePath))
            }
        }
        project.tasks.register("qualityCheck") {
            it.group = "quality"
            it.description = "Check formatting and run Detekt; suitable as an explicit CI command."
            it.dependsOn(check, analysis)
        }
    }
}
