package kg.nur.quality

import com.pinterest.ktlint.rule.engine.core.api.*
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.*
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*

/** Whitespace-only canonical class headers. Ambiguous headers retain their layout. */
class ClassLayoutRule : Rule(
    RuleId("nur:class-layout"), About(),
    usesEditorConfigProperties = setOf(MAX_LINE_LENGTH_PROPERTY, INDENT_SIZE_PROPERTY, CONSTRUCTOR_MIN_PARAMETERS, INJECT_MULTILINE, WRAPPED_BASE_ARGUMENTS),
    visitorModifiers = setOf(VisitorModifier.RunAsLateAsPossible,
        VisitorModifier.RunAfterRule(RuleId("standard:argument-list-wrapping"),
            VisitorModifier.RunAfterRule.Mode.ONLY_WHEN_RUN_AFTER_RULE_IS_LOADED_AND_ENABLED)),
), RuleAutocorrectApproveHandler {
    private var maxWidth = 140
    private var indentSize = 4
    private var parameterLimit = 4
    private var injectMultiline = true
    private var wrappedBaseArguments = true

    override fun beforeFirstNode(editorConfig: EditorConfig) {
        maxWidth = editorConfig[MAX_LINE_LENGTH_PROPERTY].let { if (it <= 0) Int.MAX_VALUE else it }
        indentSize = editorConfig[INDENT_SIZE_PROPERTY]
        parameterLimit = editorConfig[CONSTRUCTOR_MIN_PARAMETERS]
        injectMultiline = editorConfig[INJECT_MULTILINE]
        wrappedBaseArguments = editorConfig[WRAPPED_BASE_ARGUMENTS]
    }

    override fun beforeVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) {
        val declaration = node.psi as? KtClass ?: return
        if (declaration is KtEnumEntry) return
        val keyword = declaration.getDeclarationKeyword() ?: return
        val bodyBrace = declaration.body?.lBrace
        val start = keyword.textOffset
        val end = bodyBrace?.textOffset ?: declaration.textRange.endOffset
        val headerElements = descendants(declaration).filter { it.textOffset >= start && it.textRange.endOffset <= end }
        // Retain comments, multiline literals/default expressions, and where clauses. No text reparsing.
        if (declaration.typeConstraintList != null || headerElements.any {
                it is PsiComment || it is KtLambdaExpression || it is KtObjectLiteralExpression ||
                    (it is KtStringTemplateExpression && '\n' in it.text)
            }) {
            return
        }
        val leaves = mutableListOf<ASTNode>()
        fun collect(current: ASTNode) {
            if (current.startOffset >= end || current.startOffset + current.textLength <= start) return
            if (current.firstChildNode == null) leaves.add(current)
            else { var child = current.firstChildNode; while (child != null) { collect(child); child = child.treeNext } }
        }
        collect(node)
        val code = leaves.filter { it.elementType != ElementType.WHITE_SPACE }
        if (code.isEmpty()) return
        val fullText = declaration.containingFile.text
        // An empty body contributes " {}", not just " {". Include any body text
        // that remains on the header line, so the width decision is repeatable.
        val bodySuffixWidth = bodyBrace?.let {
            fullText.substring(it.textOffset, declaration.textRange.endOffset)
                .substringBefore('\n').trimEnd().length + 1
        } ?: 0
        val lineStart = fullText.lastIndexOf('\n', start - 1) + 1
        val prefix = fullText.substring(lineStart, start)
        val baseIndent = prefix.takeWhile { it == ' ' || it == '\t' }
        val oneIndent = " ".repeat(indentSize)
        val desired = linkedMapOf<ASTNode, String>()
        for (leaf in code.drop(1)) {
            val whitespace = leaf.prevLeaf?.takeIf { it.elementType == ElementType.WHITE_SPACE }
            if (whitespace != null) desired[leaf] = if ('\n' in whitespace.text) " " else whitespace.text
        }
        fun gap(element: PsiElement?, text: String) { if (element != null) desired[element.node.firstChildLeafOrSelf20] = text }
        fun inlineLength(element: PsiElement): Int {
            val tokens = code.filter {
                it.startOffset >= element.textOffset && it.startOffset < element.textRange.endOffset
            }
            return tokens.mapIndexed { index, leaf ->
                (if (index == 0) "" else desired[leaf].orEmpty()) + leaf.text
            }.joinToString("").length
        }
        val parameters = declaration.getPrimaryConstructorParameterList()
        val values = parameters?.parameters.orEmpty()
        val supers = declaration.superTypeListEntries
        val colon = declaration.getColon()
        val inject = declaration.primaryConstructor?.annotationEntries.orEmpty().any {
            it.shortName?.asString() == "Inject"
        }
        val signatureEnd = colon?.textOffset ?: end
        val primaryText = fullText.substring(start, signatureEnd).replace(Regex("\\s+"), " ").trim()
        val multilineParameters = values.isNotEmpty() && (
            injectMultiline && inject || values.size >= parameterLimit ||
                prefix.length + primaryText.length + 2 > maxWidth ||
                values.any { '\n' in it.text })
        if (parameters != null) {
            for (parameter in values) {
                gap(parameter, if (multilineParameters) "\n$baseIndent$oneIndent" else if (parameter == values.first()) "" else " ")
            }
            gap(parameters.rightParenthesis, if (multilineParameters) "\n$baseIndent" else "")
        }
        // Do not erase annotations inside individual parameters; they retain their own wrapping.
        for (parameter in values) {
            for (leaf in code) {
                if (leaf.startOffset > parameter.textOffset && leaf.startOffset < parameter.textRange.endOffset) {
                    val old = leaf.prevLeaf?.takeIf { it.elementType == ElementType.WHITE_SPACE }
                    if (old != null && '\n' in old.text) desired[leaf] = old.text
                }
            }
        }
        fun computedHeaderLength(): Int {
            val firstLine = StringBuilder(prefix)
            for ((i, leaf) in code.withIndex()) {
                if (i > 0) firstLine.append(desired[leaf].orEmpty())
                firstLine.append(leaf.text)
            }
            return firstLine.toString().substringAfterLast('\n').length + bodySuffixWidth
        }
        // Measure the prospective inline layout, including gaps that earlier rules may
        // have removed while the superclass was on another line.
        gap(colon, " ")
        for (entry in supers) {
            gap(entry, " ")
            val args = (entry as? KtSuperTypeCallEntry)?.valueArgumentList ?: continue
            for ((index, argument) in args.arguments.withIndex()) gap(argument, if (index == 0) "" else " ")
            gap(args.rightParenthesis, "")
        }
        val wrapSupers = supers.size > 1 || computedHeaderLength() > maxWidth
        for ((index, entry) in supers.withIndex()) {
            gap(entry, if (index == 0 && !wrapSupers) " " else "\n$baseIndent$oneIndent")
            val args = (entry as? KtSuperTypeCallEntry)?.valueArgumentList ?: continue
            val entryIndent = baseIndent + if (wrapSupers) oneIndent else ""
            val entryStartWidth = if (wrapSupers) entryIndent.length else {
                val text = StringBuilder(prefix)
                for ((i, leaf) in code.withIndex()) {
                    if (leaf.startOffset >= entry.textOffset) break
                    if (i > 0) text.append(desired[leaf].orEmpty())
                    text.append(leaf.text)
                }
                text.toString().substringAfterLast('\n').length + 1
            }
            val wrapArguments = wrappedBaseArguments && wrapSupers && args.arguments.isNotEmpty() || entryStartWidth + inlineLength(entry) + bodySuffixWidth > maxWidth
            for ((argIndex, argument) in args.arguments.withIndex()) {
                gap(argument, if (wrapArguments) "\n$entryIndent$oneIndent" else if (argIndex == 0) "" else " ")
            }
            gap(args.rightParenthesis, if (wrapArguments && args.arguments.isNotEmpty()) "\n$entryIndent" else "")
        }
        gap(bodyBrace, " ")
        // Reverse order keeps source offsets valid. Only whitespace leaves are changed or inserted.
        for ((leaf, whitespace) in desired.entries.sortedByDescending { it.key.startOffset }) {
            val previous = leaf.prevLeaf
            val actual = if (previous?.elementType == ElementType.WHITE_SPACE) previous.text else ""
            if (actual != whitespace) {
                emit(leaf.startOffset, "Use the shared class header layout", true).ifAutocorrectAllowed {
                    if (whitespace.isEmpty() && previous?.elementType == ElementType.WHITE_SPACE) previous.remove()
                    else leaf.upsertWhitespaceBeforeMe(whitespace)
                }
            }
        }
    }

    private fun descendants(root: PsiElement): List<PsiElement> =
        PsiTreeUtil.collectElements(root) { true }.toList()
}
