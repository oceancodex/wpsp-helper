package com.wpsp.helper

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Gợi ý ROUTE NAME cho tham số [1]:
 *   Funcs::route('AdminPages', '<caret>')
 *   wpsp_route('AdminPages', '<caret>')
 *
 * - Chỉ lấy route trong `.wpsp-routes.json` của plugin chứa file đang gõ.
 * - Nếu tham số [0] có class ('AdminPages' / AdminPages::class) thì chỉ hiện route thuộc class đó.
 * - Hoạt động trong .php, .blade.php, {{ }}, {!! !!}.
 */
class WpspRouteCompletionContributor : CompletionContributor() {

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
					val literal = PsiTreeUtil.getParentOfType(
						parameters.position, StringLiteralExpression::class.java
					) ?: return

					// Phải là tham số trực tiếp ở vị trí [1] của route call.
					val paramList = literal.parent as? ParameterList ?: return
					val args = paramList.parameters
					if (args.indexOf(literal) != WpspRouteCalls.NAME_ARG) return
					if (!WpspRouteCalls.isWpspRouteCall(paramList.parent)) return

					val routes = WpspRouteIndex.routesFor(parameters)
					if (routes.isEmpty()) return

					// Lọc theo class ở tham số [0] (nếu đọc được).
					val classHint = WpspRouteCalls.classHint(args.getOrNull(WpspRouteCalls.CLASS_ARG))

					val rs = result.withPrefixMatcher(WpspRouteCalls.typedPrefix(literal))
					for ((route, targets) in routes) {
						val matched = targets.filter { WpspRouteCalls.matchesClass(it, classHint) }
						if (matched.isEmpty()) continue

						// Mỗi route chỉ 1 dòng gợi ý; tail text là vị trí khai báo đầu tiên.
						rs.addElement(
							LookupElementBuilder.create(route)
								.withIcon(RouteIcons.ROUTE)
								.withTypeText("[${WpspRouteCalls.location(matched.first())}]", true)
						)
					}

					// Chặn mọi nguồn gợi ý khác (kể cả PsiReference.getVariants, word completion).
					result.stopHere()
				}
			}
		)
	}
}