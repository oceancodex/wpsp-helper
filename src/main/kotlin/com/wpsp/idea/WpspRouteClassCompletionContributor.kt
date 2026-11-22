package com.wpsp.helper

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Gợi ý ROUTE CLASS cho tham số [0]:
 *   Funcs::route('<caret>', ...)
 *   wpsp_route('<caret>', ...)
 *
 * Danh sách class = tên các file route (Apis, Ajaxs, AdminPages, ...) xuất hiện trong
 * `.wpsp-routes.json` của ĐÚNG plugin chứa file đang gõ.
 */
class WpspRouteClassCompletionContributor : CompletionContributor() {

	init {
		extend(
			CompletionType.BASIC,
			PlatformPatterns.psiElement(),
			object : CompletionProvider<CompletionParameters>() {
				override fun addCompletions(
					parameters: CompletionParameters,
					context: ProcessingContext,
					result: CompletionResultSet
				) {
					// Caret phải nằm trong string literal...
					val literal = PsiTreeUtil.getParentOfType(
						parameters.position, StringLiteralExpression::class.java
					) ?: return

					// ...là tham số trực tiếp ở vị trí [0]...
					val paramList = literal.parent as? ParameterList ?: return
					if (paramList.parameters.indexOf(literal) != WpspRouteCalls.CLASS_ARG) return

					// ...của một lời gọi route WPSP.
					if (!WpspRouteCalls.isWpspRouteCall(paramList.parent)) return

					// Route của plugin đang mở file (rỗng nếu không xác định được plugin).
					val routes = WpspRouteIndex.routesFor(parameters)
					if (routes.isEmpty()) return

					// Đếm số route theo từng class để hiển thị bên phải.
					val counts = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
					for ((_, targets) in routes) {
						targets.map { WpspRouteCalls.groupOf(it) }.distinct().forEach { g ->
							counts[g] = (counts[g] ?: 0) + 1
						}
					}

					// Prefix = phần đã gõ trong chuỗi.
					val rs = result.withPrefixMatcher(WpspRouteCalls.typedPrefix(literal))
					for ((group, count) in counts) {
						rs.addElement(
							LookupElementBuilder.create(group)
								.withIcon(RouteIcons.ROUTE)
								.withTypeText("$count routes", true)
						)
					}

					// Không cho contributor khác (PHP, word completion...) chen gợi ý vào.
					result.stopHere()
				}
			}
		)
	}
}