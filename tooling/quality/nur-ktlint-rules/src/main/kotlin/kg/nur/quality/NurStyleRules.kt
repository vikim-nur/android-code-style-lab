package kg.nur.quality

import com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3
import com.pinterest.ktlint.rule.engine.core.api.*
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.*
import com.pinterest.ktlint.ruleset.standard.rules.SpacingAroundColonRule
import com.pinterest.ktlint.ruleset.standard.rules.WrappingRule
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*

/** Preserve accepted inheritance spacing without disabling spacing for property/parameter types. */
class TypeColonSpacingRule : Rule(RuleId("nur:type-colon-spacing"), About()), RuleAutocorrectApproveHandler {
    private val delegate = SpacingAroundColonRule()
    override fun beforeVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) {
        if (node.elementType == ElementType.COLON && node.psi.parent is KtClassOrObject) return
        delegate.beforeVisitChildNodes(node, emit)
    }
}

/** Keep a single nested call with a trailing lambda compact; retain stock wrapping elsewhere. */
class NestedLambdaWrappingRule : Rule(
    RuleId("nur:nested-lambda-wrapping"), About(),
    usesEditorConfigProperties = WrappingRule().usesEditorConfigProperties,
    visitorModifiers = WrappingRule().visitorModifiers,
), RuleAutocorrectApproveHandler {
    private val delegate = WrappingRule()
    override fun beforeFirstNode(editorConfig: EditorConfig) = delegate.beforeFirstNode(editorConfig)
    override fun beforeVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) {
        if (node.elementType == ElementType.LPAR) {
            val list = node.psi.parent as? KtValueArgumentList
            val argument = list?.arguments?.singleOrNull()
            val nested = argument?.getArgumentExpression() as? KtCallExpression
            if (argument != null && !argument.isNamed() && nested != null && nested.lambdaArguments.size == 1) {
                val lambda = nested.lambdaArguments.single().getLambdaExpression()
                val head = nested.text.substringBefore('{')
                // Preserve only the observed layout; do not collapse intentionally expanded outer calls.
                if (lambda != null && '\n' !in head && list.text.startsWith("(" + nested.calleeExpression?.text) &&
                    nested.text.endsWith("}") && list.text.endsWith("})")) return
            }
        }
        delegate.beforeVisitChildNodes(node, emit)
    }
}

/** Optional candidate: force a multiline call at 4+ parenthesized arguments. */
class CallArgumentsRule : Rule(
    RuleId("nur:call-arguments"), About(), usesEditorConfigProperties = setOf(CALL_MIN_ARGUMENTS),
), RuleAutocorrectApproveHandler {
    private var minimumArguments = 4
    override fun beforeFirstNode(editorConfig: EditorConfig) { minimumArguments = editorConfig[CALL_MIN_ARGUMENTS] }
    override fun beforeVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) {
        val list = node.psi as? KtValueArgumentList ?: return
        if (list.parent !is KtCallExpression || list.arguments.size < minimumArguments) return
        for (argument in list.arguments) {
            val prev = argument.prevSibling
            if (prev is PsiWhiteSpace && '\n' in prev.text) continue
            emit(argument.textOffset, "Place call arguments on separate lines", true)
                .ifAutocorrectAllowed { argument.node.upsertWhitespaceBeforeMe("\n") }
        }
        val right = list.rightParenthesis ?: return
        if (right.prevSibling !is PsiWhiteSpace || '\n' !in right.prevSibling.text) {
            emit(right.textOffset, "Place closing parenthesis on a separate line", true)
                .ifAutocorrectAllowed { right.node.upsertWhitespaceBeforeMe("\n") }
        }
    }
}

/** Optional candidate: compact only a comment-free one-statement intent lambda within 120 columns. */
class CompactIntentRule : Rule(
    RuleId("nur:compact-intent"), About(),
    usesEditorConfigProperties = setOf(MAX_LINE_LENGTH_PROPERTY, COMPACT_INTENT_MAX_WIDTH),
), RuleAutocorrectApproveHandler {
    private var limit = 120
    override fun beforeFirstNode(editorConfig: EditorConfig) {
        limit = minOf(editorConfig[MAX_LINE_LENGTH_PROPERTY], editorConfig[COMPACT_INTENT_MAX_WIDTH])
    }
    override fun afterVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) {
        val lambda = node.psi as? KtLambdaExpression ?: return
        val argument = lambda.parent as? KtLambdaArgument ?: return
        val call = argument.parent as? KtCallExpression ?: return
        if (call.calleeExpression?.text != "intent") return
        val block = lambda.bodyExpression ?: return
        val statement = block.statements.singleOrNull() ?: return
        if ('\n' !in lambda.text || '\n' in statement.text) return
        if (PsiTreeUtil.findChildOfType(lambda, PsiComment::class.java) != null) return
        val literal = lambda.functionLiteral
        // Explicit lambda parameters and semantic rewrites are deliberately excluded.
        if (literal.arrow != null) return
        val proposed = "{ " + statement.text + " }"
        val text = lambda.containingFile.text
        val lineStart = text.lastIndexOf('\n', lambda.textOffset - 1) + 1
        val lineEnd = text.indexOf('\n', lambda.textRange.endOffset).let { if (it < 0) text.length else it }
        if (lambda.textOffset - lineStart + proposed.length + lineEnd - lambda.textRange.endOffset > limit) return
        emit(lambda.textOffset, "Keep a short single-statement intent lambda on one line", true).ifAutocorrectAllowed {
            val before = statement.node.firstChildLeafOrSelf20.prevLeaf
            val after = statement.node.lastChildLeafOrSelf20.nextLeaf
            // Only replace boundary whitespace. Never replace or reparse the expression itself.
            if (before?.elementType == ElementType.WHITE_SPACE) before.replaceTextWith(" ")
            if (after?.elementType == ElementType.WHITE_SPACE) after.replaceTextWith(" ")
        }
    }
}

class NurStyleRuleSetProvider : RuleSetProviderV3(RuleSetId("nur")) {
    override fun getRuleProviders(): Set<RuleProvider> = setOf(
        RuleProvider { ModifierChainRule() },
        RuleProvider { ClassLayoutRule() },
        RuleProvider { ConditionLayoutRule() },
        RuleProvider { CompactCallRule() },
        RuleProvider { TypeColonSpacingRule() },
        RuleProvider { NestedLambdaWrappingRule() },
        RuleProvider { CallArgumentsRule() },
        RuleProvider { CompactIntentRule() },
        RuleProvider { ReferenceCallCommaRule() },
        RuleProvider { ReferenceDeclarationCommaRule() },
        RuleProvider { WhenBranchSpacingRule() },
    )
}
