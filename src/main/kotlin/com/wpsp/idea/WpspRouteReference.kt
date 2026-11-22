package com.wpsp.helper

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.jetbrains.php.lang.psi.elements.ClassConstantReference
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import java.io.File

class WpspRouteReference(
	element: StringLiteralExpression,
	private val routeName: String,
	private val project: Project,
	private val routeClassHint: String?
) : PsiReferenceBase<StringLiteralExpression>(element, literalContentRange(element), false) {

	companion object {
		private fun literalContentRange(e: StringLiteralExpression): TextRange {
			val t = e.text
			return if (t.length >= 2) TextRange(1, t.length - 1) else TextRange(0, t.length)
		}
	}

	override fun resolve(): PsiElement? {
		val list = WpspRouteIndex.routesFor(element)[routeName] ?: return null

		val filtered = if (routeClassHint.isNullOrBlank()) list
		else list.filter { File(it.fileRel).nameWithoutExtension.equals(routeClassHint, ignoreCase = true) }
		if (filtered.isEmpty()) return null

		val target = chooseBest(filtered, routeClassHint)

		val vf = WpspRouteIndex.resolveVirtualFile(project, target) ?: return null
		val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
		val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return psiFile
		if (doc.lineCount == 0) return psiFile

		// JSON line = 1-based → 0-based
		var lineIndex = (target.line - 1).coerceIn(0, doc.lineCount - 1)
		repeat(5) {
			val text = doc.getText(TextRange(doc.getLineStartOffset(lineIndex), doc.getLineEndOffset(lineIndex))).trim()
			if (text.isNotEmpty()) return@repeat
			lineIndex = (lineIndex + 1).coerceAtMost(doc.lineCount - 1)
		}

		val offset = doc.getLineEndOffset(lineIndex).coerceAtMost(doc.textLength - 1).coerceAtLeast(0)
		return psiFile.findElementAt(offset) ?: psiFile
	}

	override fun getVariants(): Array<Any> {
		val routes = WpspRouteIndex.routesFor(element)
		val params = (element.parent as? ParameterList)?.parameters
		val hint = extractRouteClassNameLocal(params?.getOrNull(0)) ?: routeClassHint

		val variants = ArrayList<LookupElement>()
		for ((route, targets) in routes) {
			for (t in targets) {
				val tClass = File(t.fileRel).nameWithoutExtension
				if (!hint.isNullOrBlank() && !tClass.equals(hint, ignoreCase = true)) continue

				variants.add(
					LookupElementBuilder
						.create("$route@${t.jsonBaseDir}@${t.fileRel}", route)
						.withIcon(RouteIcons.ROUTE)
						.withTypeText(" [${File(t.fileRel).name}:${t.line}]", true)
				)
			}
		}
		return variants.toTypedArray()
	}

	private fun extractRouteClassNameLocal(firstParam: PsiElement?): String? = when (firstParam) {
		is StringLiteralExpression -> firstParam.contents.trim().takeIf { it.isNotEmpty() }
		is ClassConstantReference -> {
			val classRef = firstParam.classReference
			val raw = (classRef as? ClassReference)?.type?.toString() ?: classRef?.name
			raw?.substringAfterLast("\\")?.takeIf { it.isNotEmpty() }
		}

		else -> null
	}

	/** Ưu tiên file khớp hint, fallback entry đầu tiên theo thứ tự JSON. */
	private fun chooseBest(candidates: List<WpspRouteIndex.Target>, hint: String?): WpspRouteIndex.Target {
		if (candidates.size == 1 || hint.isNullOrBlank()) return candidates[0]
		val h = hint.lowercase()
		return candidates.firstOrNull { File(it.fileRel).name.equals("$h.php", ignoreCase = true) }
			?: candidates.firstOrNull { File(it.fileRel).name.lowercase().contains(h) }
			?: candidates[0]
	}
}