package com.wpsp.helper

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.ClassConstantReference
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import java.io.File

/**
 * Các hàm dùng chung để nhận diện lời gọi route WPSP và đọc tham số.
 *
 * Cú pháp được hỗ trợ:
 *   Funcs::route('AdminPages', 'route.name', ['param'])     // \Any\Ns\Funcs::route
 *   $this->route(...), $obj->route(...)                     // instance call
 *   wpsp_route(...), xxx_route(...)                         // function có hậu tố _route
 *   Route::get('route.name', ..., [...])                    // facade (chỉ dùng cho route params)
 *
 * Vị trí tham số với Funcs::route / *_route:
 *   [0] = route class (group)   vd 'AdminPages' hoặc AdminPages::class
 *   [1] = route name            vd 'wpsp.users.list'
 *   [2] = mảng route params     vd ['id' => 1]
 */
object WpspRouteCalls {
	const val CLASS_ARG = 0
	const val NAME_ARG = 1
	const val PARAMS_ARG = 2

	/** Có phải `Funcs::route`, `$x->route` hoặc `*_route()` không. */
	fun isWpspRouteCall(call: PsiElement?): Boolean {
		val ref = call as? FunctionReference ?: return false   // MethodReference cũng là FunctionReference
		val name = ref.name ?: return false

		if (name.endsWith("_route", ignoreCase = true)) return true

		if (ref is MethodReference && name.equals("route", ignoreCase = true)) {
			val cr = ref.classReference
			if (cr is ClassReference) {
				// Static call: chỉ chấp nhận class Funcs (có hoặc không namespace)
				val cls = cr.fqn ?: cr.name ?: return false
				return cls.equals("Funcs", ignoreCase = true) || cls.endsWith("\\Funcs", ignoreCase = true)
			}
			// Instance call: $this->route(), $obj->route()
			return true
		}
		return false
	}

	/** `Route::<method>(...)` — tên route nằm ở tham số [0]. */
	fun isRouteFacadeCall(call: PsiElement?): Boolean {
		val ref = call as? MethodReference ?: return false
		val cr = ref.classReference as? ClassReference ?: return false
		val cls = cr.fqn ?: cr.name ?: return false
		return cls.equals("Route", ignoreCase = true) || cls.endsWith("\\Route", ignoreCase = true)
	}

	/**
	 * Lấy tên route class từ tham số [0]:
	 *   'Apis'          -> "Apis"
	 *   Apis::class     -> "Apis"
	 *   \Ns\Apis::class -> "Apis"
	 *   biến / biểu thức khác -> null (không lọc theo class)
	 */
	fun classHint(arg: PsiElement?): String? = when (arg) {
		is StringLiteralExpression -> arg.contents.trim()
			.takeIf { it.isNotEmpty() && !it.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) }

		is ClassConstantReference -> (arg.classReference as? ClassReference)?.name
			?.substringAfterLast('\\')
			?.takeIf { it.isNotEmpty() }

		else -> null
	}

	/** Route class (group) của target = tên file route không đuôi: "routes/Apis.php" -> "Apis". */
	fun groupOf(t: WpspRouteIndex.Target): String = File(t.fileRel).nameWithoutExtension

	/** Target có thuộc class [hint] không (hint rỗng = không lọc). */
	fun matchesClass(t: WpspRouteIndex.Target, hint: String?): Boolean =
		hint.isNullOrBlank() || groupOf(t).equals(hint, ignoreCase = true)

	/**
	 * Phần người dùng đã gõ trong string literal, tính tới caret.
	 * Khi completion, IntelliJ chèn DUMMY_IDENTIFIER tại caret vào bản copy,
	 * nên cắt trước chuỗi đó là ra đúng prefix (kể cả khi tên route có dấu chấm).
	 */
	fun typedPrefix(literal: StringLiteralExpression): String =
		literal.contents.substringBefore(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED)

	/** Hiển thị "AdminPages.php:101" làm tail text. */
	fun location(t: WpspRouteIndex.Target): String = "${File(t.fileRel).name}:${t.line}"
}