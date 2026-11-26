package quality.upgrade

import dev.detekt.api.*
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.ServiceLoader

/** Unconditional extension, not a Rule: config.active and @Suppress cannot disable it.
 * A missing/broken listener or light Analysis API failure is rejected by the parent.
 */
@OptIn(KaExperimentalApi::class)
class AnalysisHealth : FileProcessListener {
    override val id = "NurMandatoryAnalysisHealth"
    private val output = File(requireNotNull(System.getProperty("nur.quality.health.path")))
    private val nonce = requireNotNull(System.getProperty("nur.quality.health.nonce"))
    private var count = 0
    private fun line(value: String) = synchronized(this) { output.appendText(value + "\n") }
    override fun onStart(files: List<KtFile>) {
        output.parentFile.mkdirs()
        output.writeText("NUR_HEALTH\t1\t$nonce\n")
        // Independent channel acknowledgements detect a redirected/dropped stream,
        // even when the parent-created log file still exists but remains empty.
        System.out.println("NUR_HEALTH_STDOUT\t$nonce")
        System.err.println("NUR_HEALTH_STDERR\t$nonce")
        val provider = ServiceLoader.load(RuleSetProvider::class.java, javaClass.classLoader)
            .single { it is ArchitectureRulesProvider }
        val rules = provider.instance().rules.keys.map { it.value }.sorted()
        line("PROVIDER\t${provider.javaClass.name}\t${rules.joinToString(",")}")
    }
    override fun onProcess(file: KtFile) {
        analyze(file) {
            val errors = file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                .filter { it.severity.toString() == "ERROR" }
            fun bad(type: KaType?): Boolean = type == null || type is KaErrorType ||
                (type is KaClassType && type.typeArguments.any { it.type?.let { nested -> bad(nested) } == true })
            // Inferred types matter too: @Suppress must not hide unresolved factory
            // results merely because no explicit KtTypeReference exists.
            val badTypes = file.collectDescendantsOfType<KtTypeReference>().count { bad(it.type) } +
                file.collectDescendantsOfType<KtProperty>().count { bad(it.symbol.returnType) } +
                file.collectDescendantsOfType<KtNamedFunction>().count { bad(it.symbol.returnType) } +
                file.collectDescendantsOfType<KtCallExpression>().count { bad(it.expressionType) }
            val source = File(file.virtualFilePath).canonicalFile
            val path = Base64.getUrlEncoder().withoutPadding().encodeToString(source.path.toByteArray(Charsets.UTF_8))
            val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                .joinToString("") { "%02x".format(it) }
            line("FILE\t$path\t$hash\t${errors.size}\t$badTypes\t${errors.joinToString(",") { it.factoryName }}")
            synchronized(this@AnalysisHealth) { count++ }
        }
    }
    override fun onFinish(files: List<KtFile>, result: Detektion): Detektion {
        line("END\t$count")
        return result
    }
}
