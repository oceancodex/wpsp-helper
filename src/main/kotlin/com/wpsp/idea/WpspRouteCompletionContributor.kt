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
						parameters.position, StringLiteralExpression::class.java
					) ?: return

					val paramList = literal.parent as? ParameterList ?: return
					if (paramList.parameters.indexOf(literal) != 1) return

					val call = paramList.parent as? FunctionReference ?: return
					if (!isRouteCall(call)) return

					// Chỉ route của plugin chứa file đang edit
					val routes = WpspRouteIndex.routesFor(literal)
					if (routes.isEmpty()) return

					val allowed = routes.keys.map { it.lowercase() }.toSet()
					val valid = mutableListOf<LookupElement>()

					result.runRemainingContributors(parameters) { completion ->
						val s = completion.lookupElement.lookupString.lowercase()
						if (s in allowed) valid.add(completion.lookupElement)
					}

					if (valid.isEmpty()) {
						routes.keys.forEach { valid.add(LookupElementBuilder.create(it).withIcon(RouteIcons.ROUTE)) }
					}

					valid.forEach { result.addElement(it) }
					result.stopHere()
				}
			}
		)
	}

	private fun isRouteCall(call: FunctionReference): Boolean {
		val name = call.name ?: return false
		if (name.endsWith("_route")) return true
		if (call is MethodReference && name == "route") {
			val classRef = call.classReference as? ClassReference ?: return true // $this->route()
			val fqn = classRef.fqn ?: classRef.name ?: ""
			return fqn.endsWith("\\Funcs") || fqn.equals("Funcs", ignoreCase = true)
		}
		return false
	}
}