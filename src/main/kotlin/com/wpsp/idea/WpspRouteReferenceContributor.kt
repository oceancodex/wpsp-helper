package com.wpsp.helper

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Gắn PsiReference cho tham số [1] (route name) để Ctrl+Click / Go to Declaration
 * nhảy tới dòng khai báo route:
 *   Funcs::route('Apis' | Apis::class, 'route.name')
 *   any_prefix_route('Apis', 'route.name')
 *   $this->route('Apis', 'route.name') / $obj->route(...)
 *
 * Provider này được gọi cho RẤT nhiều string literal (cả khi highlight),
 * nên làm các kiểm tra rẻ trước, chỉ đụng tới index ở bước cuối.
 */
class WpspRouteReferenceContributor : PsiReferenceContributor() {

	override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
		registrar.registerReferenceProvider(
			PlatformPatterns.psiElement(StringLiteralExpression::class.java),
			object : PsiReferenceProvider() {
				override fun getReferencesByElement(
					element: PsiElement,
					context: ProcessingContext
				): Array<PsiReference> {
					val literal = element as? StringLiteralExpression ?: return PsiReference.EMPTY_ARRAY

					// 1) Phải là tham số [1] của route call (kiểm tra PSI, rất rẻ).
					val paramList = literal.parent as? ParameterList ?: return PsiReference.EMPTY_ARRAY
					val args = paramList.parameters
					if (args.indexOf(literal) != WpspRouteCalls.NAME_ARG) return PsiReference.EMPTY_ARRAY
					if (!WpspRouteCalls.isWpspRouteCall(paramList.parent)) return PsiReference.EMPTY_ARRAY

					val routeName = literal.contents
					if (routeName.isBlank()) return PsiReference.EMPTY_ARRAY

					// 2) File phải thuộc một plugin có .wpsp-routes.json (index đã cache).
					if (WpspRouteIndex.routesFor(literal).isEmpty()) return PsiReference.EMPTY_ARRAY

					val classHint = WpspRouteCalls.classHint(args.getOrNull(WpspRouteCalls.CLASS_ARG))
					return arrayOf(WpspRouteReference(literal, routeName, classHint))
				}
			}
		)
	}
}