package kg.nur.quality

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal object ArchitectureTasks {
    fun register(
        project: Project, name: String, root: File, runtime: FileCollection, rules: File,
        sources: FileCollection, compilationClasspath: FileCollection, target: Provider<String>,
        release: String, emptyAllowed: Boolean = false, entry: ArchitectureScope? = null,
        coverage: ArchitectureCoverage? = null,
        jdkHome: Provider<File>? = null,
        javaSources: FileCollection? = null,
    ): TaskProvider<JavaExec> = project.tasks.register(name, JavaExec::class.java) { task ->
        task.group = "quality"
        task.description = "Full, fail-closed typed architecture analysis; findings are non-blocking."
        task.classpath = runtime
        task.mainClass.set("dev.detekt.cli.Main")
        task.workingDir(root)
        task.maxHeapSize = "1g"
        task.isIgnoreExitValue = true
        task.notCompatibleWithConfigurationCache("Dynamic compilation bridge and mandatory live health receipt")
        task.inputs.files(compilationClasspath).withPropertyName("compilationClasspath")
        task.inputs.files(sources).withPropertyName("moduleSources")
        if (javaSources != null) task.inputs.files(javaSources).withPropertyName("javaSourceProducers")
        task.inputs.file(rules).withPropertyName("architectureRules")
        task.inputs.file(File(root, ".quality/architecture.yml")).withPropertyName("architectureProfile")
        task.inputs.property("release", release)
        task.inputs.property("analysisJvmTarget", target)
        if (jdkHome != null) task.inputs.property("analysisJdkHome", jdkHome.map { it.absolutePath })
        val directory = project.layout.buildDirectory.dir("reports/quality")
        val report = directory.map { it.file("architecture.xml") }
        val health = directory.map { it.file("architecture.health") }
        val stdout = directory.map { it.file("architecture.stdout.log") }
        val stderr = directory.map { it.file("architecture.stderr.log") }
        task.outputs.files(report, health, stdout, stderr)
        task.outputs.upToDateWhen { false }
        var expected = emptyMap<String, String>()
        var nonce = ""
        var out: FileOutputStream? = null
        var err: FileOutputStream? = null
        task.onlyIf {
            if (emptyAllowed && sources.isEmpty) {
                if (javaSources != null && !javaSources.isEmpty) {
                    entry?.status = "unsupported"
                    entry?.reason = "Java-only production sources are not analyzed by Kotlin architecture rules."
                    coverage?.write()
                    throw GradleException("Architecture COVERAGE GAP: Java-only production sources in ${project.path}.")
                }
                entry?.status = "empty"
                entry?.reason = "Selected production compilation has zero Kotlin sources; no analysis claimed."
                entry?.sourceCount = 0
                for (old in listOf(report, health, stdout, stderr)) old.get().asFile.delete()
                val emptyReceipt = directory.get().file("architecture.empty.json").asFile
                emptyReceipt.parentFile.mkdirs()
                emptyReceipt.writeText("""{"status":"empty","sources":0,"analyzed":false}""" + "\n")
                entry?.report = emptyReceipt.path
                coverage?.write()
                false
            } else true
        }
        task.doFirst {
            entry?.status = "blocked"
            entry?.reason = "Analyzer started but has not passed mandatory health."
            coverage?.write()
            directory.get().file("architecture.empty.json").asFile.delete()
            val cp = compilationClasspath.files
            val input = sources.files
            if (cp.isEmpty() || cp.any { !it.exists() || it.extension == "aar" } ||
                input.isEmpty() || input.any { !it.exists() }) {
                throw GradleException("Architecture NOT CHECKED: missing compiler-ready classpath or production sources for ${project.path}.")
            }
            val files = input.flatMap {
                if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList()
                else listOf(it)
            }.map { it.canonicalFile }.distinct().sortedBy { it.path }
            if (files.isEmpty() || files.any { it.extension != "kt" })
                throw GradleException("Architecture NOT CHECKED: expected exact Kotlin production sources.")
            expected = files.associate { it.path to ManagedQualityFiles.sourceHash(it) }
            entry?.sourceCount = expected.size
            entry?.classpathCount = cp.size
            report.get().asFile.parentFile.mkdirs()
            report.get().asFile.delete()
            health.get().asFile.delete()
            nonce = UUID.randomUUID().toString()
            task.systemProperty("nur.quality.health.path", health.get().asFile.absolutePath)
            task.systemProperty("nur.quality.health.nonce", nonce)
            out = FileOutputStream(stdout.get().asFile)
            err = FileOutputStream(stderr.get().asFile)
            task.standardOutput = out!!
            task.errorOutput = err!!
            task.setArgs(listOf("--analysis-mode", "full", "--fail-on-severity", "Never",
                "--disable-default-rulesets", "--input", files.joinToString(File.pathSeparator) { it.path },
                "--classpath", cp.joinToString(File.pathSeparator) { it.absolutePath },
                "--jvm-target", target.get(), "--config", File(root, ".quality/architecture.yml").absolutePath,
                "--plugins", rules.absolutePath, "--base-path", root.absolutePath,
                "--report", "checkstyle:${report.get().asFile.absolutePath}") +
                (jdkHome?.let { listOf("--jdk-home", it.get().absolutePath) } ?: emptyList()))
        }
        task.doLast {
            out?.close()
            err?.close()
            val actual = task.args
            val index = actual.indexOf("--analysis-mode")
            if (actual.count { it == "--analysis-mode" || it.startsWith("--analysis-mode=") } != 1 ||
                index < 0 || actual.getOrNull(index + 1) != "full")
                throw GradleException("Architecture NOT TRUSTED: actual CLI analysis mode is not uniquely full.")
            val exit = task.executionResult.get().exitValue
            if (exit != 0 && exit != 2) throw GradleException("Architecture analysis failed (exit $exit).")
            ManagedQualityFiles.verifyAnalysisHealth(health.get().asFile, nonce, expected,
                report.get().asFile, stdout.get().asFile, stderr.get().asFile)
            entry?.status = "checked"
            entry?.reason = "Full analysis passed exact-input/compiler-health checks; findings are non-blocking."
            entry?.healthNonce = nonce
            coverage?.write()
            project.logger.lifecycle("Architecture HEALTHY: full analysis; exact ${expected.size} Kotlin inputs; zero compiler errors/error types. Findings are non-blocking.")
        }
    }
}
