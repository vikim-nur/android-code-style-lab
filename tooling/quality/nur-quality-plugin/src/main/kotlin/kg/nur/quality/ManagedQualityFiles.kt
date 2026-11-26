package kg.nur.quality

import org.gradle.api.GradleException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import java.io.File
import java.util.Base64

/** Update owned configuration only; preflight every conflict before writing anything. */
internal object ManagedQualityFiles {
    fun sourceHash(file: File): String = hash(file.readBytes())

    /** Strict external protocol: findings never substitute for a healthy full analysis. */
    fun verifyAnalysisHealth(sidecar: File, nonce: String, expected: Map<String, String>,
                             report: File, stdout: File, stderr: File) {
        fun reject(message: String): Nothing =
            throw GradleException("Architecture NOT TRUSTED: $message. See ${sidecar.parent}.")
        if (!sidecar.isFile || !stdout.isFile || !stderr.isFile || !report.isFile)
            reject("missing health/report/diagnostic channel")
        if (stdout.readLines().count { it == "NUR_HEALTH_STDOUT\t$nonce" } != 1 ||
            stderr.readLines().count { it == "NUR_HEALTH_STDERR\t$nonce" } != 1)
            reject("missing/stale/redirected diagnostic channel acknowledgement")
        val lines = sidecar.readLines()
        if (lines.firstOrNull() != "NUR_HEALTH\t1\t$nonce") reject("invalid/stale health header")
        val provider = "PROVIDER\tquality.upgrade.ArchitectureRulesProvider\tLayerForbiddenDependency,ScreenForbiddenParameterType"
        if (lines.getOrNull(1) != provider) reject("native provider/rule presence not confirmed")
        val records = lines.drop(2).dropLast(1)
        if (lines.lastOrNull() != "END\t${expected.size}" || records.size != expected.size)
            reject("incomplete/duplicate health visits")
        val visited = mutableSetOf<String>()
        for (line in records) {
            val parts = line.split('\t')
            if (parts.size != 6 || parts[0] != "FILE") reject("malformed health record")
            val path = try { String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8) }
                catch (_: IllegalArgumentException) { reject("malformed source identity") }
            if (!visited.add(path) || expected[path] != parts[2]) reject("unexpected/changed/duplicate source")
            if (parts[3] != "0" || parts[4] != "0" || parts[5].isNotEmpty())
                reject("compiler ERROR diagnostics/error types in $path (${parts[3]}/${parts[4]} ${parts[5]})")
            if (!File(path).isFile || sourceHash(File(path)) != expected[path]) reject("source changed during analysis")
        }
        if (visited != expected.keys) reject("source coverage differs from exact task inputs")
        if (Regex("(?im)(^|\\s)error:|Exception in thread|compiled with an incompatible version")
                .containsMatchIn(stderr.readText())) reject("compiler/runtime error on stderr")
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            val document = factory.newDocumentBuilder().parse(report)
            if (document.documentElement.tagName != "checkstyle") reject("unexpected XML report")
        } catch (error: Exception) {
            reject("invalid XML report (${error.javaClass.simpleName})")
        }
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/quality/$name")
        ?.use { it.readBytes() } ?: throw GradleException("Missing packaged quality resource: $name")
    fun versions() = Properties().apply { resource("versions.properties").inputStream().use { load(it) } }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun write(file: Path, bytes: ByteArray) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, ".nur-quality-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun fingerprint(name: String, bytes: ByteArray): String {
        if (name != ".idea/ktlint-plugin.xml") return hash(bytes)
        // Studio may reindent XML or omit a setting whose value equals its default.
        // Compare its structure, retaining every non-default setting and unknown component.
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val document = factory.newDocumentBuilder().parse(bytes.inputStream())
        fun canonical(element: Element): String {
            if (element.tagName == "attachToIntellijFormat" && element.textContent.trim() == "true") return ""
            val attributes = (0 until element.attributes.length).map { element.attributes.item(it) }
                .map { it.nodeName + "=" + it.nodeValue }.sorted().joinToString(";")
            val children = (0 until element.childNodes.length).map { element.childNodes.item(it) }
                .filterIsInstance<Element>().map { canonical(it) }.filter { it.isNotEmpty() }
            val content = if (children.isEmpty()) element.textContent.trim() else
                (if (element.tagName == "list") children else children.sorted()).joinToString("|")
            return "${element.tagName}[$attributes]{$content}"
        }
        return hash(canonical(document.documentElement).toByteArray())
    }

    fun sync(root: Path) {
        val versions = versions()
        val jarName = "nur-style-rules-${versions.getProperty("release")}.jar"
        val xml = """<project version="4">
  <component name="com.nbadal.ktlint.KtlintProjectSettings">
    <ktlintMode>MANUAL</ktlintMode>
    <ktlintRulesetVersion>V${versions.getProperty("ktlint").replace('.', '_')}</ktlintRulesetVersion>
    <formatOnSave>false</formatOnSave>
    <attachToIntellijFormat>true</attachToIntellijFormat>
    <externalJarPaths><list><option value="${'$'}PROJECT_DIR${'$'}/.quality/rules/$jarName" /></list></externalJarPaths>
  </component>
</project>
"""
        val desired = linkedMapOf(
            // Install the binary before publishing a setting that refers to its new path.
            ".quality/rules/$jarName" to resource(jarName),
            ".quality/rules/nur-architecture-rules-${versions.getProperty("release")}.jar" to
                resource("nur-architecture-rules-${versions.getProperty("release")}.jar"),
            ".quality/architecture.yml" to resource("architecture.yml"),
            ".editorconfig" to resource("editorconfig"),
            "ktlint-plugins.properties" to "ktlint-version=${versions.getProperty("ktlint")}\n".toByteArray(),
            ".quality/detekt.yml" to resource("detekt.yml"),
            ".idea/ktlint-plugin.xml" to xml.toByteArray(),
        )
        val manifest = root.resolve(".quality/managed-files.properties")
        val old = Properties().apply {
            if (Files.isRegularFile(manifest)) Files.newInputStream(manifest).use { load(it) }
        }
        for ((name, bytes) in desired) {
            val file = root.resolve(name)
            if (Files.exists(file)) {
                val actual = Files.readAllBytes(file)
                if (fingerprint(name, actual) != fingerprint(name, bytes) && old.getProperty(name) != fingerprint(name, actual)) {
                    throw GradleException("Quality configuration conflict: $name has local/unmanaged content. Review it before adopting the shared profile.")
                }
            }
        }
        for ((name, bytes) in desired) {
            val file = root.resolve(name)
            if (!Files.exists(file) || fingerprint(name, Files.readAllBytes(file)) != fingerprint(name, bytes)) {
                write(file, bytes)
            }
        }
        val manifestBytes = desired.entries.joinToString("\n", postfix = "\n") { (name, bytes) -> "$name=${fingerprint(name, bytes)}" }.toByteArray()
        if (!Files.exists(manifest) || !Files.readAllBytes(manifest).contentEquals(manifestBytes)) {
            write(manifest, manifestBytes)
        }
    }
}
