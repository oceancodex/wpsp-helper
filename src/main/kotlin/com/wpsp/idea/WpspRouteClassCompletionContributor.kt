package com.wpsp.helper

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.*

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
                    // phải là string literal
                    val literal = PsiTreeUtil.getParentOfType(
                        parameters.position,
                        StringLiteralExpression::class.java
                    ) ?: return

                    // tìm parameter list
                    val paramList = PsiTreeUtil.getParentOfType(
                        literal,
                        ParameterList::class.java,
                        true
                    ) ?: return

                    val params = paramList.parameters
                    val index = params.indexOf(literal)
                    if (index != 0) return   // chỉ param1

                    // kiểm tra là route call
                    val call = paramList.parent
                    if (!isRouteCall(call)) return

                    val project = literal.project

                    // lấy map từ JSON
                    val map = WpspRouteIndex.routes(project)
                    if (map.isEmpty()) return

                    // Lấy danh sách group name từ JSON: Apis, Ajaxs, AdminPages, RewriteFrontPages
                    val groupNames = map.values
                        .flatMap { list -> list }
                        .map { target ->
                            // extract group = folder name without .php
                            target.fileRel.substringAfter("routes/").substringBefore(".php")
                        }
                        .toSet()

                    for (g in groupNames) {
                        result.addElement(
                            LookupElementBuilder
                                .create(g)
                                .withIcon(RouteIcons.ROUTE)
                        )
                    }

                    result.stopHere()
                }
            }
        )
    }

    private fun isRouteCall(call: Any?): Boolean {
        return when (call) {
            is FunctionReference -> call.name?.endsWith("_route") == true
                    || call.name?.endsWith("route") == true
            is MethodReference -> {
                val name = call.name ?: return false
                name == "route" || name.endsWith("_route")
            }
            else -> false
        }
    }
}
