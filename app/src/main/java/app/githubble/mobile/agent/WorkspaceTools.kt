package app.githubble.mobile.agent

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import android.net.Uri
import android.provider.OpenableColumns
import java.net.URLEncoder
import app.githubble.mobile.network.ApiFailure
import app.githubble.mobile.network.NetworkDnsPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A tool adapter is independently registerable; agents only call registered, explicitly scoped tools. */
interface AgentTool {
    val name: String
    val spec: String
    val fields: List<String> get() = emptyList()
    val inputSchema: JSONObject? get() = null
    suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String
}

data class WorkspaceSearchHit(val path: String, val line: Int, val preview: String)
data class WorkspaceCheckpoint(val id: String, val label: String, val createdAt: Long, val size: Long)
data class WorkspaceEditorSnapshot(
    val path: String,
    val exists: Boolean,
    val content: String?,
    val revision: String,
    val size: Long,
    val modifiedAt: Long
)
class WorkspaceFileConflictException(val latest: WorkspaceEditorSnapshot) :
    java.io.IOException("Файл изменён вне Git Agent")

data class WorkspaceRenamePreview(
    val oldName: String,
    val newName: String,
    val usages: List<WorkspaceUsage>,
    val collisions: List<WorkspaceSymbol>
) {
    val affectedFiles: Int get() = usages.map { it.path }.distinct().size
    val replacements: Int get() = usages.size
    val canApply: Boolean get() = usages.isNotEmpty() && collisions.isEmpty()
}

data class WorkspaceRenameResult(
    val oldName: String,
    val newName: String,
    val changedFiles: Int,
    val replacements: Int,
    val checkpointId: String
)

class WorkspaceTools private constructor(
    private val context: Context,
    val chatId: Long,
    private val rootRelativePath: String,
    private val checkpointRelativePath: String,
    val projectUuid: String?,
    private val storageMode: String,
    private val storageUri: String
) {
    constructor(context: Context, chatId: Long) : this(
        context,
        chatId,
        "agent-workspaces/$chatId",
        "agent-workspace-checkpoints/$chatId",
        null,
        ProjectWorkspaceSupport.STORAGE_INTERNAL,
        ""
    )

    companion object {
        fun forInternalRoot(context: Context,rootRelative: String,projectId: String,storageUri: String=""): WorkspaceTools {
            val root=File(context.filesDir,rootRelative).canonicalFile
            require(root.path.startsWith(context.filesDir.canonicalPath+File.separator)) { "Путь вне папки приложения" }
            return WorkspaceTools(context,projectId.hashCode().toLong(),rootRelative,rootRelative+"-checkpoints",projectId,
                if(storageUri.isBlank())ProjectWorkspaceSupport.STORAGE_INTERNAL else ProjectWorkspaceSupport.STORAGE_SAF,storageUri)
        }

        fun forProject(
            context: Context,
            chatId: Long,
            projectUuid: String,
            storageMode: String = ProjectWorkspaceSupport.STORAGE_INTERNAL,
            storageUri: String = ""
        ): WorkspaceTools {
            require(projectUuid.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "Некорректный UUID проекта" }
            require(storageMode in setOf(ProjectWorkspaceSupport.STORAGE_INTERNAL, ProjectWorkspaceSupport.STORAGE_SAF)) {
                "Некорректный режим хранилища"
            }
            return WorkspaceTools(
                context,
                chatId,
                "project-workspaces/$projectUuid",
                "project-workspace-checkpoints/$projectUuid",
                projectUuid,
                storageMode,
                storageUri
            )
        }
    }

    val isProjectWorkspace: Boolean get() = projectUuid != null
    private val webPrefs = context.getSharedPreferences("web_access", Context.MODE_PRIVATE)
    fun webSearchEnabled(): Boolean = webPrefs.getBoolean("search_enabled", webPrefs.getBoolean("enabled", false))
    fun cloudBrowserEnabled(): Boolean = webPrefs.getBoolean("browser_enabled", webPrefs.getBoolean("enabled", false))
    private fun requireWebSearch() { if (!webSearchEnabled()) throw ApiFailure("Поиск в сети выключен. Включите его в Настройки → Поиск и облачный браузер.") }
    private fun requireBrowser() { if (!cloudBrowserEnabled()) throw ApiFailure("Чтение веб-страниц выключено. Включите «Облачный браузер».") }

    data class WebSource(
        val title: String,
        val url: String,
        val snippet: String,
        val faviconUrl: String
    )
    private data class RawPage(val finalUrl: String, val contentType: String, val body: String)
    private data class PageEvidence(
        val finalUrl: String,
        val title: String,
        val snippet: String,
        val readable: String
    )

    // Reuse clients so repeated search/page/favicon requests share pools and DNS state.
    // Page requests fail fast and fall back to search snippets instead of blocking the whole answer.
    private val publicWebClient by lazy {
        OkHttpClient.Builder().dns(NetworkDnsPolicy.dns(publicOnly = true))
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(7, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(9, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val faviconClient by lazy {
        OkHttpClient.Builder().dns(NetworkDnsPolicy.dns(publicOnly = true))
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val webSources = linkedMapOf<String, WebSource>()
    private var webSearchCalls = 0
    private var webPageReads = 0
    private var lastSearchQuery = ""
    private var lastSearchPayload = ""
    private var lastSearchLiveSources = emptyList<WebSource>()

    fun sourceCount(): Int = webSources.size
    fun sourceMarkerBlock(): String = webSources.values.take(6).joinToString("\n") {
        "[[web-source|" + Uri.encode(it.url) + "|" + Uri.encode(it.title) + "|" +
            Uri.encode(it.snippet.take(420)) + "|" + Uri.encode(it.faviconUrl) + "]]"
    }
    private fun liveSearchMarkerBlock(sources: List<WebSource>): String =
        sources.take(4).joinToString("\n") {
            "[[web-search-source|" + Uri.encode(it.url) + "|" + Uri.encode(it.faviconUrl) + "]]"
        }

    /**
     * The compact source panel already exposes every registered source. Do not append
     * a second visible "Источники: [1] [2]..." row when inline citations are absent.
     */
    fun addCitationFallback(answer: String): String = answer

    private val internalRoot = File(context.filesDir, rootRelativePath).also { it.mkdirs() }.canonicalFile
    private val checkpointRoot = File(context.filesDir, checkpointRelativePath).also { it.mkdirs() }.canonicalFile
    private val fileSystem: ProjectFileSystem by lazy {
        if (isProjectWorkspace && storageMode == ProjectWorkspaceSupport.STORAGE_SAF) {
            val uri = storageUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
                ?: throw ApiFailure("Для проекта не выбрана живая папка")
            SafProjectFileSystem(context, uri)
        } else {
            InternalProjectFileSystem(internalRoot)
        }
    }

    val isLiveFolder: Boolean get() = fileSystem.isLiveFolder
    fun storageIdentity(): String = fileSystem.identity

    private fun normalizePath(path: String): String = normalizeProjectPath(path)

    fun list(): List<String> = fileSystem.listFiles(1500)

    fun directories(): List<String> = fileSystem.listDirectories(2000)

    private fun readLimited(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw ApiFailure("Файл превышает допустимый размер")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * One-time migration path between workspace backends. Source is never deleted.
     * It is used both for the legacy chat→project migration and for first SAF attachment when the
     * selected live folder is empty.
     */
    fun adoptFilesFrom(source: WorkspaceTools): Int {
        if (source.fileSystem.identity == fileSystem.identity) return 0
        if (list().isNotEmpty()) return 0
        val paths = source.list()
        if (paths.isEmpty()) return 0
        if (paths.size > 1500) throw ApiFailure("Старый Workspace превышает лимит 1500 файлов")

        var total = 0L
        val copied = mutableListOf<String>()
        try {
            paths.forEach { path ->
                val remaining = 100L * 1024L * 1024L - total
                if (remaining <= 0L) throw ApiFailure("Старый Workspace превышает 100 МБ")
                val data = source.fileSystem.readBytes(path, remaining)
                total += data.size
                fileSystem.writeBytes(path, data, overwrite = false)
                copied += path
            }
            if (checkpointRoot.listFiles().isNullOrEmpty()) {
                source.checkpointRoot.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".zip") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(10)
                    ?.forEach { checkpoint ->
                        checkpoint.copyTo(File(checkpointRoot, checkpoint.name), overwrite = false)
                    }
            }
            return copied.size
        } catch (e: Exception) {
            copied.asReversed().forEach { runCatching { fileSystem.delete(it) } }
            throw e
        }
    }

    fun createDirectory(path: String): String {
        val clean = normalizePath(path)
        fileSystem.createDirectory(clean)
        return "Создана папка $clean"
    }

    fun renamePath(from: String, to: String): String {
        val source = normalizePath(from)
        val destination = normalizePath(to)
        fileSystem.move(source, destination)
        ProjectIndexRegistry.invalidate(fileSystem.identity)
        return "Перемещено: $source → $destination"
    }

    fun deletePath(path: String): String {
        val clean = normalizePath(path)
        if (!fileSystem.exists(clean)) throw ApiFailure("Путь не найден")
        if (!fileSystem.delete(clean)) throw ApiFailure("Не удалось удалить $clean")
        ProjectIndexRegistry.invalidate(fileSystem.identity)
        return "Удалено: $clean"
    }

    fun searchContent(query: String, maxHits: Int = 80): List<WorkspaceSearchHit> {
        val q = query.trim()
        if (q.length !in 2..120) throw ApiFailure("Поиск должен содержать от 2 до 120 символов")
        val hits = mutableListOf<WorkspaceSearchHit>()
        for (path in list()) {
            if (hits.size >= maxHits) break
            val text = runCatching { read(path) }.getOrNull() ?: continue
            text.lineSequence().forEachIndexed { index, line ->
                if (hits.size < maxHits && line.contains(q, ignoreCase = true)) {
                    hits += WorkspaceSearchHit(path, index + 1, line.trim().take(220))
                }
            }
        }
        return hits
    }

    fun projectMap(): String {
        val files = list().sorted()
        if (files.isEmpty()) return "Рабочее пространство пусто."
        val extensions = files.groupingBy {
            it.substringAfterLast('.', missingDelimiterValue = "(без расширения)").lowercase()
        }.eachCount().entries.sortedByDescending { it.value }.take(12)
        val top = files.map { it.substringBefore('/') }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(12)
        val signals = mutableListOf<String>()
        fun has(name: String) = files.any { it.endsWith(name, ignoreCase = true) }
        if (has("settings.gradle.kts") || has("settings.gradle")) signals += "Gradle"
        if (has("AndroidManifest.xml")) signals += "Android"
        if (has("package.json")) signals += "Node.js"
        if (has("pyproject.toml") || has("requirements.txt")) signals += "Python"
        if (has("Cargo.toml")) signals += "Rust"
        if (has("go.mod")) signals += "Go"
        if (has("CMakeLists.txt")) signals += "C/C++"
        val entryPoints = files.filter {
            val n = it.substringAfterLast('/')
            n in setOf("MainActivity.kt","MainActivity.java","main.kt","Main.kt","main.py","app.py",
                "index.js","index.ts","main.ts","main.js","Program.cs","main.go","main.rs")
        }.take(16)
        return buildString {
            append("PROJECT MAP\n")
            append("Файлов: ${files.size}; папок: ${directories().size}\n")
            append("Стек: ${signals.ifEmpty { listOf("не определён") }.joinToString()}\n")
            append("Типы: ${extensions.joinToString { "${it.key}:${it.value}" }}\n")
            append("Верхний уровень: ${top.joinToString { "${it.key}(${it.value})" }}\n")
            if (entryPoints.isNotEmpty()) append("Точки входа: ${entryPoints.joinToString()}\n")
            val configs = files.filter {
                val n = it.substringAfterLast('/')
                n.startsWith("build.gradle") || n in setOf("settings.gradle","settings.gradle.kts","gradle.properties",
                    "AndroidManifest.xml","package.json","pyproject.toml","requirements.txt","Cargo.toml","go.mod")
            }.take(24)
            if (configs.isNotEmpty()) append("Ключевые конфиги: ${configs.joinToString()}\n")
        }.trim()
    }

    fun checkpoints(): List<WorkspaceCheckpoint> = checkpointRoot.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "zip" && it.nameWithoutExtension.matches(Regex("[0-9]{13}-[A-Za-z0-9_-]{1,48}")) }
        .map { f ->
            val id = f.nameWithoutExtension
            val time = id.substringBefore('-').toLongOrNull() ?: f.lastModified()
            val label = id.substringAfter('-', "snapshot").replace('_', ' ')
            WorkspaceCheckpoint(id, label, time, f.length())
        }
        .sortedByDescending { it.createdAt }

    fun createCheckpoint(label: String = "snapshot"): WorkspaceCheckpoint {
        if (list().isEmpty()) throw ApiFailure("Нечего сохранять: рабочее пространство пусто")
        val now = System.currentTimeMillis()
        val safe = label.trim().ifBlank { "snapshot" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').take(48).ifBlank { "snapshot" }
        val id = "$now-$safe"
        val target = File(checkpointRoot, "$id.zip")
        target.outputStream().use { export(it) }
        if (target.length() > 30L * 1024L * 1024L) {
            target.delete()
            throw ApiFailure("Снимок превышает 30 МБ")
        }
        checkpoints().drop(10).forEach { File(checkpointRoot, "${it.id}.zip").delete() }
        return WorkspaceCheckpoint(id, safe.replace('_', ' '), now, target.length())
    }

    fun restoreCheckpoint(id: String): String {
        if (!id.matches(Regex("[0-9]{13}-[A-Za-z0-9_-]{1,48}"))) throw ApiFailure("Некорректный снимок")
        val source = File(checkpointRoot, "$id.zip").canonicalFile
        if (!source.isFile || !source.path.startsWith(checkpointRoot.path + File.separator))
            throw ApiFailure("Снимок не найден")
        val scopeTag = projectUuid ?: "chat-$chatId"
        val staging = File(context.cacheDir, "workspace-restore-$scopeTag-${System.nanoTime()}").also { it.mkdirs() }.canonicalFile
        try {
            var count = 0
            ZipInputStream(source.inputStream()).use { zip ->
                while (true) {
                    val item = zip.nextEntry ?: break
                    if (item.isDirectory) { zip.closeEntry(); continue }
                    val clean = normalizePath(item.name)
                    val dest = File(staging, clean).canonicalFile
                    if (!dest.path.startsWith(staging.path + File.separator)) throw ApiFailure("Повреждённый снимок")
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { zip.copyTo(it) }
                    count++
                    if (count > 1500) throw ApiFailure("Снимок содержит слишком много файлов")
                    zip.closeEntry()
                }
            }
            fileSystem.clear()
            staging.walkTopDown().filter { it.isFile }.forEach { src ->
                val rel = src.relativeTo(staging).invariantSeparatorsPath
                fileSystem.writeBytes(rel, src.readBytes(), overwrite = false)
            }
            ProjectIndexRegistry.invalidate(fileSystem.identity)
            return "Восстановлен снимок $id · $count файлов"
        } finally {
            staging.deleteRecursively()
        }
    }

    fun deleteCheckpoint(id: String) {
        if (!id.matches(Regex("[0-9]{13}-[A-Za-z0-9_-]{1,48}"))) throw ApiFailure("Некорректный снимок")
        val target = File(checkpointRoot, "$id.zip").canonicalFile
        if (!target.path.startsWith(checkpointRoot.path + File.separator) || !target.isFile || !target.delete())
            throw ApiFailure("Не удалось удалить снимок")
    }

    private fun read(path: String): String {
        val clean = normalizePath(path)
        if (!fileSystem.isFile(clean)) throw ApiFailure("Файл не найден")
        val data = fileSystem.readBytes(clean, 200_000L)
        if (data.any { it == 0.toByte() }) throw ApiFailure("Это бинарный файл. Агент может читать только текст.")
        return String(data, Charsets.UTF_8)
    }

    fun import(uri: Uri): String {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        } ?: "file.txt"
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^a-zA-Zа-яА-Я0-9._ -]"), "_").take(100)
        if (name.lowercase().endsWith(".zip")) return importZip(uri)
        if (fileSystem.exists(safe)) throw ApiFailure("Файл $safe уже существует. Переименуйте его перед импортом.")
        if (list().size >= 1500) throw ApiFailure("Максимум 1500 файлов")
        val data = resolver.openInputStream(uri)?.use { readLimited(it, 1_000_000L) }
            ?: throw ApiFailure("Не удалось открыть файл")
        if (data.any { it == 0.toByte() }) throw ApiFailure("Для агента поддерживаются только текстовые файлы")
        try {
            fileSystem.writeBytes(safe, data, overwrite = false)
            ProjectIndexRegistry.invalidate(fileSystem.identity)
            return safe
        } catch (e: Exception) {
            runCatching { fileSystem.delete(safe) }
            throw e
        }
    }

    /** ZIP is staged and validated before files are copied into the active ProjectFileSystem. */
    private fun importZip(uri: Uri): String {
        val existingCount = list().size
        val scopeTag = projectUuid ?: "chat-$chatId"
        val staging = File(context.cacheDir, "workspace-import-$scopeTag-${System.nanoTime()}").also { it.mkdirs() }.canonicalFile
        try {
            var total = 0L
            val entries = mutableListOf<String>()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) { zip.closeEntry(); continue }
                        val path = normalizePath(entry.name.replace('\\', '/'))
                        if (entries.contains(path)) throw ApiFailure("Повторный путь внутри ZIP: $path")
                        if (entries.size + existingCount >= 1500) throw ApiFailure("Максимум 1500 файлов проекта")
                        val dest = File(staging, path).canonicalFile
                        if (!dest.path.startsWith(staging.path + File.separator)) throw ApiFailure("Недопустимый путь в ZIP")
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            var size = 0L
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                size += n
                                total += n
                                if (size > 2_000_000L || total > 80_000_000L)
                                    throw ApiFailure("ZIP превышает допустимые размеры")
                                out.write(buf, 0, n)
                            }
                        }
                        entries += path
                        zip.closeEntry()
                    }
                }
            } ?: throw ApiFailure("Не удалось открыть ZIP")

            val prefix = if (entries.any(fileSystem::exists)) {
                "imports/project-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}/"
            } else ""
            val copied = mutableListOf<String>()
            try {
                entries.forEach { path ->
                    val targetPath = prefix + path
                    if (fileSystem.exists(targetPath)) throw ApiFailure("Повторный путь импорта: $targetPath")
                    fileSystem.writeBytes(targetPath, File(staging, path).readBytes(), overwrite = false)
                    copied += targetPath
                }
            } catch (e: Exception) {
                copied.asReversed().forEach { runCatching { fileSystem.delete(it) } }
                throw e
            }
            ProjectIndexRegistry.invalidate(fileSystem.identity)
            return "Импортировано ${entries.size} файлов из ZIP" +
                if (prefix.isNotEmpty()) " в отдельную папку $prefix; прежние файлы сохранены" else ""
        } finally {
            staging.deleteRecursively()
        }
    }

    fun export(output: java.io.OutputStream) = ZipOutputStream(output).use { zip ->
        list().forEach { path ->
            zip.putNextEntry(ZipEntry(path))
            fileSystem.openInput(path).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    fun exportFile(path: String, output: java.io.OutputStream) {
        val clean = normalizePath(path)
        if (!fileSystem.isFile(clean)) throw ApiFailure("Файл не найден")
        fileSystem.openInput(clean).use { input -> output.use { input.copyTo(it) } }
    }

    /** Temporary file used only by trusted built-in integrations such as GitHub Actions. */
    fun trustedTempFile(name: String): File {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "artifact.bin" }
        val dir = File(context.cacheDir, "trusted-agent").also { it.mkdirs() }.canonicalFile
        return File(dir, clean).canonicalFile.also {
            if (!it.path.startsWith(dir.path + File.separator)) throw ApiFailure("Недопустимое имя временного файла")
            it.parentFile?.mkdirs()
        }
    }

    /** Import a trusted binary into either internal or SAF project storage. */
    fun trustedImportFile(path: String, source: File, maxBytes: Long = 120L * 1024L * 1024L): String {
        if (!source.isFile) throw ApiFailure("Временный файл не найден")
        if (source.length() > maxBytes) throw ApiFailure("Файл превышает 120 МБ")
        val clean = normalizePath(path)
        if (!fileSystem.exists(clean) && list().size >= 1500) throw ApiFailure("Максимум 1500 файлов")
        fileSystem.writeBytes(clean, source.readBytes(), overwrite = true)
        ProjectIndexRegistry.invalidate(fileSystem.identity)
        return clean
    }

    /** Export a workspace file into a private temporary file for a trusted integration. */
    fun trustedExportFile(path: String, maxBytes: Long = 120L * 1024L * 1024L): File {
        val clean = normalizePath(path)
        if (!fileSystem.isFile(clean)) throw ApiFailure("Файл не найден")
        if (fileSystem.size(clean) > maxBytes) throw ApiFailure("Файл превышает 120 МБ")
        val target = trustedTempFile(clean.substringAfterLast('/'))
        fileSystem.openInput(clean).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun write(path: String, contents: String): String {
        val clean = normalizePath(path)
        val data = contents.toByteArray(Charsets.UTF_8)
        if (data.size > 200_000) throw ApiFailure("Файл не должен превышать 200 КБ")
        if (!fileSystem.exists(clean) && list().size >= 1500) throw ApiFailure("Максимум 1500 файлов")
        fileSystem.writeBytes(clean, data, overwrite = true)
        ProjectIndexRegistry.invalidate(fileSystem.identity)
        return "Сохранено $clean (${contents.length} символов)"
    }

    /** Developer Workspace editor: content plus stable revision for optimistic concurrency. */
    fun editorSnapshot(path: String): WorkspaceEditorSnapshot {
        val clean = normalizePath(path)
        if (!fileSystem.exists(clean)) {
            return WorkspaceEditorSnapshot(
                path = clean,
                exists = false,
                content = null,
                revision = "missing",
                size = 0L,
                modifiedAt = 0L
            )
        }
        if (!fileSystem.isFile(clean)) throw ApiFailure("Ожидался файл, но указан каталог")
        val data = fileSystem.readBytes(clean, 200_000L)
        if (data.any { it == 0.toByte() }) throw ApiFailure("Это бинарный файл. Агент может читать только текст.")
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        return WorkspaceEditorSnapshot(
            path = clean,
            exists = true,
            content = String(data, Charsets.UTF_8),
            revision = digest,
            size = data.size.toLong(),
            modifiedAt = fileSystem.lastModified(clean)
        )
    }

    fun readForEditor(path: String): String =
        editorSnapshot(path).content ?: throw ApiFailure("Файл не найден")

    /** Legacy unchecked editor save used by project bootstrap code. */
    fun saveFromEditor(path: String, content: String): String = write(path, content)

    /**
     * Checked editor save. If the file changed since it was loaded, nothing is overwritten.
     * force=true is reserved for the explicit "keep my version" conflict action.
     */
    fun saveFromEditorChecked(
        path: String,
        content: String,
        expectedRevision: String?,
        force: Boolean = false
    ): WorkspaceEditorSnapshot {
        val before = editorSnapshot(path)
        if (!force && expectedRevision != null && before.revision != expectedRevision) {
            throw WorkspaceFileConflictException(before)
        }
        write(path, content)
        return editorSnapshot(path)
    }

    fun deleteFromEditor(path: String) {
        val clean = normalizePath(path)
        if (!fileSystem.isFile(clean) || !fileSystem.delete(clean)) throw ApiFailure("Не удалось удалить файл")
        ProjectIndexRegistry.invalidate(fileSystem.identity)
    }

    private fun projectIndex(query: String? = null, limit: Int = 80): ProjectIndexResult {
        if (!isProjectWorkspace) throw ApiFailure("Project Index доступен только внутри проекта")
        val files = list()
        val stat: (String) -> ProjectIndexFileStat = { path ->
            ProjectIndexFileStat(
                size = fileSystem.size(path),
                modifiedAt = fileSystem.lastModified(path)
            )
        }
        val reader: (String) -> String = { path -> read(path) }
        return if (query == null) {
            ProjectIndexRegistry.refresh(fileSystem.identity, files, stat, reader)
        } else {
            ProjectIndexRegistry.search(fileSystem.identity, query, files, stat, reader, limit)
        }
    }

    fun projectIndexStatus(): ProjectIndexStatus = projectIndex().status

    fun searchSymbols(query: String, limit: Int = 80): ProjectIndexResult {
        val clean = query.trim()
        if (clean.length > 120) throw ApiFailure("Поиск символов ограничен 120 символами")
        return projectIndex(clean, limit)
    }

    fun findDefinition(name: String, fromPath: String? = null): ProjectIndexResult {
        if (!isProjectWorkspace) throw ApiFailure("Переход к определению доступен только внутри проекта")
        val clean = name.trim()
        if (clean.isBlank() || clean.length > 120) throw ApiFailure("Укажите имя символа")
        val files = list()
        return ProjectIndexRegistry.definition(
            identity = fileSystem.identity,
            name = clean,
            fromPath = fromPath,
            allFiles = files,
            stat = { path -> ProjectIndexFileStat(fileSystem.size(path), fileSystem.lastModified(path)) },
            readText = { path -> read(path) }
        )
    }

    fun fileOutline(path: String): ProjectIndexResult {
        val clean = normalizePath(path)
        val files = list()
        return ProjectIndexRegistry.outline(
            identity = fileSystem.identity,
            path = clean,
            allFiles = files,
            stat = { item -> ProjectIndexFileStat(fileSystem.size(item), fileSystem.lastModified(item)) },
            readText = { item -> read(item) }
        )
    }

    fun findUsages(name: String): ProjectUsageResult {
        val clean = name.trim()
        if (clean.isBlank() || clean.length > 120) throw ApiFailure("Укажите имя символа")
        val files = list()
        return ProjectIndexRegistry.usages(
            identity = fileSystem.identity,
            name = clean,
            allFiles = files,
            stat = { item -> ProjectIndexFileStat(fileSystem.size(item), fileSystem.lastModified(item)) },
            readText = { item -> read(item) }
        )
    }

    fun completeSymbols(prefix: String, fromPath: String? = null): ProjectCompletionResult {
        val clean = prefix.trim()
        if (clean.length > 120) throw ApiFailure("Префикс автодополнения слишком длинный")
        val files = list()
        return ProjectIndexRegistry.completions(
            identity = fileSystem.identity,
            prefix = clean,
            fromPath = fromPath,
            allFiles = files,
            stat = { item -> ProjectIndexFileStat(fileSystem.size(item), fileSystem.lastModified(item)) },
            readText = { item -> read(item) }
        )
    }

    fun diagnosticsForEditor(path: String, content: String): List<WorkspaceDiagnostic> =
        ProjectIndexRegistry.diagnostics(normalizePath(path), content)

    fun previewRenameSymbol(oldName: String, newName: String): WorkspaceRenamePreview {
        val oldClean = oldName.trim()
        val newClean = newName.trim()
        val identifier = Regex("[A-Za-z_][A-Za-z0-9_]{0,119}")
        if (!identifier.matches(oldClean)) throw ApiFailure("Некорректное исходное имя символа")
        if (!identifier.matches(newClean)) throw ApiFailure("Новое имя должно быть корректным идентификатором")
        if (oldClean == newClean) throw ApiFailure("Новое имя совпадает с текущим")
        val usages = findUsages(oldClean).usages
        if (usages.none { it.isDefinition }) throw ApiFailure("Определение символа $oldClean не найдено в Project Index")
        val collisions = searchSymbols(newClean, 100).symbols
            .filter { it.name == newClean }
            .take(20)
        return WorkspaceRenamePreview(oldClean, newClean, usages, collisions)
    }

    fun renameSymbol(oldName: String, newName: String): WorkspaceRenameResult {
        val preview = previewRenameSymbol(oldName, newName)
        if (!preview.canApply) {
            val where = preview.collisions.firstOrNull()?.let { "${it.path}:${it.line}" }
            throw ApiFailure(
                if (where != null) "Символ ${preview.newName} уже объявлен: $where"
                else "Переименование недоступно"
            )
        }

        val grouped = preview.usages.groupBy { it.path }
        val originals = linkedMapOf<String, WorkspaceEditorSnapshot>()
        val updated = linkedMapOf<String, String>()

        grouped.forEach { (path, occurrences) ->
            val snapshot = editorSnapshot(path)
            val source = snapshot.content ?: throw ApiFailure("Файл $path больше не существует")
            originals[path] = snapshot
            val offsets = occurrences.map { usage ->
                val offset = lineColumnOffset(source, usage.line, usage.column)
                if (offset < 0 || !source.regionMatches(offset, preview.oldName, 0, preview.oldName.length)) {
                    throw ApiFailure("Project Index устарел для $path:${usage.line}")
                }
                offset
            }.distinct().sortedDescending()

            var text = source
            offsets.forEach { offset ->
                text = text.replaceRange(offset, offset + preview.oldName.length, preview.newName)
            }
            updated[path] = text
        }

        originals.forEach { (path, snapshot) ->
            val current = editorSnapshot(path)
            if (current.revision != snapshot.revision) {
                throw WorkspaceFileConflictException(current)
            }
        }

        val checkpoint = createCheckpoint("rename_${preview.oldName}_to_${preview.newName}")
        val written = linkedMapOf<String, WorkspaceEditorSnapshot>()
        try {
            updated.forEach { (path, text) ->
                val saved = saveFromEditorChecked(
                    path = path,
                    content = text,
                    expectedRevision = originals.getValue(path).revision
                )
                written[path] = saved
            }
        } catch (error: Throwable) {
            var rollbackFailed = false
            written.entries.toList().asReversed().forEach { (path, saved) ->
                val original = originals.getValue(path)
                val current = runCatching { editorSnapshot(path) }.getOrNull()
                if (current == null || current.revision != saved.revision) {
                    rollbackFailed = true
                    return@forEach
                }
                val restored = runCatching {
                    saveFromEditorChecked(
                        path = path,
                        content = original.content.orEmpty(),
                        expectedRevision = current.revision
                    )
                }.isSuccess
                if (!restored) rollbackFailed = true
            }
            ProjectIndexRegistry.invalidate(fileSystem.identity)
            if (rollbackFailed) {
                throw ApiFailure(
                    "Rename прерван. Автооткат неполный; доступен снимок ${checkpoint.id}. Причина: ${error.message}"
                )
            }
            throw error
        }

        ProjectIndexRegistry.invalidate(fileSystem.identity)
        return WorkspaceRenameResult(
            oldName = preview.oldName,
            newName = preview.newName,
            changedFiles = updated.size,
            replacements = preview.replacements,
            checkpointId = checkpoint.id
        )
    }

    private fun lineColumnOffset(text: String, line: Int, column: Int): Int {
        if (line < 1 || column < 1) return -1
        var currentLine = 1
        var offset = 0
        while (currentLine < line && offset < text.length) {
            if (text[offset] == '\n') currentLine++
            offset++
        }
        if (currentLine != line) return -1
        val target = offset + column - 1
        return target.takeIf { it in 0..text.length } ?: -1
    }

    private fun formatSymbols(result: ProjectIndexResult): String {
        val header = "INDEX files=${result.status.indexedFiles} symbols=${result.status.symbolCount} changed=${result.status.changedFiles}"
        val rows = result.symbols.joinToString("\n") { symbol ->
            "${symbol.kind} ${symbol.name} — ${symbol.path}:${symbol.line}:${symbol.column} · ${symbol.signature}"
        }
        return if (rows.isBlank()) "$header\nСимволы не найдены" else "$header\n$rows"
    }

    private fun changeStats(before: String, after: String): String {
        fun lineCount(value: String): Int =
            if (value.isEmpty()) 0 else value.lineSequence().count()
        val beforeLines = lineCount(before)
        val afterLines = lineCount(after)
        val added = maxOf(afterLines - beforeLines, 0)
        val removed = maxOf(beforeLines - afterLines, 0)
        val deltaChars = after.length - before.length
        return "lines=+$added/-$removed chars=${if (deltaChars >= 0) "+$deltaChars" else deltaChars.toString()}"
    }

    fun toolDescriptions(): String = tools.values.joinToString("\n") { "- ${it.name}: ${it.spec}" }
    /** OpenAI-compatible function tool descriptors; runtime invocation still passes this registry's approval policy. */
    fun nativeToolSpecs(chatCompletions: Boolean = false): JSONArray = JSONArray().also { arr ->
        tools.values.forEach { tool ->
            val fields = when (tool.name) {
                "read_file", "delete_file", "make_directory", "outline_file" -> listOf("path")
                "search_files", "find_symbol" -> listOf("query")
                "find_definition", "find_usages" -> listOf("name")
                "rename_symbol" -> listOf("old", "new")
                "write_file" -> listOf("path", "content")
                "replace_in_file" -> listOf("path", "old", "new")
                "move_path" -> listOf("from", "to")
                "http_get" -> listOf("url")
                "web_search" -> listOf("query")
                else -> tool.fields
            }
            val props = JSONObject()
            fields.forEach { props.put(it, JSONObject().put("type", "string")) }
            val parameters = tool.inputSchema ?: JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(fields))
                .put("additionalProperties", false)
            if (chatCompletions) {
                val function = JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.spec)
                    .put("parameters", parameters)
                    .put("strict", false)
                arr.put(JSONObject().put("type", "function").put("function", function))
            } else {
                arr.put(JSONObject().put("type", "function")
                    .put("name", tool.name)
                    .put("description", tool.spec)
                    .put("parameters", parameters)
                    .put("strict", false))
            }
        }
    }
    private val tools = linkedMapOf<String, AgentTool>()
    init {
        WorkspaceArchiveAgentTools.registerAll(this)
        register(object : AgentTool {
            override val name = "list_files"
            override val spec = "{} — быстрый список файлов текущего рабочего пространства; вывод ограничен, чтобы агент не зависал на больших проектах"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val files = list().sorted()
                if (files.isEmpty()) return "TOOL list_files OK total=0\nРабочий каталог пуст"
                val shown = files.take(300)
                val omitted = files.size - shown.size
                return buildString {
                    append("TOOL list_files OK total=").append(files.size)
                        .append(" shown=").append(shown.size).append('\n')
                    append(shown.joinToString("\n"))
                    if (omitted > 0) {
                        append("\n... еще ").append(omitted)
                            .append(" файлов скрыто. Используй search_files или project_map для сужения.")
                    }
                }
            }
        })
        register(object : AgentTool {
            override val name = "read_file"
            override val spec = "{\"path\":\"src/Main.kt\"} — читать файл в рабочем каталоге"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean) = read(arguments.getString("path"))
        })
        register(object : AgentTool {
            override val name = "search_files"
            override val spec = "{\"query\":\"fun main\"} — найти текст в файлах проекта"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val q = arguments.getString("query"); if (q.length !in 1..120) throw ApiFailure("Некорректный поиск")
                return list().flatMap { path -> (runCatching { read(path) }.getOrNull() ?: "").lineSequence().mapIndexedNotNull { i, line ->
                    if (line.contains(q, ignoreCase = true)) "$path:${i + 1}: ${line.take(200)}" else null
                }.take(20).toList() }.take(60).joinToString("\n").ifBlank { "Совпадений нет" }
            }
        })
        register(object : AgentTool {
            override val name = "write_file"
            override val spec = "{\"path\":\"src/Main.kt\",\"content\":\"...\"} — запись/создание файла; пользователь подтверждает изменения"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val path = arguments.getString("path"); val content = arguments.getString("content")
                if (content.length > 200_000) throw ApiFailure("Слишком большой файл")
                val old = path.takeIf { fileSystem.isFile(it) }?.let { read(it) }
                val preview = "Файл: $path\nБыло (${old?.length ?: 0} символов):\n${old?.take(3500) ?: "[новый файл]"}\n\nСтанет (${content.length} символов):\n${content.take(6000)}"
                return if (approve("Записать файл $path?", preview)) {
                    val saved = write(path, content)
                    "TOOL write_file OK path=$path ${changeStats(old.orEmpty(), content)}\n$saved"
                } else "TOOL write_file CANCELLED path=$path"
            }
        })
        register(object : AgentTool {
            override val name = "replace_in_file"
            override val spec = "{\"path\":\"src/Main.kt\",\"old\":\"точный фрагмент\",\"new\":\"замена\"} — замена единственного совпадения; требуется подтверждение"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val path = arguments.getString("path"); val old = arguments.getString("old"); val replacement = arguments.getString("new")
                if (old.isBlank()) throw ApiFailure("Пустая строка поиска")
                val current = read(path)
                if (current.indexOf(old) < 0 || current.indexOf(old) != current.lastIndexOf(old)) throw ApiFailure("Для изменения необходимо ровно одно совпадение")
                val updated = current.replace(old, replacement)
                return if (approve("Заменить фрагмент в $path?", "Было:\n${old.take(3500)}\n\nСтанет:\n${replacement.take(3500)}")) {
                    val saved = write(path, updated)
                    "TOOL replace_in_file OK path=$path ${changeStats(current, updated)}\n$saved"
                } else "TOOL replace_in_file CANCELLED path=$path"
            }
        })
        register(object : AgentTool {
            override val name = "delete_file"
            override val spec = "{\"path\":\"src/Old.kt\"} — удаление файла; требуется подтверждение"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val path = arguments.getString("path")
                if (!fileSystem.isFile(path)) throw ApiFailure("Файл не найден")
                val old = read(path)
                return if (approve("Удалить $path?", old.take(2500))) {
                    if (!fileSystem.delete(path)) throw ApiFailure("Не удалось удалить файл")
                    ProjectIndexRegistry.invalidate(fileSystem.identity)
                    "TOOL delete_file OK path=$path ${changeStats(old, "")}"
                } else "TOOL delete_file CANCELLED path=$path"
            }
        })
        register(object : AgentTool {
            override val name = "make_directory"
            override val spec = "{\"path\":\"src/new\"} — создать каталог; требуется подтверждение"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val path = arguments.getString("path")
                return if (approve("Создать папку?", path)) {
                    "TOOL make_directory OK path=$path\n${createDirectory(path)}"
                } else "TOOL make_directory CANCELLED path=$path"
            }
        })
        register(object : AgentTool {
            override val name = "move_path"
            override val spec = "{\"from\":\"src/Old.kt\",\"to\":\"src/New.kt\"} — переименовать или переместить файл/папку; требуется подтверждение"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val from = arguments.getString("from")
                val to = arguments.getString("to")
                return if (approve("Переместить путь?", "$from → $to")) {
                    "TOOL move_path OK from=$from to=$to\n${renamePath(from, to)}"
                } else "TOOL move_path CANCELLED from=$from to=$to"
            }
        })
        register(object : AgentTool {
            override val name = "project_map"
            override val spec = "{} — краткая локальная карта структуры и технологий проекта"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String =
                projectMap()
        })
        register(object : AgentTool {
            override val name = "project_index"
            override val spec = "{} — построить/обновить быстрый индекс исходников проекта и вернуть статистику"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val result = projectIndex()
                return "Project Index: ${result.status.indexedFiles} файлов · ${result.status.symbolCount} символов · изменено ${result.status.changedFiles}"
            }
        })
        register(object : AgentTool {
            override val name = "find_symbol"
            override val spec = "{\"query\":\"MainViewModel\"} — найти классы, методы, функции и типы в локальном Project Index"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String =
                formatSymbols(searchSymbols(arguments.getString("query"), 60))
        })
        register(object : AgentTool {
            override val name = "find_definition"
            override val spec = "{\"name\":\"loadProject\"} — найти вероятное определение символа и точную строку файла"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String =
                formatSymbols(findDefinition(arguments.getString("name")))
        })
        register(object : AgentTool {
            override val name = "find_usages"
            override val spec = "{\"name\":\"loadProject\"} — найти использования символа во всех исходниках проекта"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val result = findUsages(arguments.getString("name"))
                return result.usages.joinToString("\n") { usage ->
                    (if (usage.isDefinition) "DEF " else "USE ") +
                        "${usage.path}:${usage.line}:${usage.column} · ${usage.preview}"
                }.ifBlank { "Использования не найдены" }
            }
        })
        register(object : AgentTool {
            override val name = "outline_file"
            override val spec = "{\"path\":\"src/Main.kt\"} — структура классов, методов и функций текущего файла"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String =
                formatSymbols(fileOutline(arguments.getString("path")))
        })
        register(object : AgentTool {
            override val name = "rename_symbol"
            override val spec = "{\"old\":\"OldName\",\"new\":\"NewName\"} — безопасно переименовать символ по Project Index; создаётся checkpoint и требуется подтверждение"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val preview = previewRenameSymbol(arguments.getString("old"), arguments.getString("new"))
                val details = buildString {
                    append("${preview.oldName} → ${preview.newName}\n")
                    append("${preview.replacements} замен · ${preview.affectedFiles} файлов\n")
                    preview.usages.take(40).forEach {
                        append(if (it.isDefinition) "DEF " else "USE ")
                        append("${it.path}:${it.line} · ${it.preview}\n")
                    }
                    if (preview.collisions.isNotEmpty()) {
                        append("\nКонфликт имён:\n")
                        preview.collisions.forEach { append("${it.path}:${it.line} · ${it.signature}\n") }
                    }
                }
                if (!preview.canApply) throw ApiFailure("Rename заблокирован из-за конфликта нового имени")
                if (!approve("Переименовать символ ${preview.oldName} → ${preview.newName}?", details.take(10_000))) {
                    return "Переименование отклонено"
                }
                val result = renameSymbol(preview.oldName, preview.newName)
                return "Переименовано ${result.oldName} → ${result.newName}: ${result.replacements} замен в ${result.changedFiles} файлах · checkpoint ${result.checkpointId}"
            }
        })
        register(object : AgentTool {
            override val name = "web_search"
            override val spec = "{\"query\":\"актуальная информация\"} — поиск в общедоступном интернете; возвращает заголовки, URL и фрагменты результатов для последующего чтения источников"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val query = arguments.getString("query").trim()
                if (query.length !in 2..300) throw ApiFailure("Поисковый запрос должен содержать от 2 до 300 символов")
                return searchPublicWeb(query)
            }
        })
        register(object : AgentTool {
            override val name = "http_get"
            override val spec = "{\"url\":\"https://example.com/docs\"} — читать публичную веб-страницу без cookies и API-ключей; требуется подтверждение URL"
            override suspend fun execute(arguments: org.json.JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val url = arguments.getString("url")
                if (!cloudBrowserEnabled()) return "Облачный браузер выключен: доступен только поисковый snippet и URL."
                return fetchPublic(url, requireBrowserAccess = true)
            }
        })
    }
    fun register(tool: AgentTool) { require(tool.name.matches(Regex("[a-z][a-z0-9_]{2,39}"))); tools[tool.name] = tool }
    suspend fun execute(name: String, args: org.json.JSONObject, approve: suspend (String, String) -> Boolean): String = withContext(Dispatchers.IO) {
        val tool = tools[name] ?: throw ApiFailure("Инструмент '$name' не зарегистрирован")
        tool.execute(args, this@WorkspaceTools, approve).take(45_000)
    }

    suspend fun searchPublicWeb(
        query: String,
        onProgress: suspend (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        requireWebSearch()
        val cleanQuery = query.replace(Regex("\\s+"), " ").trim()
        if (cleanQuery.length !in 2..300) throw ApiFailure("Поисковый запрос должен содержать от 2 до 300 символов")
        if (cleanQuery == lastSearchQuery && lastSearchPayload.isNotBlank()) {
            val live = liveSearchMarkerBlock(lastSearchLiveSources)
            onProgress(if (live.isBlank()) "🌐" else live)
            return@withContext lastSearchPayload
        }
        webSearchCalls++
        if (webSearchCalls > 3) throw ApiFailure("Остановлено: слишком много поисковых запросов за один ответ")
        onProgress("🌐 Поиск: " + cleanQuery.take(90))

        val encoded = URLEncoder.encode(cleanQuery, Charsets.UTF_8.name())
        val braveApiState = BraveSearchApiStore(context).status()
        val useBraveApi = braveApiState.enabled && BraveSearchApiPolicy.canSpend(
            braveApiState.dailyUsed, braveApiState.monthlyUsed
        )
        onProgress("🌐 Параллельный поиск: Bing · " +
            (if (useBraveApi) "Brave API (резерв HTML)" else "Brave HTML") + " · Mojeek")
        val backendHits = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    runCatching {
                        val raw = requestPublic("https://www.bing.com/search?format=rss&count=12&q=$encoded", "GET", "")
                        WebSearchBackends.parseBingRss(raw.body)
                    }.getOrElse { emptyList() }
                },
                async(Dispatchers.IO) {
                    runCatching {
                        // Search API is strictly opt-in and locally budget-limited.
                        // When unavailable, keep the original keyless HTML backend.
                        val official = if (useBraveApi) runCatching {
                            BraveSearchApiClient(context).searchIfEnabled(cleanQuery)
                        }.getOrNull() else null
                        if (!official.isNullOrEmpty()) official else {
                            val raw = requestPublic("https://search.brave.com/search?q=$encoded&source=web", "GET", "")
                            WebSearchBackends.parseBraveHtml(raw.body)
                        }
                    }.getOrElse { emptyList() }
                },
                async(Dispatchers.IO) {
                    runCatching {
                        val raw = requestPublic("https://www.mojeek.com/search?q=$encoded", "GET", "")
                        WebSearchBackends.parseMojeekHtml(raw.body)
                    }.getOrElse { emptyList() }
                }
            ).awaitAll()
        }
        val hits = WebSearchBackends.mergeRoundRobin(backendHits, limit = 8)
        if (hits.isEmpty()) throw ApiFailure("Параллельный поиск не вернул результатов")

        // Show the sites immediately. Favicon downloads must never block the first progress update.
        val liveHits = hits.take(4)
        val initialLive = liveHits.map { hit ->
            WebSource(hit.title, hit.url, hit.snippet, "")
        }
        lastSearchLiveSources = initialLive
        onProgress(liveSearchMarkerBlock(initialLive))

        // Browser reads used to run serially: 4 pages × up to 12 s, plus serial favicon requests.
        // Fetch top pages and favicons concurrently, then register sources in search-rank order.
        val browserHits = if (cloudBrowserEnabled()) {
            val allowance = (8 - webPageReads).coerceAtLeast(0)
            WebSearchBackends.independentHosts(hits, limit = minOf(6, allowance))
        } else emptyList()
        webPageReads += browserHits.size

        val (faviconPaths, pageEvidence) = coroutineScope {
            val favicons = async(Dispatchers.IO) {
                liveHits.map { hit ->
                    async(Dispatchers.IO) {
                        cacheFavicon(faviconFor(hit.url))
                    }
                }.awaitAll()
            }
            val pages = async(Dispatchers.IO) {
                browserHits.map { hit ->
                    async(Dispatchers.IO) {
                        runCatching {
                            fetchPageEvidence(hit.url, hit.title, hit.snippet)
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            favicons.await() to pages.await()
        }

        val faviconByUrl = liveHits.mapIndexed { index, hit ->
            hit.url to faviconPaths.getOrElse(index) { "" }
        }.toMap()
        val enrichedLive = liveHits.map { hit ->
            WebSource(hit.title, hit.url, hit.snippet, faviconByUrl[hit.url].orEmpty())
        }
        lastSearchLiveSources = enrichedLive
        onProgress(liveSearchMarkerBlock(enrichedLive))

        val evidenceByHitUrl = browserHits.mapIndexedNotNull { index, hit ->
            pageEvidence.getOrNull(index)?.let { hit.url to it }
        }.toMap()
        val fullReadCount = evidenceByHitUrl.size
        val independentReadHosts = evidenceByHitUrl.values.mapNotNull {
            it.finalUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")?.lowercase()
        }.distinct().size
        if (browserHits.isNotEmpty()) {
            onProgress("🌐 Прочитано: $fullReadCount/${browserHits.size} · доменов: $independentReadHosts")
        }

        val payload = buildString {
            append("Актуальные веб-источники по запросу «$cleanQuery». ")
            append("Используй номера источников [1], [2]... в ответе.\n")
            append("Поиск выполнен параллельно через Bing RSS, Brave и Mojeek. ")
            if (browserHits.isNotEmpty()) {
                append("Полностью прочитано страниц: $fullReadCount; независимых доменов: $independentReadHosts.\n")
                if (fullReadCount < 2 || independentReadHosts < 2) {
                    append("ВНИМАНИЕ: недостаточно независимо прочитанных страниц для уверенной проверки; ")
                    append("не утверждай, что сведения подтверждены несколькими источниками.\n")
                }
            }
            append("\n")

            hits.take(6).forEach { hit ->
                val page = evidenceByHitUrl[hit.url]
                val finalUrl = page?.finalUrl ?: hit.url
                val title = page?.title ?: hit.title
                val snippet = page?.snippet ?: hit.snippet
                val number = registerSource(
                    title,
                    finalUrl,
                    snippet,
                    faviconByUrl[hit.url].orEmpty()
                )
                append("[$number] $title\n$finalUrl")
                if (page != null) {
                    append("\n")
                    append(page.readable.take(4_500))
                } else if (snippet.isNotBlank()) {
                    append("\n")
                    append(snippet)
                }
                append("\n\n")
            }

            if (browserHits.isEmpty()) {
                append("Полное чтение страниц выключено; использованы поисковые snippets.")
            } else if (fullReadCount == 0) {
                append("Страницы не успели/не смогли открыться; использованы поисковые snippets.")
            } else if (fullReadCount < 2 || independentReadHosts < 2) {
                append("Удалось полноценно прочитать менее двух независимых источников; проверка считается неполной.")
            }
        }.trim()

        lastSearchQuery = cleanQuery
        lastSearchPayload = payload
        payload
    }

    private fun registerSource(title: String, url: String, snippet: String, faviconUrl: String): Int {
        val normalized = url.trim()
        val existing = webSources.keys.indexOf(normalized)
        if (existing >= 0) return existing + 1
        webSources[normalized] = WebSource(
            title = title.ifBlank { normalized.toHttpUrlOrNull()?.host.orEmpty() }.take(220),
            url = normalized,
            snippet = snippet.replace(Regex("\\s+"), " ").trim().take(420),
            faviconUrl = faviconUrl
        )
        return webSources.size
    }

    private fun htmlText(value: String, limit: Int = 1200): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim().take(limit)

    private fun readableHtml(value: String): String = value
        .replace(Regex("""(?is)<!--.*?-->"""), " ")
        .replace(Regex("""(?is)<(script|style|noscript|svg|canvas|template)[^>]*>.*?</\1>"""), " ")
        .replace(Regex("""(?is)<(br|p|div|li|h[1-6]|article|section|tr)[^>]*>"""), "\n")
        .let { htmlText(it, 30_000) }
        .trim()

    private fun pageTitle(html: String, fallback: String, url: String): String {
        val title = Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.getOrNull(1)
            ?.let(::htmlText).orEmpty()
        return title.ifBlank { fallback }.ifBlank {
            url.toHttpUrlOrNull()?.host?.removePrefix("www.").orEmpty()
        }.take(220)
    }

    private fun faviconFor(rawUrl: String): String {
        val url = rawUrl.toHttpUrlOrNull() ?: return ""
        return "https://${url.host}/favicon.ico"
    }

    private fun extractFavicon(html: String, pageUrl: String): String {
        val page = pageUrl.toHttpUrlOrNull() ?: return ""
        val tags = Regex("""(?is)<link\b[^>]*>""").findAll(html).map { it.value }.take(40)
        for (tag in tags) {
            val rel = Regex("""(?i)rel\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.getOrNull(1).orEmpty()
            if (!rel.contains("icon", ignoreCase = true)) continue
            val href = Regex("""(?i)href\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.getOrNull(1).orEmpty()
            if (href.isBlank()) continue
            val resolved = page.resolve(href.replace("&amp;", "&")) ?: continue
            if (resolved.scheme == "https") return resolved.toString()
        }
        return faviconFor(pageUrl)
    }

    private fun cacheFavicon(rawUrl: String): String {
        val url = rawUrl.toHttpUrlOrNull() ?: return ""
        if (url.scheme != "https") return ""
        val root = File(context.filesDir, "web-favicons").also { it.mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(rawUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cached = File(root, "$hash.img")
        if (cached.isFile && cached.length() in 16..131_072) return cached.absolutePath
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "OmniChat/0.8 Android").get().build()
            faviconClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use ""
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (!type.startsWith("image/") && !url.encodedPath.endsWith(".ico", ignoreCase = true)) return@use ""
                val bytes = response.body?.byteStream()?.readNBytesCompat(131_073) ?: return@use ""
                if (bytes.size !in 16..131_072) return@use ""
                cached.writeBytes(bytes)
                cached.absolutePath
            }
        }.getOrDefault("")
    }

    private fun fetchPageEvidence(
        url: String,
        fallbackTitle: String = "",
        fallbackSnippet: String = ""
    ): PageEvidence {
        val raw = requestPublic(url, "GET", "")
        val isHtml = raw.contentType.contains("html")
        val readable = if (isHtml) readableHtml(raw.body)
            else raw.body.replace(Regex("\\s+"), " ").trim().take(30_000)
        if (readable.isBlank()) throw ApiFailure("Страница не содержит читаемого текста")
        val title = if (isHtml) pageTitle(raw.body, fallbackTitle, raw.finalUrl)
            else fallbackTitle.ifBlank { raw.finalUrl.toHttpUrlOrNull()?.host.orEmpty() }
        val snippet = readable.take(420).ifBlank { fallbackSnippet.take(420) }
        return PageEvidence(raw.finalUrl, title, snippet, readable)
    }

    private fun fetchAndRegisterPage(url: String, fallbackTitle: String = "", fallbackSnippet: String = ""): String {
        webPageReads++
        if (webPageReads > 8) throw ApiFailure("Остановлено: слишком много чтений страниц за один ответ")
        val raw = requestPublic(url, "GET", "")
        val isHtml = raw.contentType.contains("html")
        val readable = if (isHtml) readableHtml(raw.body)
            else raw.body.replace(Regex("\\s+"), " ").trim().take(30_000)
        if (readable.isBlank()) throw ApiFailure("Страница не содержит читаемого текста")
        val title = if (isHtml) pageTitle(raw.body, fallbackTitle, raw.finalUrl)
            else fallbackTitle.ifBlank { raw.finalUrl.toHttpUrlOrNull()?.host.orEmpty() }
        val snippet = readable.take(420).ifBlank { fallbackSnippet.take(420) }
        val faviconUrl = if (isHtml) extractFavicon(raw.body, raw.finalUrl) else faviconFor(raw.finalUrl)
        val faviconPath = cacheFavicon(faviconUrl).ifBlank {
            cacheFavicon(faviconFor(raw.finalUrl))
        }
        val number = registerSource(title, raw.finalUrl, snippet, faviconPath)
        return "Источник [$number]: $title\n${raw.finalUrl}\n${readable.take(12_000)}"
    }

    suspend fun fetchPublic(
        rawUrl: String,
        method: String = "GET",
        payload: String = "",
        requireBrowserAccess: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        if (requireBrowserAccess) requireBrowser() else requireWebSearch()
        fetchAndRegisterPage(rawUrl, fallbackTitle = "", fallbackSnippet = "")
    }

    private fun requestPublic(rawUrl: String, method: String, payload: String): RawPage {
        val url = rawUrl.toHttpUrlOrNull() ?: throw ApiFailure("Некорректный адрес")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ApiFailure("Требуется HTTPS без учётных данных")
        }
        val req = Request.Builder().url(url).header("User-Agent", "OmniChat/0.8 Android").let { builder ->
            if (method == "POST") builder.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            else builder.get()
        }.build()
        publicWebClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure("HTTP ${response.code}: страница не загружена")
            val type = response.header("Content-Type").orEmpty().lowercase()
            if (!(type.contains("text/") || type.contains("application/json") || type.contains("application/xml"))) {
                throw ApiFailure("Поддерживаются только текстовые веб-страницы")
            }
            val input = response.body?.byteStream() ?: throw ApiFailure("Пустая страница")
            val bytes = input.readNBytesCompat(400_001)
            if (bytes.size > 400_000) throw ApiFailure("Страница больше 400 КБ")
            return RawPage(response.request.url.toString(), type, String(bytes, Charsets.UTF_8))
        }
    }

    private fun isPublicAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        if (a is Inet4Address) {
            val b = a.address.map { it.toInt() and 255 }
            if (b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 || b[0] == 169 && b[1] == 254 ||
                b[0] == 172 && b[1] in 16..31 || b[0] == 192 && b[1] == 168 || b[0] == 100 && b[1] in 64..127 ||
                b[0] == 192 && b[1] == 0 || b[0] == 198 && b[1] in 18..19 || b[0] == 192 && b[1] == 0 && b[2] == 2 ||
                b[0] == 198 && b[1] == 51 && b[2] == 100 || b[0] == 203 && b[1] == 0 && b[2] == 113) return false
        }
        if (a is Inet6Address) {
            val b = a.address
            if ((b[0].toInt() and 0xfe) == 0xfc || b[0] == 0x20.toByte() && b[1] == 0x01.toByte() && b[2] == 0x0d.toByte() && b[3] == 0xb8.toByte()) return false
            // Reject mapped IPv4, tunnels and address families not explicitly vetted.
            if (!(b[0] == 0x20.toByte() || b[0] == 0x26.toByte() || b[0] == 0x2a.toByte() || b[0] == 0x24.toByte())) return false
        }
        return true
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val buffer = ByteArray(8192); val out = java.io.ByteArrayOutputStream()
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

