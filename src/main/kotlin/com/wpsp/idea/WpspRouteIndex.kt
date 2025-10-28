package com.wpsp.helper

import com.google.gson.Gson
import com.google.gson.JsonElement
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
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object WpspRouteIndex {
    private val LOG = Logger.getInstance(WpspRouteIndex::class.java)
    private val gson = Gson()

    data class Target(
        val fileRel: String,   // "routes/Apis.php"
        val line: Int,         // 0-based
        val jsonBaseDir: String // thư mục chứa JSON (absolute path)
    )

    // cache: Project -> routeName -> list of targets (có thể duplicate)
    private val cache: MutableMap<Project, Map<String, List<Target>>> = ConcurrentHashMap()
    private val watchSet = ConcurrentHashMap.newKeySet<Project>()

    fun routes(project: Project): Map<String, List<Target>> {
        return cache[project] ?: run {
            val map = readAll(project)
            cache[project] = map
            setupWatcher(project)
            map
        }
    }

    fun allRouteNames(project: Project): Set<String> = routes(project).keys

    private fun readAll(project: Project): Map<String, List<Target>> {
        val result = LinkedHashMap<String, MutableList<Target>>()
        val files = findMappingFiles(project)
        if (files.isEmpty()) return emptyMap()

        for (vf in files) {
            try {
                val jsonText = FileUtil.loadFile(File(vf.path), StandardCharsets.UTF_8)
                val root = gson.fromJson(jsonText, JsonObject::class.java) ?: continue
                val jsonBaseDir = File(vf.path).parent // base dir của JSON
                mergeJson(root, jsonBaseDir, result, project)
            } catch (t: Throwable) {
                LOG.warn("Failed to read ${vf.path}: ${t.message}", t)
            }
        }
        return result
    }

    private fun mergeJson(
        root: JsonObject,
        jsonBaseDir: String,
        out: MutableMap<String, MutableList<Target>>,
        project: Project
    ) {
        // 1) Scope filter
        val scope = root.get("scope")?.asString
        if (!scope.isNullOrBlank()) {
            val currentPlugin = File(jsonBaseDir).name
            if (!scope.equals(currentPlugin, ignoreCase = true)) return
        }

        // 2) Lấy phần routes nếu có
        val main = if (root.has("routes")) root.getAsJsonObject("routes") else root

        // 3) Kiểm tra flat format (legacy)
        var looksFlat = false
        for ((key, value) in main.entrySet()) {
            if (value.isJsonObject && value.asJsonObject.has("file")) {
                looksFlat = true
                break
            }
        }

        if (looksFlat) {
            // Format A: { "route.name": { "file": "...", "line": 123 } }
            for ((route, v) in main.entrySet()) {
                val obj = v.asJsonObject
                append(out, route, obj, jsonBaseDir)
            }
            return
        }

        // 4) Format nhóm: { "Group": [...], "Group2": {...} }
        for ((_, groupVal) in main.entrySet()) {
            if (groupVal.isJsonObject) {
                for ((route, v) in groupVal.asJsonObject.entrySet()) {
                    val obj = v.asJsonObject
                    append(out, route, obj, jsonBaseDir)
                }
            } else if (groupVal.isJsonArray) {
                for (elem in groupVal.asJsonArray) {
                    if (elem.isJsonObject) {
                        for ((route, v) in elem.asJsonObject.entrySet()) {
                            val obj = v.asJsonObject
                            append(out, route, obj, jsonBaseDir)
                        }
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
        val line = obj.get("line")?.asInt ?: 0 // 0-based
        val t = Target(fileRel = fileRel, line = line, jsonBaseDir = jsonBaseDir)
        out.computeIfAbsent(route) { mutableListOf() }.add(t)
    }

    private fun findMappingFiles(project: Project): List<VirtualFile> {
        val root = project.basePath ?: return emptyList()
        val fs = LocalFileSystem.getInstance()
        val projectDir = File(root)

        val pathsToCheck = arrayListOf<File>()

        when {
            // Case 4: đang mở trong plugin cụ thể
            isPluginFolder(projectDir) -> {
                pathsToCheck.add(projectDir)
            }
            // Case 3: đang mở plugins folder
            projectDir.name == "plugins" -> {
                projectDir.listFiles()?.filter { it.isDirectory }?.let { pathsToCheck.addAll(it) }
            }
            // Case 2: đang mở wp-content folder
            projectDir.name == "wp-content" -> {
                projectDir.resolve("plugins").listFiles()
                    ?.filter { it.isDirectory }
                    ?.let { pathsToCheck.addAll(it) }
            }
            // Case 1: mở ở public_html hoặc trên cao hơn
            else -> {
                val wc = projectDir.resolve("wp-content/plugins")
                wc.listFiles()
                    ?.filter { it.isDirectory }
                    ?.let { pathsToCheck.addAll(it) }
            }
        }

        val out = mutableListOf<VirtualFile>()
        for (pluginDir in pathsToCheck) {
            val jf = pluginDir.resolve(".wpsp-routes.json")
            fs.findFileByIoFile(jf)?.let { if (!it.isDirectory) out.add(it) }
        }
        return out
    }

    private fun isPluginFolder(f: File): Boolean {
        return f.parentFile?.name == "plugins"
                && f.parentFile?.parentFile?.name == "wp-content"
    }

    private fun setupWatcher(project: Project) {
        if (!watchSet.add(project)) return
        val connection = ApplicationManager.getApplication().messageBus.connect(project)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                val changed = events.any { e ->
                    val p = e.file?.canonicalPath ?: e.path
                    p != null && p.endsWith(".wpsp-routes.json")
                }
                if (changed) cache.remove(project)
            }
        })
    }

    private fun currentPluginName(project: Project): String? {
        val projectDir = File(project.basePath ?: return null)

        if (isPluginFolder(projectDir)) {
            return projectDir.name
        }

        // Nếu mở ở public_html hoặc wp-content hoặc plugins
        val pluginsDir = findPluginsDir(projectDir) ?: return null
        val children = pluginsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        return if (children.size == 1) children.first().name else null
    }

    private fun findPluginsDir(dir: File): File? {
        if (dir.name == "plugins" && dir.parentFile?.name == "wp-content") {
            return dir
        }
        val sub = dir.resolve("wp-content/plugins")
        return if (sub.exists() && sub.isDirectory) sub else null
    }

    fun resolveVirtualFile(project: Project, target: Target): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        val candidate = File(target.jsonBaseDir, target.fileRel) // resolve từ thư mục JSON
        return fs.findFileByIoFile(candidate)
    }
}