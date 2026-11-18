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

/** Update owned configuration only; preflight every conflict before writing anything. */
internal object ManagedQualityFiles {
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
