package com.wpsp.helper

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Gợi ý ROUTE PARAMETERS cho tham số [2] (mảng):
 *   Funcs::route('AdminPages', 'route.name', ['<caret>'])   // tên route ở [1]
 *   xxx_route('AdminPages', 'route.name', [<caret>])        // tên route ở [1]
 *   Route::get('route.name', [Ctrl::class, 'm'], ['<caret>']) // tên route ở [0]
 *
 * Chỉ gợi ý `route_parameters` của đúng route đó, trong đúng plugin chứa file đang gõ.
 */
class WpspRouteParamCompletionContributor : CompletionContributor() {

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
					val position = parameters.position

					// Literal đang gõ (null nếu gõ không có dấu nháy, vd [id<caret>]).
					val literal = PsiTreeUtil.getParentOfType(position, StringLiteralExpression::class.java)

					// Mảng gần nhất chứa caret.
					val array = PsiTreeUtil.getParentOfType(position, ArrayCreationExpression::class.java) ?: return

					// Bỏ qua nếu caret ở vế VALUE của 'key' => 'value' (chỉ gợi ý cho key).
					val hash = PsiTreeUtil.getParentOfType(position, ArrayHashElement::class.java, true)
					val hashValue = hash?.value
					if (hash != null && hash.parent == array && hashValue != null &&
						PsiTreeUtil.isAncestor(hashValue, position, false)
					) return

					// Mảng phải là tham số trực tiếp ở vị trí [2].
					val paramList = array.parent as? ParameterList ?: return
					val args = paramList.parameters
					if (args.indexOf(array) != WpspRouteCalls.PARAMS_ARG) return

					// Xác định vị trí tên route & class hint theo kiểu lời gọi.
					val call = paramList.parent
					val routeNameIdx: Int
					val classHint: String?
					when {
						WpspRouteCalls.isWpspRouteCall(call) -> {
							routeNameIdx = WpspRouteCalls.NAME_ARG
							classHint = WpspRouteCalls.classHint(args.getOrNull(WpspRouteCalls.CLASS_ARG))
						}

						WpspRouteCalls.isRouteFacadeCall(call) -> {
							routeNameIdx = 0
							classHint = null
						}

						else -> return
					}

					// Tên route phải là string literal đã gõ xong.
					val routeName = (args.getOrNull(routeNameIdx) as? StringLiteralExpression)
						?.contents?.trim()
					if (routeName.isNullOrBlank()) return

					// Target của route trong plugin hiện tại, lọc theo class.
					val targets = WpspRouteIndex.routesFor(parameters)[routeName] ?: return
					val matched = targets.filter { WpspRouteCalls.matchesClass(it, classHint) }
					if (matched.isEmpty()) return

					val all = LinkedHashSet<String>()
					matched.forEach { all.addAll(it.routeParameters) }
					if (all.isEmpty()) return   // route không có param → không gợi ý gì

					// Các param đã viết trong mảng (trừ literal đang gõ) → không gợi ý lại.
					val used = PsiTreeUtil.findChildrenOfType(array, StringLiteralExpression::class.java)
						.filter { it != literal }
						.map { it.contents.trim() }
						.toSet()

					val inQuotes = literal != null
					val rs = if (literal != null)
						result.withPrefixMatcher(WpspRouteCalls.typedPrefix(literal)) else result

					for (p in all) {
						if (p in used) continue

						var b = LookupElementBuilder.create(p)
							.withIcon(RouteIcons.ROUTE)
							.withTypeText("Parameter", true)

						// Gõ không có nháy → khi chọn tự bọc thành 'param'.
						if (!inQuotes) {
							b = b.withInsertHandler { ctx, _ ->
								ctx.document.replaceString(ctx.startOffset, ctx.tailOffset, "'$p'")
								ctx.editor.caretModel.moveToOffset(ctx.startOffset + p.length + 2)
							}
						}
						rs.addElement(b)
					}

					result.stopHere()
				}
			}
		)
	}
}