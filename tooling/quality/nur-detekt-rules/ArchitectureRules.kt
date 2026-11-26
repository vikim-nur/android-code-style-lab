package quality.upgrade

import dev.detekt.api.*
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.psi.*

class ArchitectureRulesProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("TeamArchitecture")
    override fun instance() = RuleSet(ruleSetId,
        listOf(::ScreenForbiddenParameterType, ::LayerForbiddenDependency))
}

private fun KaSession.forbiddenType(type: KaType?, pattern: Regex): String? {
    val visited = mutableSetOf<String>()
    fun inspect(current: KaType?): String? {
        if (current == null || current is KaErrorType) return null
        val klass = current.fullyExpandedType as? KaClassType ?: return null
        if (!visited.add(klass.toString())) return null
        val name = klass.classId.asSingleFqName().asString()
        if (pattern.matches(name)) return name
        for (argument in klass.typeArguments) inspect(argument.type)?.let { return it }
        for (supertype in klass.directSupertypes) inspect(supertype)?.let { return it }
        return null
    }
    return inspect(type)
}

class ScreenForbiddenParameterType(config: Config) :
    Rule(config, "Selected Screen/Content exposes a forbidden parameter type."), RequiresAnalysisApi {
    @Configuration("Full-match Screen/Content role regex")
    private val functionPattern: Regex by config(".*(Screen|ScreenContent)$", String::toRegex)
    @Configuration("Full-match explicitly allowed role regex")
    private val allowedFunctionPattern: Regex by config(".*(NavHost|NavGraph|Graph|Flow)$", String::toRegex)
    @Configuration("Require actual Compose annotation")
    private val onlyComposable: Boolean by config(true)
    @Configuration("Forbidden type full-match regex, including subtypes/generic arguments")
    private val forbiddenTypePattern: Regex by config("androidx\\.navigation\\.NavController", String::toRegex)
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val name = function.name ?: return
        if (!functionPattern.matches(name) || allowedFunctionPattern.matches(name)) return
        analyze(function) {
            if (onlyComposable && function.symbol.annotations.none {
                    it.classId?.asSingleFqName()?.asString() == "androidx.compose.runtime.Composable"
                }) return
            for (parameter in function.valueParameters) {
                val reference = parameter.typeReference ?: continue
                val forbidden = forbiddenType(reference.type, forbiddenTypePattern) ?: continue
                report(Finding(Entity.from(parameter),
                    "$name: parameter '${parameter.name}' exposes $forbidden. " +
                        "Keep navigation in the host; pass callbacks to Screen/Content."))
            }
        }
    }
}

class LayerForbiddenDependency(config: Config) :
    Rule(config, "Selected architectural package depends on a forbidden type/package."), RequiresAnalysisApi {
    @Configuration("Search regex selecting architectural packages")
    private val packagePattern: Regex by config("(^|\\.)domain(\\.|$)", String::toRegex)
    @Configuration("Full-match forbidden symbols regex")
    private val forbiddenSymbolPattern: Regex by config(
        ".*\\.(data|network_api)\\.(?:[^.]+\\.)*(model|dto)\\..*", String::toRegex)
    @Configuration("Also report matching imports")
    private val reportImports: Boolean by config(true)
    private var selected = false
    private val reportedSymbols = mutableSetOf<String>()
    override fun visitKtFile(file: KtFile) {
        selected = packagePattern.containsMatchIn(file.packageFqName.asString())
        reportedSymbols.clear()
        if (selected) super.visitKtFile(file)
    }
    private fun KaSession.reportType(type: KaType?, element: KtElement) {
        if (!selected) return
        val symbol = forbiddenType(type, forbiddenSymbolPattern) ?: return
        if (!reportedSymbols.add(symbol)) return
        report(Finding(Entity.from(element),
            "Package selected by packagePattern depends on $symbol, forbidden by this layer profile."))
    }
    override fun visitImportDirective(directive: KtImportDirective) {
        super.visitImportDirective(directive)
        val symbol = directive.importedFqName?.asString() ?: return
        if (selected && reportImports && forbiddenSymbolPattern.matches(symbol) && reportedSymbols.add(symbol)) {
            report(Finding(Entity.from(directive),
                "Package selected by packagePattern imports $symbol, forbidden by this layer profile."))
        }
    }
    override fun visitTypeReference(reference: KtTypeReference) {
        super.visitTypeReference(reference)
        analyze(reference) { reportType(reference.type, reference) }
    }
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        analyze(expression) { reportType(expression.expressionType, expression) }
    }
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        analyze(property) { reportType(property.symbol.returnType, property) }
    }
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        analyze(function) { reportType(function.symbol.returnType, function) }
    }
}
