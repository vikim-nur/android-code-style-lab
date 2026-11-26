package kg.nur.quality

import com.pinterest.ktlint.rule.engine.core.api.*
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.*
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*

/** Normalize a short if condition after block wrapping, irrespective of the engine's rule ordering. */
class ConditionLayoutRule : Rule(
    RuleId("nur:compact-condition"), About(), usesEditorConfigProperties = setOf(MAX_LINE_LENGTH_PROPERTY),
    visitorModifiers = setOf(VisitorModifier.RunAsLateAsPossible,
        VisitorModifier.RunAfterRule(RuleId("nur:nested-lambda-wrapping"), VisitorModifier.RunAfterRule.Mode.ONLY_WHEN_RUN_AFTER_RULE_IS_LOADED_AND_ENABLED),
        VisitorModifier.RunAfterRule(RuleId("standard:condition-wrapping"), VisitorModifier.RunAfterRule.Mode.ONLY_WHEN_RUN_AFTER_RULE_IS_LOADED_AND_ENABLED)),
), RuleAutocorrectApproveHandler {
    private var width = 140
    override fun beforeFirstNode(editorConfig: EditorConfig) {
        width = editorConfig[MAX_LINE_LENGTH_PROPERTY].let { if (it <= 0) Int.MAX_VALUE else it }
    }
    override fun beforeVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) {
        val expression = node.psi as? KtIfExpression ?: return
        val condition = expression.condition ?: return
        val right = expression.rightParenthesis ?: return
        val left = expression.leftParenthesis ?: return
        if (PsiTreeUtil.findChildrenOfType(expression, PsiComment::class.java).any {
                it.textOffset > left.textOffset && it.textRange.endOffset <= right.textOffset
            } ||
            PsiTreeUtil.findChildOfType(condition, KtLambdaExpression::class.java) != null ||
            PsiTreeUtil.findChildrenOfType(condition, KtStringTemplateExpression::class.java).any { '\n' in it.text }) return
        val text = expression.containingFile.text
        val start = text.lastIndexOf('\n', left.textOffset - 1) + 1
        val flat = condition.text.replace(Regex("\\s+"), " ")
        val endOfLine = text.indexOf('\n', right.textRange.endOffset).let { if (it < 0) text.length else it }
        val tailLength = endOfLine - right.textRange.endOffset
        if (left.textRange.endOffset - start + flat.length + 1 + tailLength > width) return
        val spaces = mutableListOf<ASTNode>()
        fun collect(n: ASTNode) {
            if (n.elementType == ElementType.WHITE_SPACE && '\n' in n.text) spaces.add(n)
            var child = n.firstChildNode
            while (child != null) { collect(child); child = child.treeNext }
        }
        collect(condition.node)
        for (space in spaces) emit(space.startOffset, "Keep a short if condition on one line", true).ifAutocorrectAllowed {
            space.replaceTextWith(" ")
        }
        val before = condition.node.firstChildLeafOrSelf20.prevLeaf
        if (before?.elementType == ElementType.WHITE_SPACE && '\n' in before.text) {
            emit(before.startOffset, "Keep a short condition after the opening parenthesis", true).ifAutocorrectAllowed { before.remove() }
        }
        val previous = right.node.prevLeaf
        if (previous?.elementType == ElementType.WHITE_SPACE && '\n' in previous.text) {
            emit(previous.startOffset, "Keep the closing parenthesis with a short condition", true).ifAutocorrectAllowed { previous.remove() }
        }
    }
}
