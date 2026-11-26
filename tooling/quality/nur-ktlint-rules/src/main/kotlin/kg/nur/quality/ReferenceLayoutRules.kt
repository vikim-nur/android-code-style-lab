package kg.nur.quality

import com.pinterest.ktlint.rule.engine.core.api.*
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfig
import com.pinterest.ktlint.ruleset.standard.rules.TrailingCommaOnCallSiteRule
import com.pinterest.ktlint.ruleset.standard.rules.TrailingCommaOnDeclarationSiteRule
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Reuse pinned ktlint comma logic after our wrapping replacement. */
class ReferenceCallCommaRule : Rule(
    RuleId("nur:call-site-comma"), About(),
    usesEditorConfigProperties = TrailingCommaOnCallSiteRule().usesEditorConfigProperties,
    visitorModifiers = setOf(VisitorModifier.RunAfterRule(RuleId("nur:nested-lambda-wrapping"), VisitorModifier.RunAfterRule.Mode.ONLY_WHEN_RUN_AFTER_RULE_IS_LOADED_AND_ENABLED), VisitorModifier.RunAsLateAsPossible),
), RuleAutocorrectApproveHandler {
    private val delegate = TrailingCommaOnCallSiteRule()
    override fun beforeFirstNode(editorConfig: EditorConfig) = delegate.beforeFirstNode(editorConfig)
    override fun beforeVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) =
        delegate.beforeVisitChildNodes(node, emit)
}

class ReferenceDeclarationCommaRule : Rule(
    RuleId("nur:declaration-site-comma"), About(),
    usesEditorConfigProperties = TrailingCommaOnDeclarationSiteRule().usesEditorConfigProperties,
    visitorModifiers = setOf(VisitorModifier.RunAfterRule(RuleId("nur:nested-lambda-wrapping"), VisitorModifier.RunAfterRule.Mode.ONLY_WHEN_RUN_AFTER_RULE_IS_LOADED_AND_ENABLED), VisitorModifier.RunAsLateAsPossible),
), RuleAutocorrectApproveHandler {
    private val delegate = TrailingCommaOnDeclarationSiteRule()
    override fun beforeFirstNode(editorConfig: EditorConfig) = delegate.beforeFirstNode(editorConfig)
    override fun beforeVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) =
        delegate.beforeVisitChildNodes(node, emit)
}

/** Separate multiline branches without inserting gaps between adjacent simple branches. */
class WhenBranchSpacingRule : Rule(
    RuleId("nur:when-branch-spacing"), About(),
    visitorModifiers = setOf(VisitorModifier.RunAsLateAsPossible),
), RuleAutocorrectApproveHandler {
    override fun beforeVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) {
        val expression = node.psi as? KtWhenExpression ?: return
        if ('\n' !in expression.text) return
        val entries = expression.entries
        for (index in 1 until entries.size) {
            val entry = entries[index]
            // Do not move or reinterpret comments between branches or semicolon-separated code.
            val whitespace = entry.prevSibling as? PsiWhiteSpace ?: continue
            if (whitespace.prevSibling != entries[index - 1] || '\n' !in whitespace.text) continue
            val gap = if ('\n' in entries[index - 1].text) "\n\n" else "\n"
            val desired = gap + whitespace.text.substringAfterLast('\n')
            if (whitespace.text == desired) continue
            emit(whitespace.textOffset, "Use one blank line after a multiline when branch and none after a simple branch", true)
                .ifAutocorrectAllowed { whitespace.node.replaceTextWith(desired) }
        }
    }
}
