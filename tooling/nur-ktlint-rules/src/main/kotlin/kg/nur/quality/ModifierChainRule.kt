package kg.nur.quality

import com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3
import com.pinterest.ktlint.rule.engine.core.api.*
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfig

/** Experimental syntax-only rule. Does not resolve Kotlin types or compile application code. */
class ModifierChainRule : Rule(
    ruleId = RuleId("nur:modifier-chain"),
    about = About(),
    usesEditorConfigProperties = setOf(MODIFIER_MIN_CALLS),
), RuleAutocorrectApproveHandler {
    private var minimumCalls = 2
    override fun beforeFirstNode(editorConfig: EditorConfig) { minimumCalls = editorConfig[MODIFIER_MIN_CALLS] }
    override fun beforeVisitChildNodes(
        node: ASTNode,
        emit: (Int, String, Boolean) -> AutocorrectDecision,
    ) {
        val expression = node.psi as? KtDotQualifiedExpression ?: return
        // Only process the outer expression; argument expressions are independent chains.
        val parent = expression.parent as? KtDotQualifiedExpression
        if (parent?.receiverExpression == expression) return
        val chain = mutableListOf<KtDotQualifiedExpression>()
        var receiver: KtExpression = expression
        while (receiver is KtDotQualifiedExpression) {
            // Properties and safe calls are outside this first prototype.
            if (receiver.selectorExpression !is KtCallExpression) return
            chain.add(receiver)
            receiver = receiver.receiverExpression
        }
        if (chain.size < minimumCalls || !isKnownModifier(receiver)) return
        for (part in chain.asReversed()) {
            val dot = part.node.findChildByType(ElementType.DOT) ?: continue
            val whitespace = dot.treePrev
            if (whitespace?.elementType == ElementType.WHITE_SPACE && '\n' in whitespace.text) continue
            emit(dot.startOffset, "Place each Modifier call on a separate line", true)
                .ifAutocorrectAllowed { dot.upsertWhitespaceBeforeMe("\n") }
        }
    }

    private fun isKnownModifier(receiver: KtExpression): Boolean {
        val name = (receiver as? KtNameReferenceExpression)?.getReferencedName() ?: return false
        val file = receiver.containingFile as? KtFile ?: return false
        val importedNames = file.importDirectives.filter {
            it.importedFqName?.asString() == "androidx.compose.ui.Modifier" && !it.isAllUnder
        }.map { it.aliasName ?: "Modifier" }.toSet()
        // Conservative shadowing guard: skip ambiguous blocks, including nested lambdas.
        var ancestor: PsiElement? = receiver.parent
        var parameter: KtParameter? = null
        while (ancestor != null && ancestor !is KtFile) {
            if (ancestor is KtBlockExpression && ancestor.statements.any {
                    it is KtNamedDeclaration && it.name == name
                }) return false
            if (ancestor is KtClassOrObject && ancestor.declarations.any { it.name == name }) return false
            if (ancestor is KtLambdaExpression) {
                if (ancestor.valueParameters.any { it.name == name }) return false
                if (name == "it" && ancestor.valueParameters.isEmpty()) return false
            }
            if (ancestor is KtNamedFunction) {
                val found = ancestor.valueParameters.firstOrNull { it.name == name }
                if (found != null) { parameter = found; break }
            }
            ancestor = ancestor.parent
        }
        if (parameter != null) {
            val type = parameter.typeReference?.text ?: return false
            return type == "androidx.compose.ui.Modifier" || type in importedNames
        }
        // Avoid same-file declarations shadowing the imported singleton.
        if (file.declarations.any { it.name == name }) return false
        return name in importedNames
    }
}
