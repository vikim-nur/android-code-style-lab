package kg.nur.quality

import org.gradle.api.GradleException
import java.io.File

internal class ArchitectureScope(val projectPath: String) {
    var kind = "unknown"
    var compilation = ""
    var taskPath = ""
    var status = "blocked"
    var reason = "Project discovery/configuration has not completed."
    var sourceCount: Int? = null
    var classpathCount: Int? = null
    var healthNonce: String? = null
    var report = ""
    @Volatile var classpathAdjustments: List<Map<String, Any?>> = emptyList()
}

/** Writes an initial non-clean snapshot before execution, each result, and a buildFinished
 * fallback for producer/configuration failures that prevent the aggregate task running.
 */
internal class ArchitectureCoverage(private val output: File, paths: List<String>) {
    val scopes = paths.associateWith { ArchitectureScope(it) }.toMutableMap()
    var mode = "auto"
    var requested = false
    private val failures = linkedMapOf<String, String>()
    @Synchronized fun failed(task: String, failure: Throwable) {
        val seen = mutableSetOf<Throwable>()
        var cause: Throwable? = failure
        var reason = "Failed task $task (${failure.javaClass.simpleName}); see Gradle diagnostics."
        while (cause != null && seen.add(cause)) {
            // Only our controlled messages; never copy arbitrary compiler/build-script
            // source lines, repository credentials or raw external exception messages.
            if (cause.message?.startsWith("Architecture ") == true) {
                reason = cause.message!!.take(1600)
                break
            }
            cause = cause.cause
        }
        failures[task] = reason
        scopes.values.filter { it.taskPath == task }.forEach {
            it.status = "blocked"
            it.reason = reason
        }
        write()
    }
    @Synchronized fun finish(failed: Boolean) {
        if (failed) scopes.values.filter { it.status == "blocked" }.forEach {
            if (failures.isNotEmpty()) it.reason += " Failed tasks: ${failures.keys.joinToString()}."
            else it.reason += " Configuration/classpath resolution failed before task execution; see Gradle diagnostics."
        }
        write()
    }
    @Synchronized fun write() {
        if (!requested) return
        output.parentFile.mkdirs()
        val values = linkedMapOf<String, Any?>(
            "schema" to 1, "mode" to mode, "allModulesClaimed" to
                (mode == "auto" && scopes.values.any { it.status in setOf("checked", "empty") } &&
                    scopes.values.all { it.status in setOf("checked", "empty", "not_module") }),
            "scope" to "JVM main / one Android production variant; not all variants/tests/KMP",
            "failures" to failures.map { (task, reason) -> linkedMapOf("task" to task, "reason" to reason) },
            "modules" to scopes.values.sortedBy { it.projectPath }.map { s ->
                linkedMapOf("project" to s.projectPath, "kind" to s.kind,
                    "compilation" to s.compilation, "task" to s.taskPath,
                    "status" to s.status, "reason" to s.reason, "sources" to s.sourceCount,
                    "classpathEntries" to s.classpathCount, "healthNonce" to s.healthNonce,
                    "report" to s.report, "classpathAdjustments" to s.classpathAdjustments)
            })
        val temporary = File(output.parentFile, output.name + ".tmp")
        temporary.writeText(json(values) + "\n")
        java.nio.file.Files.move(temporary.toPath(), output.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    fun assertComplete() {
        write()
        val gaps = scopes.values.filter { it.status in setOf("blocked", "unsupported") }
        if (gaps.isNotEmpty()) throw GradleException("Architecture COVERAGE INCOMPLETE: " +
            gaps.joinToString { "${it.projectPath}: ${it.reason}" } + ". See $output")
    }
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> value.toString().map { c ->
            when (c) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (c.code < 32) "\\u%04x".format(c.code) else c.toString()
            }
        }.joinToString("", "\"", "\"")
    }
}
