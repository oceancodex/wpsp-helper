package com.wpsp.helper

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.injected.editor.EditorWindow
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Index route WPSP đọc từ file `.wpsp-routes.json` của từng plugin.
 *
 * NGUYÊN TẮC CHÍNH
 * ----------------
 * - Mỗi plugin có index RIÊNG, key = đường dẫn thư mục chứa `.wpsp-routes.json`
 *   (chính là thư mục gốc của plugin, ví dụ `F:/.../wp-content/plugins/wpsp`).
 * - Khi cần gợi ý / resolve, luôn xác định FILE ĐANG EDIT nằm trong plugin nào,
 *   rồi chỉ trả route của đúng plugin đó.
 * - Không xác định được plugin  →  trả RỖNG (không bao giờ gộp route của plugin khác).
 *
 * CÁC VỊ TRÍ MỞ PROJECT ĐƯỢC HỖ TRỢ
 * --------------------------------
 *   wordpress/                              → quét wordpress/wp-content/plugins/...
 *   wordpress/wp-content/                   → quét wp-content/plugins/...
 *   wordpress/wp-content/plugins/           → quét plugins/...
 *   wordpress/wp-content/plugins/wpsp/      → chỉ plugin wpsp
 *   wordpress/wp-content/plugins/wpsp-lite/ → chỉ plugin wpsp-lite
 *
 * CÁC LOẠI FILE ĐƯỢC HỖ TRỢ
 * -------------------------
 *   .php, .blade.php, PHP bên trong {{ }}, {!! !!}, @php ... @endphp.
 *   Với Blade, PHP là "injected language": PSI nằm trong một file ảo (VirtualFileWindow /
 *   LightVirtualFile) KHÔNG có thư mục cha → phải quy về file host thật trên đĩa
 *   (xem [contextVirtualFile]).
 */
object WpspRouteIndex {
	private val LOG = Logger.getInstance(WpspRouteIndex::class.java)
	private val gson = Gson()

	/** Tên file mapping nằm ở gốc mỗi plugin. */
	const val MAPPING_FILE = ".wpsp-routes.json"

	/**
	 * Một vị trí khai báo route.
	 *
	 * @property fileRel         Đường dẫn file route, tương đối so với thư mục plugin (vd "routes/Apis.php").
	 * @property line            Số dòng như trong JSON (1-based).
	 * @property jsonBaseDir     Thư mục chứa `.wpsp-routes.json` (= thư mục plugin), dạng VirtualFile.path (dùng '/').
	 * @property routeParameters Danh sách tham số route (`route_parameters` trong JSON).
	 */
	data class Target(
		val fileRel: String,
		val line: Int,
		val jsonBaseDir: String,
		val routeParameters: List<String> = emptyList()
	)

	/**
	 * Cache theo project:
	 *   pluginDir  ->  routeName  ->  danh sách Target (1 route có thể khai báo nhiều chỗ).
	 */
	private val cache: MutableMap<Project, Map<String, Map<String, List<Target>>>> = ConcurrentHashMap()

	/** Project đã đăng ký watcher (tránh đăng ký lặp). */
	private val watchSet = ConcurrentHashMap.newKeySet<Project>()

	// ==================================================================
	// PUBLIC API — mọi contributor / reference đều phải đi qua các hàm này
	// ==================================================================

	/**
	 * Dùng trong CompletionContributor.
	 *
	 * Cách chắc chắn nhất để biết file thật đang gõ: lấy editor cấp cao nhất
	 * (nếu đang trong vùng injected như Blade `{{ }}` thì editor là EditorWindow → lấy delegate),
	 * rồi lấy VirtualFile từ Document của editor đó.
	 */
	fun routesFor(parameters: CompletionParameters): Map<String, List<Target>> =
		routes(parameters.position.project, contextVirtualFile(parameters))

	/**
	 * Dùng trong PsiReference / PsiReferenceProvider (không có CompletionParameters).
	 */
	fun routesFor(element: PsiElement): Map<String, List<Target>> =
		routes(element.project, contextVirtualFile(element))

	/**
	 * Route của plugin chứa [contextFile].
	 * Trả rỗng nếu file null hoặc không nằm trong plugin nào có `.wpsp-routes.json`.
	 */
	fun routes(project: Project, contextFile: VirtualFile?): Map<String, List<Target>> {
		val file = contextFile ?: return emptyMap()
		val index = index(project)
		val pluginDir = findOwningPluginDir(file, index.keys)

		if (LOG.isDebugEnabled) {
			LOG.debug("WPSP ctx=${file.path} (${file.javaClass.simpleName}) -> plugin=$pluginDir; known=${index.keys}")
		}

		return pluginDir?.let { index[it] }.orEmpty()
	}

	/** Mở file route của target (đường dẫn tương đối tính từ thư mục plugin). */
	fun resolveVirtualFile(target: Target): VirtualFile? =
		LocalFileSystem.getInstance().findFileByIoFile(File(target.jsonBaseDir, target.fileRel))

	// ==================================================================
	// XÁC ĐỊNH FILE THẬT ĐANG EDIT
	// ==================================================================

	/** File thật đang gõ, lấy từ editor của completion. */
	fun contextVirtualFile(parameters: CompletionParameters): VirtualFile? {
		// Trong vùng injected (Blade {{ }}), editor là EditorWindow bọc editor thật.
		val editor = parameters.editor.let { if (it is EditorWindow) it.delegate else it }

		// Document của editor thật luôn gắn với file thật trên đĩa (vd dashboard.blade.php).
		FileDocumentManager.getInstance().getFile(editor.document)
			?.let { unwrap(it) }
			?.let { return it }

		// Fallback: suy ra từ PSI.
		return contextVirtualFile(parameters.position)
	}

	/**
	 * File thật chứa [element], xử lý 3 trường hợp:
	 *  1. Element nằm trong PHP injected (Blade) → lấy file host bằng InjectedLanguageManager.
	 *  2. Element nằm trong bản copy khi completion → `originalFile` trỏ về file gốc.
	 *  3. VirtualFile là file ảo (VirtualFileWindow / LightVirtualFile) → bóc ra file thật.
	 */
	fun contextVirtualFile(element: PsiElement): VirtualFile? {
		if (!element.isValid) return null

		val host: PsiFile = InjectedLanguageManager.getInstance(element.project).getTopLevelFile(element)
			?: element.containingFile
			?: return null

		val original = host.originalFile
		val vf = original.virtualFile ?: original.viewProvider.virtualFile
		return unwrap(vf)
	}

	/** Bóc các lớp file ảo cho tới khi gặp file thật. */
	private fun unwrap(file: VirtualFile?): VirtualFile? {
		var vf = file
		repeat(5) {
			vf = when (val v = vf) {
				is VirtualFileWindow -> v.delegate          // file injected → file host
				is LightVirtualFile -> v.originalFile ?: return v // file trong bộ nhớ → file gốc nếu có
				else -> return v                             // đã là file thật (hoặc null)
			}
		}
		return vf
	}

	/**
	 * Tìm thư mục plugin chứa [file].
	 *
	 * Dùng `FileUtil.isAncestor` để so sánh theo TỪNG THƯ MỤC (không phải so chuỗi `startsWith`),
	 * nên `plugins/wpsp` KHÔNG bị khớp nhầm với `plugins/wpsp-lite/...`.
	 * Hàm này cũng xử lý đúng không phân biệt hoa/thường trên Windows.
	 * Nếu có plugin lồng nhau thì lấy thư mục sâu nhất (đường dẫn dài nhất).
	 */
	private fun findOwningPluginDir(file: VirtualFile, pluginDirs: Set<String>): String? =
		pluginDirs
			.filter { FileUtil.isAncestor(it, file.path, false) }
			.maxByOrNull { it.length }

	// ==================================================================
	// ĐỌC & CACHE INDEX
	// ==================================================================

	private fun index(project: Project): Map<String, Map<String, List<Target>>> {
		return cache[project] ?: run {
			val idx = readAll(project)
			cache[project] = idx
			setupWatcher(project)
			idx
		}
	}

	/** Đọc mọi `.wpsp-routes.json` tìm thấy, mỗi file thành index riêng của plugin đó. */
	private fun readAll(project: Project): Map<String, Map<String, List<Target>>> {
		val result = LinkedHashMap<String, Map<String, List<Target>>>()

		for (jsonFile in findMappingFiles(project)) {
			try {
				val root = gson.fromJson(VfsUtilCore.loadText(jsonFile), JsonObject::class.java) ?: continue
				val pluginDir = jsonFile.parent ?: continue

				val routes = LinkedHashMap<String, MutableList<Target>>()
				parseJson(root, pluginDir.name, pluginDir.path, routes)

				if (routes.isNotEmpty()) result[pluginDir.path] = routes
				LOG.debug("WPSP loaded ${routes.size} routes from ${jsonFile.path}")
			} catch (t: Throwable) {
				LOG.warn("Failed to read ${jsonFile.path}: ${t.message}", t)
			}
		}
		return result
	}

	/**
	 * Tìm các file `.wpsp-routes.json` tùy theo thư mục đang mở làm project.
	 */
	private fun findMappingFiles(project: Project): List<VirtualFile> {
		val base = project.basePath ?: return emptyList()
		val projectDir = File(base)
		val pluginDirs = LinkedHashSet<File>()

		// Project chính là 1 plugin: .../wp-content/plugins/<plugin>,
		// hoặc thư mục gốc có sẵn .wpsp-routes.json (plugin mở độc lập / symlink).
		if (isPluginFolder(projectDir) || File(projectDir, MAPPING_FILE).isFile) {
			pluginDirs.add(projectDir)
		}

		// Thư mục chứa các plugin, tùy cấp đang mở.
		val pluginsRoot: File? = when {
			isPluginFolder(projectDir) -> null                          // đã xử lý ở trên
			projectDir.name == "plugins" -> projectDir                  // mở ở wp-content/plugins
			projectDir.name == "wp-content" -> File(projectDir, "plugins") // mở ở wp-content
			else -> File(projectDir, "wp-content/plugins")              // mở ở thư mục WordPress
		}
		pluginsRoot?.listFiles()?.filter { it.isDirectory }?.let { pluginDirs.addAll(it) }

		val fs = LocalFileSystem.getInstance()
		return pluginDirs.mapNotNull { dir ->
			fs.findFileByIoFile(File(dir, MAPPING_FILE))?.takeIf { !it.isDirectory }
		}
	}

	/** `<x>/wp-content/plugins/<plugin>` */
	private fun isPluginFolder(f: File): Boolean =
		f.parentFile?.name == "plugins" && f.parentFile?.parentFile?.name == "wp-content"

	// ==================================================================
	// PARSE JSON
	// ==================================================================

	/**
	 * Hỗ trợ các format:
	 *
	 *  A) Flat (legacy):
	 *     { "route.name": { "file": "routes/Apis.php", "line": 12, "route_parameters": [...] } }
	 *
	 *  B) Nhóm (object hoặc array), có thể bọc trong "routes":
	 *     { "routes": { "Apis": { "route.name": {...} }, "Ajaxs": [ { "route.name": {...} } ] } }
	 *
	 * Trường "scope" (nếu có) phải trùng tên thư mục plugin, nếu không file bị bỏ qua.
	 */
	private fun parseJson(
		root: JsonObject,
		pluginName: String,
		jsonBaseDir: String,
		out: MutableMap<String, MutableList<Target>>
	) {
		val scope = root.get("scope")?.takeIf { it.isJsonPrimitive }?.asString
		if (!scope.isNullOrBlank() && !scope.equals(pluginName, ignoreCase = true)) return

		val main = if (root.has("routes") && root.get("routes").isJsonObject)
			root.getAsJsonObject("routes") else root

		// Format A: có ít nhất 1 entry dạng { "file": ... } ở cấp đầu
		val looksFlat = main.entrySet().any { (_, v) -> v.isJsonObject && v.asJsonObject.has("file") }
		if (looksFlat) {
			for ((route, v) in main.entrySet()) {
				if (v.isJsonObject) append(out, route, v.asJsonObject, jsonBaseDir)
			}
			return
		}

		// Format B
		for ((_, group) in main.entrySet()) {
			when {
				group.isJsonObject -> for ((route, v) in group.asJsonObject.entrySet()) {
					if (v.isJsonObject) append(out, route, v.asJsonObject, jsonBaseDir)
				}

				group.isJsonArray -> for (elem in group.asJsonArray) {
					if (!elem.isJsonObject) continue
					for ((route, v) in elem.asJsonObject.entrySet()) {
						if (v.isJsonObject) append(out, route, v.asJsonObject, jsonBaseDir)
					}
				}
			}
		}
	}

	private fun append(
		out: MutableMap<String, MutableList<Target>>,
		route: String,
		obj: JsonObject,
		jsonBaseDir: String
	) {
		val fileRel = obj.get("file")?.takeIf { it.isJsonPrimitive }?.asString ?: return
		val line = obj.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

		val params = obj.get("route_parameters")
			?.takeIf { it.isJsonArray }
			?.asJsonArray
			?.mapNotNull { e -> if (e.isJsonPrimitive) e.asString else null }
			?: emptyList()

		out.computeIfAbsent(route) { mutableListOf() }
			.add(Target(fileRel, line, jsonBaseDir, params))
	}

	// ==================================================================
	// WATCHER — xóa cache khi .wpsp-routes.json thay đổi
	// ==================================================================

	private fun setupWatcher(project: Project) {
		if (!watchSet.add(project)) return

		// Parent disposable là project service của plugin (KHÔNG dùng Project trực tiếp):
		// - đóng project       → service.dispose() → dọn cache + ngắt listener
		// - unload/reload plugin → service cũng bị dispose → không rò rỉ listener
		val parent = project.service<WpspRouteProjectService>()

		ApplicationManager.getApplication().messageBus.connect(parent)
			.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
				override fun after(events: MutableList<out VFileEvent>) {
					// Tạo / sửa / xóa / đổi tên .wpsp-routes.json → build lại index ở lần gọi sau.
					if (events.any { it.path.endsWith(MAPPING_FILE) }) cache.remove(project)
				}
			})
	}

	/** Gọi từ [WpspRouteProjectService.dispose] khi project đóng / plugin unload. */
	internal fun onProjectDisposed(project: Project) {
		cache.remove(project)
		watchSet.remove(project)
	}
}

/**
 * Project service chỉ dùng làm "parent disposable" cho listener của WpspRouteIndex.
 *
 * Là light service (annotation @Service) nên KHÔNG cần khai báo trong plugin.xml.
 * IntelliJ tự dispose service khi project đóng hoặc khi plugin bị gỡ/reload động.
 */
@Service(Service.Level.PROJECT)
class WpspRouteProjectService(private val project: Project) : Disposable {
	override fun dispose() {
		WpspRouteIndex.onProjectDisposed(project)
	}
}
