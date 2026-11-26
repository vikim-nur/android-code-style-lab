package kg.nur.quality

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File

/** Deliberately no KGP/AGP implementation dependency in the convention-plugin loader.
 * Public task properties are probed at the actual applied plugin version. Unsupported
 * model/API is a coverage failure, never a guessed empty classpath or Kotlin auto-apply.
 */
internal object AutomaticArchitecture {
    fun discover(root: Project, extension: NurQualityExtension, runtime: FileCollection,
                 rules: File, release: String, coverage: ArchitectureCoverage): List<TaskProvider<out Task>> {
        val tasks = mutableListOf<TaskProvider<out Task>>()
        val androidPaths = root.allprojects.filter { p ->
            listOf("com.android.application", "com.android.library", "com.android.dynamic-feature")
                .any { p.pluginManager.hasPlugin(it) }
        }.map { it.path }.toSet()
        val invalidOverrides = extension.architectureVariants.get().keys - androidPaths
        if (invalidOverrides.isNotEmpty()) {
            coverage.scopes["@variant-configuration"] = ArchitectureScope("@variant-configuration").apply {
                kind = "configuration"
                status = "blocked"
                reason = "Variant overrides reference unknown/non-Android projects: ${invalidOverrides.sorted().joinToString()}."
            }
        }
        root.allprojects.sortedBy { it.path }.forEach { module ->
            val entry = coverage.scopes.getValue(module.path)
            val android = listOf("com.android.application", "com.android.library", "com.android.dynamic-feature")
                .any { module.pluginManager.hasPlugin(it) }
            val jvm = module.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") || module.pluginManager.hasPlugin("kotlin")
            val kmp = module.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")
            if (kmp) {
                entry.kind = "kmp"
                entry.status = "unsupported"
                entry.reason = "KMP targets are not covered by the JVM-main/Android-production adapter."
                return@forEach
            }
            if (!android && !jvm) {
                val java = module.pluginManager.hasPlugin("java") || module.pluginManager.hasPlugin("java-library")
                if (kmp || java || module.pluginManager.hasPlugin("com.android.test")) {
                    entry.kind = if (kmp) "kmp" else if (java) "java-only" else "android-test-only"
                    entry.status = "unsupported"
                    entry.reason = "No supported Kotlin/JVM main or Android production compilation adapter."
                } else if (!module.file("src").exists()) {
                    entry.kind = "container"
                    entry.status = "not_module"
                    entry.reason = "No supported compilation plugin; structural/root project is not analyzed as a module."
                } else {
                    entry.kind = "unrecognized-source-project"
                    entry.status = "unsupported"
                    entry.reason = "Project has src but no supported Kotlin compilation plugin."
                }
                return@forEach
            }
            entry.kind = if (android) "android" else "jvm"
            try {
                val compileName = if (android) selectAndroid(module, extension) else "compileKotlin"
                if (compileName !in module.tasks.names) throw GradleException("Missing production compiler task $compileName")
                val compile = module.tasks.named(compileName)
                val model = compile.get()
                requireGetter(model, "Libraries")
                val sourceProperty = listOf("Sources", "Source").firstOrNull { hasGetter(model, it) }
                    ?: throw GradleException("Compiler source FileCollection API is unavailable")
                val sources = module.files(module.provider { read(compile.get(), sourceProperty) })
                    .asFileTree.matching { it.include("**/*.kt") }
                val libraries = module.files(module.provider { read(compile.get(), "Libraries") })
                val cp = module.objects.fileCollection()
                var javaSources: FileCollection? = null
                val compilation = if (android) compileName.removePrefix("compile").removeSuffix("Kotlin")
                    .replaceFirstChar { it.lowercase() } else "main"
                // Own Java declarations are source roots to kotlinc, not necessarily
                // classes in libraries. Source emptiness is NOT a graph-selection
                // predicate: generated Java may appear only after producers execute.
                val javaName = if (android) compileName.removeSuffix("Kotlin") + "JavaWithJavac" else "compileJava"
                if (javaName in module.tasks.names) {
                    val javac = module.tasks.named(javaName, JavaCompile::class.java)
                    val javaOutput = javac.flatMap { it.destinationDirectory }
                    javaSources = module.files(module.provider { javac.get().source })
                    cp.builtBy(javac) // Stable edge, even while generated source dirs are absent.
                    cp.from(module.provider {
                        val producer = javac.get()
                        if (!producer.enabled || (producer.state.executed && producer.state.skipMessage == "SKIPPED"))
                            throw GradleException("Architecture NOT CHECKED: required own Java producer ${producer.path} was disabled/skipped.")
                        // NO-SOURCE is a terminal Gradle outcome, not a filesystem guess.
                        // A real compile/cache hit must expose its declared output; missing
                        // output then remains a blocking classpath/input validation failure.
                        if (producer.state.noSource) emptyList<File>() else javaOutput
                    })
                }
                cp.from(libraries) // Keep FileCollection / builtBy edges, never .files/filter(existing).
                if (android) {
                    val components = module.extensions.findByName("androidComponents")
                        ?: throw GradleException("Android Components SDK API unavailable")
                    val sdk = read(components, "SdkComponents")
                    requireGetter(sdk, "BootClasspath")
                    cp.from(module.provider { read(sdk, "BootClasspath") })
                }
                val target = module.provider { compilerTarget(compile.get()) }
                val jdk: Provider<File>? = if (!android) {
                    val java = module.extensions.findByType(JavaPluginExtension::class.java)
                        ?: throw GradleException("JVM Java toolchain model unavailable")
                    module.extensions.getByType(JavaToolchainService::class.java).launcherFor(java.toolchain)
                        .map { it.metadata.installationPath.asFile }
                } else null
                entry.compilation = compilation
                entry.taskPath = if (module.path == ":") ":qualityArchitectureModule" else "${module.path}:qualityArchitectureModule"
                entry.report = module.layout.buildDirectory.file("reports/quality/architecture.xml").get().asFile.path
                entry.reason = "Selected $compileName; pending producers and mandatory full-analysis health."
                tasks += ArchitectureTasks.register(module, "qualityArchitectureModule", root.projectDir,
                    runtime, rules, sources, cp, target, release, emptyAllowed = true,
                    entry = entry, coverage = coverage, jdkHome = jdk, javaSources = javaSources)
            } catch (failure: Exception) {
                entry.status = "blocked"
                entry.reason = "Compilation adapter unavailable: ${failure.message?.take(250)}"
            }
        }
        return tasks
    }

    private fun selectAndroid(project: Project, extension: NurQualityExtension): String {
        val override = extension.architectureVariants.get()[project.path]
        if (override != null) {
            if (!Regex("[A-Za-z][A-Za-z0-9]*").matches(override) || override.contains("test", ignoreCase = true))
                throw GradleException("Variant override must select one production variant, not tests")
            return "compile${override.replaceFirstChar { it.uppercase() }}Kotlin"
        }
        if ("compileDebugKotlin" in project.tasks.names) return "compileDebugKotlin"
        val candidates = project.tasks.names.filter { it.startsWith("compile") && it.endsWith("DebugKotlin") }
        if (candidates.size == 1) return candidates.single()
        throw GradleException("No unique production debug compilation (${candidates.size} candidates); set root nurQuality.architectureVariants['${project.path}']")
    }
    private fun hasGetter(value: Any, property: String) =
        value.javaClass.methods.any { it.name == "get$property" && it.parameterCount == 0 }
    private fun requireGetter(value: Any, property: String) {
        if (!hasGetter(value, property)) throw GradleException("Missing public get$property on ${value.javaClass.name}")
    }
    private fun read(value: Any, property: String): Any {
        requireGetter(value, property)
        return try {
            value.javaClass.methods.first { it.name == "get$property" && it.parameterCount == 0 }.invoke(value)
                ?: throw GradleException("Null compiler property $property")
        } catch (failure: ReflectiveOperationException) {
            throw GradleException("Unable to read $property on ${value.javaClass.name}: ${failure.javaClass.simpleName}")
        }
    }
    private fun compilerTarget(task: Task): String {
        val result = if (hasGetter(task, "CompilerOptions")) {
            val target = read(read(task, "CompilerOptions"), "JvmTarget")
            val selected = if (target is Provider<*>) target.orNull
                ?: throw GradleException("Compiler JVM target has no value") else target
            if (hasGetter(selected, "Target")) read(selected, "Target").toString() else selected.toString()
        } else if (hasGetter(task, "KotlinOptions")) read(read(task, "KotlinOptions"), "JvmTarget").toString()
        else throw GradleException("Compiler JVM target API unavailable")
        if (!Regex("(1\\.[68]|[0-9]+)").matches(result)) throw GradleException("Unrecognized compiler JVM target $result")
        return result
    }
}
