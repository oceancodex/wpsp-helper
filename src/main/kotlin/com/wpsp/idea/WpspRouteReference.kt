package com.wpsp.helper

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.openapi.util.TextRange
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import java.io.File
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.ClassConstantReference
import com.intellij.openapi.diagnostic.Logger

class WpspRouteReference(
    element: StringLiteralExpression,
    private val routeName: String,
    private val project: Project,
    private val routeClassHint: String? // "Apis" từ param[0], có thể null
) : PsiReferenceBase<StringLiteralExpression>(element, literalContentRange(element), false) {

    private val LOG = Logger.getInstance(WpspRouteReference::class.java)

    companion object {
        private fun literalContentRange(e: StringLiteralExpression): TextRange {
            val t = e.text
            return if (t.length >= 2) TextRange(1, t.length - 1) else TextRange(0, t.length)
        }
    }

    override fun resolve(): PsiElement? {
        val map = WpspRouteIndex.routes(project)
        val list = map[routeName] ?: return null

        // Lọc theo routeClassHint
        val filtered = routeClassHint?.let { hint ->
            list.filter { target ->
                File(target.fileRel).nameWithoutExtension.equals(hint, ignoreCase = true)
            }
        }?.takeIf { it.isNotEmpty() } ?: return null   // không khớp -> không resolve

        val target = chooseBest(filtered, routeClassHint)

        val vf = WpspRouteIndex.resolveVirtualFile(project, target) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return psiFile

        // JSON line = 1-based, convert to 0-based
        var lineIndex = (target.line - 1).coerceIn(0, doc.lineCount - 1)

        // Tìm dòng có code (tránh dòng trắng/comment)
        var attempts = 0
        while (attempts < 5) {
            val lineText = doc.getText(
                TextRange(doc.getLineStartOffset(lineIndex), doc.getLineEndOffset(lineIndex))
            ).trim()

            if (lineText.isNotEmpty()) break

            lineIndex = (lineIndex + 1).coerceAtMost(doc.lineCount - 1)
            attempts++
        }

        val offset = doc.getLineEndOffset(lineIndex).coerceAtMost(doc.textLength - 1)
        return psiFile.findElementAt(offset) ?: psiFile
    }

    override fun getVariants(): Array<Any> {
        val vf = this.element.containingFile?.virtualFile
        LOG.warn("========= FILE_PATH=${vf?.path}")

        val map = WpspRouteIndex.routes(project)
        val variants = ArrayList<LookupElement>()

        // 1: Lấy plugin hiện tại từ path
        val currentPlugin = detectPluginFromFile(this.element)

        // 2: Lấy group class từ param1
        val paramList = this.element.parent as? ParameterList
        val params = paramList?.parameters
        val hint = extractRouteClassNameLocal(params?.getOrNull(0)) ?: routeClassHint

        for ((route, targets) in map) {
            for (t in targets) {
                val tPlugin = pluginOfTarget(t)
                val tClass = File(t.fileRel).nameWithoutExtension

                LOG.warn("DEBUG currentPlugin=$currentPlugin hint=$hint tPlugin=$tPlugin tClass=$tClass route=$route")

                // Lọc theo plugin
                if (currentPlugin != null &&
                    !tPlugin.equals(currentPlugin, ignoreCase = true)) continue

                // Lọc theo class
                if (!hint.isNullOrBlank() &&
                    !tClass.equals(hint, ignoreCase = true)) continue

                val label = prettyFileAndLine(t)

                variants.add(
                    LookupElementBuilder
                        .create("${route}@${tPlugin}@${t.fileRel}", route)
                        .withIcon(RouteIcons.ROUTE)
                        .withTypeText(" [$label]", true)
                )
            }
        }

        return variants.toTypedArray()
    }

    private fun detectPluginFromFile(element: PsiElement): String? {
        val file = element.containingFile?.originalFile ?: return null
        val vf = file.virtualFile ?: return null
        val path = vf.path.replace('\\', '/')

        val marker = "/plugins/"
        val idx = path.indexOf(marker)
        if (idx < 0) return null

        return path.substring(idx + marker.length)
            .substringBefore('/')
            .takeIf { it.isNotBlank() }
    }


    /** Fallback lấy plugin đang active nếu detectCurrentPlugin() không ra */
    private fun activePluginOrNull(): String? {
        // 1) Ưu tiên lấy từ file đang gõ
        detectCurrentPlugin(this.element)?.let { return it }

        // 2) Nếu project mở trực tiếp trong 1 plugin: .../wp-content/plugins/<plugin>
        val base = project.basePath ?: return null
        val f = File(base)
        if (f.parentFile?.name == "plugins" && f.parentFile?.parentFile?.name == "wp-content") {
            return f.name
        }

        // 3) Đang mở ở public_html / wp-content / plugins => không xác định 1 plugin duy nhất
        return null
    }


    /** Lấy hint từ tham số 1 ngay tại chỗ (string 'Apis' hoặc Apis::class) */
    private fun extractRouteClassNameLocal(firstParam: PsiElement?): String? {
        return when (firstParam) {
            is StringLiteralExpression -> firstParam.contents?.trim()?.takeIf { it.isNotEmpty() }
            is ClassConstantReference -> {
                val classRef = firstParam.classReference
                val typeText = (classRef as? ClassReference)?.type?.toString()
                val raw = typeText ?: classRef?.name
                raw?.substringAfterLast("\\")?.takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }

    private fun detectPluginFromProjectBase(): String? {
        val base = project.basePath ?: return null
        val root = File(base)

        // Nếu project mở trực tiếp trong plugin (…/plugins/wpsp)
        if (root.parentFile?.name == "plugins") {
            return root.name
        }

        // Tìm thư mục plugins bên dưới project
        val pluginsDir = File("$base/wp-content/plugins")
        if (!pluginsDir.isDirectory) return null

        val plugins = pluginsDir.listFiles()?.filter { it.isDirectory } ?: return null
        if (plugins.size == 1) {
            // Chỉ 1 plugin → chắc chắn là plugin đang dev
            return plugins.first().name
        }

        // Không xác định được chính xác
        return null
    }


    /**
     * Chọn target tốt nhất:
     *  1) Ưu tiên target thuộc đúng plugin hiện tại (file đang mở nằm trong plugin nào)
     *  2) Trong nhóm đã ưu tiên, áp dụng hint từ tham số thứ nhất (Apis / Apis::class)
     *  3) Fallback: entry đầu tiên theo thứ tự trong JSON
     */
    private fun chooseBest(
        candidates: List<WpspRouteIndex.Target>,
        hint: String?
    ): WpspRouteIndex.Target {
        if (candidates.size == 1) return candidates[0]

        // 1) Ưu tiên theo plugin hiện tại
        val current = detectCurrentPlugin(this.element)
        val pool = if (current != null) {
            val same = candidates.filter { pluginOfTarget(it).equals(current, ignoreCase = true) }
            if (same.isNotEmpty()) same else candidates
        } else candidates

        // 2) Ưu tiên theo routeClassHint trong "pool"
        if (!hint.isNullOrBlank()) {
            val hintLower = hint.lowercase()
            pool.firstOrNull { baseName(it).equals("$hintLower.php", ignoreCase = true) }?.let { return it }
            pool.firstOrNull { baseName(it).contains(hintLower) }?.let { return it }
        }

        // 3) Fallback: lấy entry đầu tiên theo thứ tự JSON
        return pool[0]
    }

    /** Lấy tên file lowercase từ target (ví dụ "Apis.php" -> "apis.php") */
    private fun baseName(t: WpspRouteIndex.Target): String {
        return File(t.fileRel).name.lowercase()
    }

    /** Hiển thị tên file + dòng để làm tail text trong gợi ý */
    private fun prettyFileAndLine(t: WpspRouteIndex.Target): String {
        val name = File(t.fileRel).name
        return "$name:${t.line}"  // vẫn hiển thị 1-based như JSON
    }

    /** Lấy tên plugin của file đang mở, từ đường dẫn /wp-content/plugins/<plugin>/... */
    private fun detectCurrentPlugin(element: PsiElement): String? {
        var vf = element.containingFile?.virtualFile ?: return null
        var path = vf.path.replace('\\', '/')

        // Đi ngược lên cho đến khi gặp thư mục plugins
        while (true) {
            val idx = path.indexOf("/wp-content/plugins/")
            if (idx >= 0) {
                val rest = path.substring(idx + "/wp-content/plugins/".length)
                val plugin = rest.substringBefore('/')
                return plugin.takeIf { it.isNotEmpty() }
            }

            // Lùi lên: ví dụ nếu file đang nằm deep trong vendor hoặc submodule
            val parent = File(path).parent ?: break
            path = parent.replace('\\', '/')
        }

        return null
    }

    /** Tên plugin của một target lấy từ thư mục chứa JSON (.wpsp-routes.json) */
    private fun pluginOfTarget(t: WpspRouteIndex.Target): String =
        File(t.jsonBaseDir).name
}
