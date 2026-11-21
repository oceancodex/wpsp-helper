package com.wpsp.helper

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.*

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
                        parameters.position,
                        StringLiteralExpression::class.java
                    ) ?: return

                    val paramList = PsiTreeUtil.getParentOfType(
                        parameters.position,
                        ParameterList::class.java
                    ) ?: return

                    val index = paramList.parameters.indexOf(
                        PsiTreeUtil.getParentOfType(
                            parameters.position,
                            StringLiteralExpression::class.java
                        ) ?: parameters.position.parent
                    )

                    if (index != 1) return

                    // Lấy call expression chính xác trong mọi context
                    val call = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                        literal,
                        FunctionReference::class.java,
                        MethodReference::class.java
                    )

                    // Nếu không phải Function/Method call -> bỏ
                    if (call == null) return

                    // ĐIỀU KIỆN isRouteCall đã sửa trước đó
                    val isRouteCall = when (call) {
                        is FunctionReference -> {
                            val fn = call.name ?: ""
                            fn.endsWith("_route")
                        }
                        is MethodReference -> {
                            val name = call.name ?: ""
                            if (name.endsWith("_route")) {
                                true
                            } else if (name == "route") {
                                val classRef = call.classReference
                                if (classRef is ClassReference) {
                                    val fqn = classRef.fqn ?: classRef.name ?: ""
                                    fqn.endsWith("\\Funcs") || fqn.equals("Funcs", ignoreCase = true)
                                } else {
                                    true
                                }
                            } else false
                        }
                        else -> false
                    }

                    if (!isRouteCall) return

                    val allRoutes = WpspRouteIndex.routes(literal.project)
                    if (allRoutes.isEmpty()) return

                    val allowed = allRoutes.keys.map { it.lowercase() }.toSet()
                    val valid = mutableListOf<LookupElement>()

                    // Thu hết suggestions của contributors khác và lọc
                    result.runRemainingContributors(parameters) { completion ->
                        val s = completion.lookupElement.lookupString.lowercase()
                        if (s in allowed) {
                            valid.add(completion.lookupElement)
                        }
                    }

                    // Nếu chưa có route nào thì tự sinh
                    if (valid.isEmpty()) {
                        for (route in allRoutes.keys) {
                            valid.add(LookupElementBuilder.create(route))
                        }
                    }

                    // Clear danh sách + add routes duy nhất
                    for (v in valid) {
                        result.addElement(v)
                    }

                    // Chặn toàn bộ contributors còn lại
                    result.stopHere()
                }

            }
        )
    }
}
