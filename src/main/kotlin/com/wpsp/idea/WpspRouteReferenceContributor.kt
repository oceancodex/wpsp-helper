package com.wpsp.helper

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.*
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.jetbrains.php.lang.psi.elements.impl.ClassConstantReferenceImpl

/**
 * Tạo reference cho tham số thứ 2 của:
 *   anyNs\Funcs::route('Apis' | Apis::class, 'route.name')
 *   any_prefix_route('Apis' | Apis::class, 'route.name')   // function name ends with _route
 *   $this->route('Apis' | Apis::class, 'route.name') / $obj->route(...)
 */
class WpspRouteReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(StringLiteralExpression::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val literal = element as? StringLiteralExpression ?: return PsiReference.EMPTY_ARRAY
                    val paramList = literal.parent as? ParameterList ?: return PsiReference.EMPTY_ARRAY

                    // Chỉ hoạt động khi đã có ít nhất 1 JSON
                    if (WpspRouteIndex.routes(literal.project).isEmpty()) return PsiReference.EMPTY_ARRAY

                    val params = paramList.parameters
                    val idx = params.indexOf(literal)
                    if (idx != 1) return PsiReference.EMPTY_ARRAY

                    val routeName = literal.contents ?: return PsiReference.EMPTY_ARRAY
                    if (routeName.isBlank()) return PsiReference.EMPTY_ARRAY

                    val routeClassHint = extractRouteClassName(params.getOrNull(0))

                    val call = paramList.parent

                    // function: *_route(...)
                    if (call is FunctionReference) {
                        val fn = call.name ?: ""
                        if (fn.endsWith("_route")) {
                            return arrayOf(WpspRouteReference(literal, routeName, literal.project, routeClassHint))
                        }
                    }

                    // method: ::route(...) hoặc ->route(...)
                    if (call is MethodReference) {
                        val mName = call.name ?: return PsiReference.EMPTY_ARRAY
                        if (mName == "route") {
                            val cr = call.classReference
                            if (cr is ClassReference) {
                                val name = cr.type?.toString() ?: cr.name ?: ""
                                if (name.endsWith("\\Funcs") || name == "Funcs") {
                                    return arrayOf(WpspRouteReference(literal, routeName, literal.project, routeClassHint))
                                }
                            } else {
                                // instance call $this->route() / $obj->route()
                                return arrayOf(WpspRouteReference(literal, routeName, literal.project, routeClassHint))
                            }
                        }
                    }

                    return PsiReference.EMPTY_ARRAY
                }
            }
        )
    }

    private fun extractRouteClassName(firstParam: PsiElement?): String? {
        when (firstParam) {
            is StringLiteralExpression -> {
                val s = firstParam.contents?.trim()
                if (!s.isNullOrBlank()) return s
            }
            is ClassConstantReference -> {
                // Apis::class -> lấy tên class (không cần namespace)
                val classRef = firstParam.classReference
                val typeText = (classRef as? ClassReference)?.type?.toString()
                val raw = typeText ?: classRef?.name
                if (!raw.isNullOrBlank()) {
                    // Chuẩn hóa về tên đơn giản, ví dụ \WPSP\routes\Apis -> Apis
                    val simple = raw.substringAfterLast("\\")
                    if (simple.isNotBlank()) return simple
                }
            }
        }
        return null
    }
}
