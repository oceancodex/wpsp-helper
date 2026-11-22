package com.wpsp.helper

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiElement
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.testFramework.LightVirtualFile

object WpspRouteIndex {
	private val LOG = Logger.getInstance(WpspRouteIndex::class.java)
	private val gson = Gson()
	private const val MAPPING_FILE = ".wpsp-routes.json"

	data class Target(
		val fileRel: String,              // "routes/Apis.php"
		val line: Int,
		val jsonBaseDir: String,          // thư mục chứa JSON (VirtualFile.path, dùng '/')
		val routeParameters: List<String> = emptyList()
	)

	/** pluginDir -> routeName -> targets */
	private val cache: MutableMap<Project, Map<String, Map<String, List<Target>>>> = ConcurrentHashMap()
	private val watchSet = ConcurrentHashMap.newKeySet<Project>()

	// ------------------------------------------------------------------
	// Public API
	// ------------------------------------------------------------------

	/** Route của plugin chứa element. Đúng cả trong completion (dùng originalFile). */
	/** Route của plugin chứa element. Xử lý cả completion copy, Blade/injected PHP. */
	fun routesFor(element: PsiElement): Map<String, List<Target>> =
		routes(element.project, contextVirtualFile(element))

	/** Lấy VirtualFile thật trên đĩa của file đang edit. */
	fun contextVirtualFile(element: PsiElement): VirtualFile? {
		val project = element.project

		// 1) Injected PHP (Blade {{ }}, @php...) -> file host
		val topFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(element)
			?: element.containingFile
			?: return null

		// 2) Completion copy -> file gốc
		val psi = topFile.originalFile

		var vf: VirtualFile? = psi.virtualFile
			?: psi.viewProvider.virtualFile
			?: topFile.viewProvider.virtualFile

		// 3) Bóc các lớp file ảo
		repeat(3) {
			vf = when (val v = vf) {
				is VirtualFileWindow -> v.delegate
				is LightVirtualFile -> v.originalFile ?: return@repeat
				else -> return@repeat
			}
		}
		return vf
	}

	/**
	 * Route áp dụng cho contextFile: thư mục cha gần nhất có .wpsp-routes.json.
	 * File không thuộc plugin nào (hoặc null) → gộp tất cả.
	 */
	fun routes(project: Project, contextFile: VirtualFile?): Map<String, List<Target>> {
		val index = index(project)
		val pluginDir = findOwningPluginDir(contextFile, index.keys)
		if (pluginDir != null) return index[pluginDir].orEmpty()
		return mergeAll(index)
	}

	/** Gộp mọi plugin — chỉ dùng khi không có context file. */
	fun routes(project: Project): Map<String, List<Target>> = mergeAll(index(project))

	fun allRouteNames(project: Project, contextFile: VirtualFile?): Set<String> =
		routes(project, contextFile).keys

	fun resolveVirtualFile(project: Project, target: Target): VirtualFile? {
		val candidate = File(target.jsonBaseDir, target.fileRel)
		return LocalFileSystem.getInstance().findFileByIoFile(candidate)
	}

	// ------------------------------------------------------------------
	// Index
	// ------------------------------------------------------------------

	private fun index(project: Project): Map<String, Map<String, List<Target>>> {
		return cache[project] ?: run {
			val idx = readAll(project)
			cache[project] = idx
			setupWatcher(project)
			idx
		}
	}

	private fun findOwningPluginDir(file: VirtualFile?, pluginDirs: Set<String>): String? {
		if (file == null) return null
		var dir: VirtualFile? = if (file.isDirectory) file else file.parent
		while (dir != null) {
			if (dir.path in pluginDirs) return dir.path
			dir = dir.parent
		}
		// Fallback: so tiền tố đường dẫn (có dấu '/' để wpsp không khớp wpsp-lite)
		val path = file.path
		return pluginDirs
			.filter { path.startsWith("$it/") }
			.maxByOrNull { it.length }
	}

	private fun mergeAll(index: Map<String, Map<String, List<Target>>>): Map<String, List<Target>> {
		if (index.isEmpty()) return emptyMap()
		if (index.size == 1) return index.values.first()
		val out = LinkedHashMap<String, MutableList<Target>>()
		for (routes in index.values) {
			for ((name, targets) in routes) {
				out.computeIfAbsent(name) { mutableListOf() }.addAll(targets)
			}
		}
		return out
	}

	private fun readAll(project: Project): Map<String, Map<String, List<Target>>> {
		val result = LinkedHashMap<String, Map<String, List<Target>>>()
		for (vf in findMappingFiles(project)) {
			try {
				val jsonText = FileUtil.loadFile(File(vf.path), StandardCharsets.UTF_8)
				val root = gson.fromJson(jsonText, JsonObject::class.java) ?: continue
				val baseDir = vf.parent ?: continue
				val jsonBaseDir = baseDir.path

				val routes = LinkedHashMap<String, MutableList<Target>>()
				mergeJson(root, baseDir.name, jsonBaseDir, routes)
				if (routes.isNotEmpty()) result[jsonBaseDir] = routes
			} catch (t: Throwable) {
				LOG.warn("Failed to read ${vf.path}: ${t.message}", t)
			}
		}
		return result
	}

	private fun mergeJson(
		root: JsonObject,
		pluginName: String,
		jsonBaseDir: String,
		out: MutableMap<String, MutableList<Target>>
	) {
		// 1) Scope filter
		val scope = root.get("scope")?.takeIf { it.isJsonPrimitive }?.asString
		if (!scope.isNullOrBlank() && !scope.equals(pluginName, ignoreCase = true)) return

		// 2) Lấy phần routes nếu có
		val main = if (root.has("routes") && root.get("routes").isJsonObject)
			root.getAsJsonObject("routes") else root

		// 3) Flat format (legacy)
		val looksFlat = main.entrySet().any { (_, v) -> v.isJsonObject && v.asJsonObject.has("file") }
		if (looksFlat) {
			for ((route, v) in main.entrySet()) {
				if (v.isJsonObject) append(out, route, v.asJsonObject, jsonBaseDir)
			}
			return
		}

		// 4) Format nhóm
		for ((_, groupVal) in main.entrySet()) {
			when {
				groupVal.isJsonObject -> for ((route, v) in groupVal.asJsonObject.entrySet()) {
					if (v.isJsonObject) append(out, route, v.asJsonObject, jsonBaseDir)
				}

				groupVal.isJsonArray -> for (elem in groupVal.asJsonArray) {
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
		val fileRel = obj.get("file")?.asString ?: return
		val line = obj.get("line")?.asInt ?: 0

		val params = obj.get("route_parameters")
			?.takeIf { it.isJsonArray }
			?.asJsonArray
			?.mapNotNull { e -> if (e.isJsonPrimitive) e.asString else null }
			?: emptyList()

		out.computeIfAbsent(route) { mutableListOf() }
			.add(Target(fileRel, line, jsonBaseDir, params))
	}

	// ------------------------------------------------------------------
	// Tìm file mapping
	// ------------------------------------------------------------------

	private fun findMappingFiles(project: Project): List<VirtualFile> {
		val root = project.basePath ?: return emptyList()
		val fs = LocalFileSystem.getInstance()
		val projectDir = File(root)

		val pathsToCheck = arrayListOf<File>()
		when {
			isPluginFolder(projectDir) -> pathsToCheck.add(projectDir)
			projectDir.name == "plugins" ->
				projectDir.listFiles()?.filter { it.isDirectory }?.let { pathsToCheck.addAll(it) }

			projectDir.name == "wp-content" ->
				projectDir.resolve("plugins").listFiles()?.filter { it.isDirectory }?.let { pathsToCheck.addAll(it) }

			else ->
				projectDir.resolve("wp-content/plugins").listFiles()?.filter { it.isDirectory }?.let { pathsToCheck.addAll(it) }
		}

		return pathsToCheck.mapNotNull { dir ->
			fs.findFileByIoFile(dir.resolve(MAPPING_FILE))?.takeIf { !it.isDirectory }
		}
	}

	private fun isPluginFolder(f: File): Boolean =
		f.parentFile?.name == "plugins" && f.parentFile?.parentFile?.name == "wp-content"

	private fun setupWatcher(project: Project) {
		if (!watchSet.add(project)) return
		val connection = ApplicationManager.getApplication().messageBus.connect(project)
		connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
			override fun after(events: MutableList<out VFileEvent>) {
				if (events.any { it.path.endsWith(MAPPING_FILE) }) cache.remove(project)
			}
		})
	}
}