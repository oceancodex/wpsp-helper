package com.wpsp.helper

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import java.io.File

/**
 * Reference từ chuỗi route name tới dòng khai báo route trong file routes/xxx.php.
 *
 * @param routeName      Tên route trong chuỗi.
 * @param routeClassHint Class ở tham số [0] (vd "Apis"), null nếu không đọc được → không lọc theo class.
 */
class WpspRouteReference(
	element: StringLiteralExpression,
	private val routeName: String,
	private val routeClassHint: String?
) : PsiReferenceBase<StringLiteralExpression>(element, contentRange(element), false) {

	companion object {
		/** Vùng reference = nội dung bên trong dấu nháy. */
		private fun contentRange(e: StringLiteralExpression): TextRange {
			val t = e.text
			return if (t.length >= 2) TextRange(1, t.length - 1) else TextRange(0, t.length)
		}

		/** Số dòng trống tối đa bỏ qua khi tìm dòng có code. */
		private const val MAX_SKIP_BLANK = 5
	}

	/** Ctrl+Click: nhảy tới dòng khai báo route trong plugin hiện tại. */
	override fun resolve(): PsiElement? {
		val list = WpspRouteIndex.routesFor(element)[routeName] ?: return null

		val filtered = list.filter { WpspRouteCalls.matchesClass(it, routeClassHint) }
		if (filtered.isEmpty()) return null

		val target = chooseBest(filtered, routeClassHint)
		val project = element.project

		val vf = WpspRouteIndex.resolveVirtualFile(target) ?: return null
		val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
		val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return psiFile
		if (doc.lineCount == 0) return psiFile

		// Dòng trong JSON là 1-based → đổi sang 0-based của Document.
		var lineIndex = (target.line - 1).coerceIn(0, doc.lineCount - 1)

		// Nếu rơi vào dòng trống thì dịch xuống tối đa vài dòng để gặp code.
		for (i in 0 until MAX_SKIP_BLANK) {
			val text = doc.getText(
				TextRange(doc.getLineStartOffset(lineIndex), doc.getLineEndOffset(lineIndex))
			).trim()
			if (text.isNotEmpty() || lineIndex >= doc.lineCount - 1) break
			lineIndex++
		}

		val offset = doc.getLineEndOffset(lineIndex).coerceIn(0, maxOf(0, doc.textLength - 1))
		return psiFile.findElementAt(offset) ?: psiFile
	}

	/**
	 * Gợi ý qua reference (dự phòng). Bình thường WpspRouteCompletionContributor đã
	 * gọi stopHere() nên phần này hiếm khi được dùng, nhưng vẫn lọc đúng plugin + class.
	 */
	override fun getVariants(): Array<Any> {
		val routes = WpspRouteIndex.routesFor(element)
		val args = (element.parent as? ParameterList)?.parameters
		val hint = WpspRouteCalls.classHint(args?.getOrNull(WpspRouteCalls.CLASS_ARG)) ?: routeClassHint

		val variants = ArrayList<LookupElement>()
		for ((route, targets) in routes) {
			val matched = targets.filter { WpspRouteCalls.matchesClass(it, hint) }
			if (matched.isEmpty()) continue
			variants.add(
				LookupElementBuilder.create(route)
					.withIcon(RouteIcons.ROUTE)
					.withTypeText("[${WpspRouteCalls.location(matched.first())}]", true)
			)
		}
		return variants.toTypedArray()
	}

	/**
	 * Chọn 1 target khi route khai báo ở nhiều chỗ:
	 *   1) file tên đúng "<Hint>.php"
	 *   2) file có chứa hint trong tên
	 *   3) entry đầu tiên theo thứ tự JSON
	 */
	private fun chooseBest(candidates: List<WpspRouteIndex.Target>, hint: String?): WpspRouteIndex.Target {
		if (candidates.size == 1 || hint.isNullOrBlank()) return candidates[0]
		val h = hint.lowercase()
		return candidates.firstOrNull { File(it.fileRel).name.equals("$h.php", ignoreCase = true) }
			?: candidates.firstOrNull { File(it.fileRel).name.lowercase().contains(h) }
			?: candidates[0]
	}
}
