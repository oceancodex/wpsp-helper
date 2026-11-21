package com.wpsp.helper

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.*
import java.io.File

/**
 * Gợi ý route_parameters cho tham số thứ 3 (mảng args) của:
 *   Funcs::route('AdminPages', 'route.name', ['<caret>'])   // tên route ở param[1]
 *   xxx_route('AdminPages', 'route.name', [...])            // tên route ở param[1]
 *   Route::get('route.name', [Ctrl::class, 'm'], [...])     // tên route ở param[0]
 *
 * Chỉ gợi ý đúng route_parameters của route đó, không fallback sang route khác.
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
					val literal = PsiTreeUtil.getParentOfType(position, StringLiteralExpression::class.java)

					val array = PsiTreeUtil.getParentOfType(position, ArrayCreationExpression::class.java)
						?: return

					// Bỏ qua nếu đang ở vế value của 'key' => 'value'
					val hash = PsiTreeUtil.getParentOfType(position, ArrayHashElement::class.java, true)
					if (hash != null && hash.parent == array &&
						hash.value != null && PsiTreeUtil.isAncestor(hash.value!!, position, false)
					) return

					// Mảng phải là tham số thứ 3
					val paramList = array.parent as? ParameterList ?: return
					val params = paramList.parameters
					if (params.indexOf(array) != 2) return

					// Xác định kiểu call -> vị trí tên route + class hint
					val call = paramList.parent as? FunctionReference ?: return
					val callName = call.name?.lowercase() ?: return

					val routeNameIdx: Int
					val classHint: String?
					when {
						callName == "route" || callName.endsWith("_route") -> {
							routeNameIdx = 1
							classHint = extractClassHint(params.getOrNull(0))
						}
						isRouteFacadeCall(call) -> {
							routeNameIdx = 0
							classHint = null
						}
						else -> return
					}

					// Tên route phải là string literal đã gõ xong
					val routeName = (params.getOrNull(routeNameIdx) as? StringLiteralExpression)
						?.contents?.trim()
					if (routeName.isNullOrBlank()) return

					val routes = WpspRouteIndex.routes(position.project)
					val targets = routes[routeName] ?: return

					val currentPlugin = detectPlugin(parameters.originalFile.virtualFile?.path)

					val matched = targets.filter { t ->
						val pluginOk = currentPlugin == null ||
								File(t.jsonBaseDir).name.equals(currentPlugin, ignoreCase = true)
						val classOk = classHint.isNullOrBlank() ||
								File(t.fileRel).nameWithoutExtension.equals(classHint, ignoreCase = true)
						pluginOk && classOk
					}
					if (matched.isEmpty()) return

					val all = LinkedHashSet<String>()
					matched.forEach { all.addAll(it.routeParameters) }

					// Route không có param (vd wpsp.users.create) -> không gợi ý gì
					if (all.isEmpty()) return

					// Các param đã viết trong mảng (trừ literal đang gõ)
					val used = PsiTreeUtil.findChildrenOfType(array, StringLiteralExpression::class.java)
						.filter { it != literal }
						.mapNotNull { it.contents?.trim() }
						.toSet()

					val inQuotes = literal != null

					for (p in all) {
						if (p in used) continue

						var b = LookupElementBuilder.create(p)
							.withIcon(RouteIcons.ROUTE)
							.withTypeText("Parameter", true)

						if (!inQuotes) {
							b = b.withInsertHandler { ctx, _ ->
								ctx.document.replaceString(ctx.startOffset, ctx.tailOffset, "'$p'")
								ctx.editor.caretModel.moveToOffset(ctx.startOffset + p.length + 2)
							}
						}
						result.addElement(b)
					}

					result.stopHere()
				}
			}
		)
	}

	/** 'Apis' hoặc Apis::class -> "Apis" */
	private fun extractClassHint(first: PsiElement?): String? = when (first) {
		is StringLiteralExpression -> first.contents?.trim()?.takeIf { it.isNotEmpty() }
		is ClassConstantReference -> {
			val classRef = first.classReference
			val raw = (classRef as? ClassReference)?.type?.toString() ?: classRef?.name
			raw?.substringAfterLast("\\")?.takeIf { it.isNotEmpty() }
		}
		else -> null
	}

	private fun detectPlugin(path: String?): String? {
		val p = path?.replace('\\', '/') ?: return null
		val marker = "/wp-content/plugins/"
		val idx = p.indexOf(marker)
		if (idx < 0) return null
		return p.substring(idx + marker.length).substringBefore('/').takeIf { it.isNotBlank() }
	}

	/** Route::<bất kỳ method nào>(...) */
	private fun isRouteFacadeCall(call: FunctionReference): Boolean {
		if (call !is MethodReference) return false
		val cr = call.classReference as? ClassReference ?: return false
		val name = cr.fqn ?: cr.name ?: return false
		return name == "Route" || name.endsWith("\\Route")
	}
}