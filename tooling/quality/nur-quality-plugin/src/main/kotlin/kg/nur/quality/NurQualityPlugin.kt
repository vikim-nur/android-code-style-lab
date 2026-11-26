package kg.nur.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import java.io.File

open class NurQualityExtension(project: Project) {
    /** Supply ONE module/variant's complete source set and compile classpath. */
    val architectureClasspath: ConfigurableFileCollection = project.objects.fileCollection()
    val architectureSources: ConfigurableFileCollection = project.objects.fileCollection()
    val architectureJvmTarget: Property<String> = project.objects.property(String::class.java).convention("17")
    /** Only needed for an ambiguous flavored Android project, keyed by full project path. */
    val architectureVariants: MapProperty<String, String> = project.objects.mapProperty(String::class.java, String::class.java).convention(emptyMap())
}

/** Root-only prototype: isolated CLI processes avoid mixing the engines' Kotlin runtimes. */
class NurQualityPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "Apply kg.nur.quality only to the root project" }
        val root = project.projectDir
        ManagedQualityFiles.sync(root.toPath())
        val versions = ManagedQualityFiles.versions()
        val extension = project.extensions.create("nurQuality", NurQualityExtension::class.java, project)
        val ktlint = project.configurations.create("nurKtlint") { it.isVisible = false }
        val detekt = project.configurations.create("nurDetekt") { it.isVisible = false }
        val compose = project.configurations.create("nurComposeRules") { it.isVisible = false; it.isTransitive = false }
        project.dependencies.add(ktlint.name, "com.pinterest.ktlint:ktlint-cli:${versions.getProperty("ktlint")}:all")
        project.dependencies.add(detekt.name, "dev.detekt:detekt-cli:${versions.getProperty("detekt")}:all")
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
        val architectureRules = File(root, ".quality/rules/nur-architecture-rules-${versions.getProperty("release")}.jar")
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
            task.isIgnoreExitValue = true
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
                if (task.executionResult.get().exitValue != 0) {
                    throw GradleException("Formatting check failed; see ktlint output.")
                }
                marker.get().asFile.apply { parentFile.mkdirs(); writeText("passed\n") }
            }
            if (format) task.doLast {
                if (task.executionResult.get().exitValue != 0) throw GradleException("ktlint failed.")
            }
        }
        val check = formatter("qualityFormatCheck", false)
        formatter("qualityFormat", true)
        val analysis = project.tasks.register("qualityDetekt", JavaExec::class.java) { task ->
            task.group = "quality"
            task.description = "Run syntax-based Detekt and Compose rules, without detekt-formatting or autocorrect."
            task.classpath = detekt
            task.mainClass.set("dev.detekt.cli.Main")
            task.workingDir(root)
            task.maxHeapSize = "512m"
            task.isIgnoreExitValue = true
            task.inputs.files(sources).withPropertyName("sources")
            task.inputs.file(File(root, ".quality/detekt.yml")).withPropertyName("config")
            task.inputs.files(compose).withPropertyName("composeRules")
            val report = reportDir.map { it.file("detekt.xml") }
            task.outputs.file(report)
            task.onlyIf { !sources.isEmpty }
            task.doFirst {
                report.get().asFile.parentFile.mkdirs()
                report.get().asFile.delete()
                task.setArgs(listOf("--analysis-mode", "light", "--fail-on-severity", "Never",
                    "--input", sources.files.sortedBy { it.path }.joinToString(File.pathSeparator) { it.absolutePath },
                    "--config", File(root, ".quality/detekt.yml").absolutePath,
                    "--plugins", compose.files.sortedBy { it.path }.joinToString(File.pathSeparator) { it.absolutePath },
                    "--report", "checkstyle:${report.get().asFile.absolutePath}", "--base-path", root.absolutePath))
            }
            task.doLast {
                val exit = task.executionResult.get().exitValue
                if (exit != 0 && exit != 2) throw GradleException("Detekt failed (exit $exit); not a clean analysis.")
                if (!report.get().asFile.isFile) throw GradleException("Detekt did not produce its XML report.")
            }
        }
        val coverage = ArchitectureCoverage(reportDir.get().file("architecture-coverage.json").asFile,
            project.allprojects.map { it.path })
        coverage.requested = project.gradle.startParameter.taskNames.any {
            it.substringAfterLast(':').startsWith("qualityArch") || it.substringAfterLast(':') == "qualityCheck"
        }
        val manualEntry = ArchitectureScope("@manual").apply {
            kind = "manual"
            compilation = "explicit"
            taskPath = ":qualityArchitectureManual"
            report = reportDir.get().file("architecture.xml").asFile.path
        }
        val manual = ArchitectureTasks.register(project, "qualityArchitectureManual", root, detekt,
            architectureRules, extension.architectureSources, extension.architectureClasspath,
            extension.architectureJvmTarget, versions.getProperty("release"), entry = manualEntry, coverage = coverage)
        val coverageTask = project.tasks.register("qualityArchitectureCoverage") { task ->
            task.group = "quality"
            task.description = "Write coverage inventory/results; unexecuted scopes are never called checked."
            task.notCompatibleWithConfigurationCache("Live compilation coverage")
            task.doLast { coverage.write() }
        }
        val architecture = project.tasks.register("qualityArchitecture") { task ->
            task.group = "quality"
            task.description = "Analyze every supported production Kotlin module from one root plugin."
            task.notCompatibleWithConfigurationCache("Automatic project/compiler model discovery")
            task.finalizedBy(coverageTask)
            task.doLast { coverage.assertComplete() }
        }
        fun manualConfigured() = extension.architectureSources.from.isNotEmpty() ||
            extension.architectureClasspath.from.isNotEmpty()
        project.afterEvaluate {
            // Root-only task selection must still discover modules with configuration-on-demand.
            if (!manualConfigured()) project.allprojects.filter { it != project }.forEach {
                project.evaluationDependsOn(it.path)
            }
            // Must wire before task selection under configuration-on-demand:
            // projectsEvaluated can be too late for the already-selected root task.
            if (manualConfigured()) {
                coverage.mode = "manual"
                coverage.scopes.values.forEach {
                    it.status = "not_selected"
                    it.reason = "Explicit manual scope selected; no all-module coverage claim."
                }
                coverage.scopes["@manual"] = manualEntry
                architecture.configure { it.dependsOn(manual) }
                manual.configure { it.finalizedBy(coverageTask) }
            } else {
                val discovered = AutomaticArchitecture.discover(project, extension, detekt,
                    architectureRules, versions.getProperty("release"), coverage)
                architecture.configure { it.dependsOn(discovered) }
                discovered.forEach { task -> task.configure { it.finalizedBy(coverageTask) } }
            }
        }
        project.gradle.taskGraph.whenReady { graph ->
            coverage.requested = graph.allTasks.any {
                it.name in setOf("qualityArchitecture", "qualityArchitectureModule",
                    "qualityArchitectureManual", "qualityArchitectureCoverage")
            }
            coverage.write() // Fresh non-clean inventory BEFORE producers/analyzers.
        }
        @Suppress("DEPRECATION")
        project.gradle.taskGraph.afterTask { task ->
            task.state.failure?.let { if (coverage.requested) coverage.failed(task.path, it) }
        }
        @Suppress("DEPRECATION")
        project.gradle.buildFinished { result ->
            // A failed producer may prevent a task/finalizer from starting. Always retain gaps.
            coverage.finish(result.failure != null)
        }
        project.tasks.register("qualityCheck") {
            it.group = "quality"
            it.description = "Check formatting and run Detekt; suitable as an explicit CI command."
            it.dependsOn(check, analysis, architecture)
        }
    }
}
