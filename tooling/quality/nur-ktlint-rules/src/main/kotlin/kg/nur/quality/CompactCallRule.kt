package kg.nur.quality

import com.pinterest.ktlint.rule.engine.core.api.*
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.*
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*

/** Compact a fitting argument list without changing arguments or moving lambda expressions. */
class CompactCallRule : Rule(
    RuleId("nur:compact-call"), About(), usesEditorConfigProperties = setOf(MAX_LINE_LENGTH_PROPERTY),
    visitorModifiers = setOf(VisitorModifier.RunAsLateAsPossible,
        VisitorModifier.RunAfterRule(RuleId("nur:class-layout"), VisitorModifier.RunAfterRule.Mode.REGARDLESS_WHETHER_RUN_AFTER_RULE_IS_LOADED_OR_DISABLED),
        VisitorModifier.RunAfterRule(RuleId("nur:call-site-comma"), VisitorModifier.RunAfterRule.Mode.REGARDLESS_WHETHER_RUN_AFTER_RULE_IS_LOADED_OR_DISABLED),
        VisitorModifier.RunAfterRule(RuleId("standard:argument-list-wrapping"), VisitorModifier.RunAfterRule.Mode.REGARDLESS_WHETHER_RUN_AFTER_RULE_IS_LOADED_OR_DISABLED)),
), RuleAutocorrectApproveHandler {
    private var width = 140
    override fun beforeFirstNode(editorConfig: EditorConfig) {
        width = editorConfig[MAX_LINE_LENGTH_PROPERTY].let { if (it <= 0) Int.MAX_VALUE else it }
    }
    override fun afterVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) {
        val list = node.psi as? KtValueArgumentList ?: return
        if (list.parent !is KtCallExpression || '\n' !in list.text) return
        if (PsiTreeUtil.findChildOfType(list, PsiComment::class.java) != null ||
            PsiTreeUtil.findChildrenOfType(list, KtStringTemplateExpression::class.java).any { '\n' in it.text } ||
            PsiTreeUtil.findChildrenOfType(list, KtBlockExpression::class.java).any { it.statements.size > 1 } ||
            PsiTreeUtil.findChildrenOfType(list, KtQualifiedExpression::class.java).any { '\n' in it.text } ||
            PsiTreeUtil.findChildOfType(list, KtIfExpression::class.java) != null ||
            PsiTreeUtil.findChildOfType(list, KtWhenExpression::class.java) != null ||
            PsiTreeUtil.findChildOfType(list, KtLoopExpression::class.java) != null ||
            PsiTreeUtil.findChildOfType(list, KtTryExpression::class.java) != null ||
            PsiTreeUtil.findChildOfType(list, KtNamedFunction::class.java) != null) return
        val leaves = mutableListOf<ASTNode>()
        fun collect(n: ASTNode) {
            if (n.firstChildNode == null) leaves.add(n)
            var child = n.firstChildNode
            while (child != null) { collect(child); child = child.treeNext }
        }
        collect(node)
        val replacements = linkedMapOf<ASTNode, String>()
        for (leaf in leaves) {
            if (leaf.elementType != ElementType.WHITE_SPACE || '\n' !in leaf.text) continue
            val previous = leaf.prevLeaf?.text
            val next = leaf.nextLeaf?.text
            replacements[leaf] = when {
                next in setOf(")", "]", ">", ",", ".", "?.") -> ""
                previous in setOf("(", "[", "<", ".", "?.") -> ""
                previous == "{" && next == "}" -> ""
                else -> " "
            }
        }
        if (replacements.isEmpty()) return
        val compact = leaves.joinToString("") { replacements[it] ?: it.text }
        val file = list.containingFile.text
        val startOfLine = file.lastIndexOf('\n', list.textOffset - 1) + 1
        val endOfLine = file.indexOf('\n', list.textRange.endOffset).let { if (it < 0) file.length else it }
        val trailingComma = list.rightParenthesis?.node?.prevCodeLeaf?.elementType == ElementType.COMMA
        val compactWidth = list.textOffset - startOfLine + compact.length + endOfLine - list.textRange.endOffset - if (trailingComma) 1 else 0
        if (compactWidth > width) return
        for ((space, value) in replacements.entries.sortedByDescending { it.key.startOffset }) {
            emit(space.startOffset, "Keep a fitting call argument list on one line", true).ifAutocorrectAllowed {
                if (value.isEmpty()) space.remove() else space.replaceTextWith(value)
            }
        }
    }
}
