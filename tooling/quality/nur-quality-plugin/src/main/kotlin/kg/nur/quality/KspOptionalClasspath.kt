package kg.nur.quality

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import java.io.File

/**
 * KSP2 registers classOutputDir even when CodeGenerator emits only .kt/.java.
 * The class directory is created lazily when a processor writes a .class file.
 * Only that exact declared output of the selected successful KspAATask is optional.
 * No path-pattern whitelist, filter(existing), user bypass, or health relaxation.
 *
 * Verified model/semantics: google/ksp tag 2.3.12, KspSubplugin.kt,
 * KspAATask.kt / KspGradleConfig, common/impl/CodeGeneratorImpl.kt.
 */
internal object KspOptionalClasspath {
    fun adapt(project: Project, compileName: String, libraries: FileCollection,
              entry: ArchitectureScope): FileCollection {
        if (!project.pluginManager.hasPlugin("com.google.devtools.ksp")) return libraries
        val name = compileName.replaceFirst("compile", "ksp")
        if (name !in project.tasks.names) fail("selected KSP producer $name is unavailable")
        val producer = project.tasks.named(name)
        val model = producer.get()
        if (generateSequence<Class<*>>(model.javaClass) { it.superclass }
                .none { it.name == "com.google.devtools.ksp.gradle.KspAATask" })
            fail("unsupported KSP producer API ${model.javaClass.name}; no optional-output exemption")
        val config = read(model, "KspConfig")
        fun directory(property: String): DirectoryProperty = read(config, property) as? DirectoryProperty
            ?: fail("KSP $property is not a DirectoryProperty")
        val classOutput = directory("ClassOutputDir")
        val kotlinOutput = directory("KotlinOutputDir")
        val javaOutput = directory("JavaOutputDir")
        val base = directory("OutputBaseDir").get().asFile.canonicalFile
        val output = classOutput.get().asFile.canonicalFile
        if (output != File(base, "classes").canonicalFile ||
            kotlinOutput.get().asFile.canonicalFile != File(base, "kotlin").canonicalFile ||
            javaOutput.get().asFile.canonicalFile != File(base, "java").canonicalFile ||
            !base.toPath().startsWith(project.layout.buildDirectory.get().asFile.canonicalFile.toPath()))
            fail("nonstandard selected KSP output registration; no optional-output exemption")
        if (model.outputs.files.files.none { it.canonicalFile == output })
            fail("KSP classOutputDir is not a registered task output")

        fun generated(directory: DirectoryProperty, extension: String): List<String> {
            val folder = directory.get().asFile
            return if (!folder.isDirectory) emptyList() else folder.walkTopDown()
                .filter { it.isFile && it.extension == extension }.map { it.canonicalPath }.sorted().toList()
        }
        val filtered = libraries.filter { input ->
            if (input.canonicalFile != output) true else {
                val task = producer.get()
                val state = task.state
                if (!task.enabled || state.failure != null || state.skipMessage == "SKIPPED")
                    fail("selected KSP producer ${task.path} failed or was disabled/skipped")
                val complete = state.executed
                val success = complete && (!state.skipped || state.upToDate ||
                    state.noSource || state.skipMessage == "FROM-CACHE")
                if (complete && !success) fail("selected KSP producer ${task.path} has no successful outcome")
                if (input.exists() && !input.isDirectory) fail("KSP classOutputDir is not a directory")
                val omitted = success && !input.exists()
                entry.classpathAdjustments = listOf(linkedMapOf(
                    "kind" to "ksp2-optional-binary-output",
                    "path" to output.path, "producer" to task.path,
                    "producerType" to "com.google.devtools.ksp.gradle.KspAATask",
                    "registeredOutput" to true,
                    "producerComplete" to complete,
                    "producerOutcome" to (state.skipMessage ?: if (complete) "EXECUTED" else "PENDING"),
                    "action" to if (omitted) "omit-absent-after-success" else "retain",
                    "reason" to if (omitted)
                        "Successful KSP2 producer emitted no binary directory; .class output is lazily created."
                        else "Present output or producer not yet complete; no missing input exemption.",
                    "generatedKotlinSources" to if (success) generated(kotlinOutput, "kt") else emptyList<String>(),
                    "generatedJavaSources" to if (success) generated(javaOutput, "java") else emptyList<String>()
                ))
                !omitted
            }
        }
        // Keep every original FileCollection edge even when its optional path disappears,
        // plus an unconditional selected producer edge independent of filesystem state.
        return project.files(filtered).builtBy(producer)
    }
    private fun read(value: Any, property: String): Any = try {
        value.javaClass.methods.firstOrNull { it.name == "get$property" && it.parameterCount == 0 }
            ?.invoke(value) ?: fail("KSP $property getter/value unavailable")
    } catch (failure: ReflectiveOperationException) {
        fail("KSP $property getter failed (${failure.javaClass.simpleName})")
    }
    private fun fail(reason: String): Nothing =
        throw GradleException("Architecture NOT CHECKED: $reason.")
}
