package app.githubble.mobile.agent

import app.githubble.mobile.network.ApiFailure
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Result of a project/workspace ZIP export. */
data class WorkspaceArchiveResult(
    val path: String,
    val fileCount: Int,
    val sourceBytes: Long,
    val archiveBytes: Long,
    val skipped: List<String>
)

/**
 * ZIP policy shared by the agent tools and ChatViewModel's archive post-condition.
 *
 * Archives are deliberately conservative: generated/build caches and common credential files are
 * omitted from full-project exports. Explicit selected-file exports still reject sensitive paths.
 */
object WorkspaceArchivePolicy {
    const val EXPORT_DIRECTORY = "exports"
    private val excludedDirectoryNames = setOf(
        ".git", ".gradle", ".idea", ".cache", ".cxx", ".kotlin", ".dart_tool",
        "build", "node_modules", "out", "target", "dist-cache", EXPORT_DIRECTORY
    )
    private val sensitiveExactNames = setOf(
        ".npmrc", ".pypirc", ".netrc", "local.properties", "keystore.properties",
        "credentials.json", "credential.json", "secrets.json", "secret.json",
        "id_rsa", "id_ed25519"
    )
    private val sensitiveExtensions = setOf("jks", "keystore", "p12", "pfx", "pem", "key")

    fun requestsArchive(text: String): Boolean {
        if (text.isBlank()) return false
        return Regex(
            "(\\bzip\\b|\\.zip\\b|архив|заархив|упак(уй|овать|оваться)|запак(уй|овать)|" +
                "\\b(pack|archive|compress)\\b)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
    }

    /** Creation + archive is the workflow where OmniChat must enforce a final project ZIP. */
    fun requestsProjectArchive(text: String): Boolean {
        if (!requestsArchive(text)) return false
        return Regex(
            "(созда|собер|реализ|разработ|сдела|постро|сгенерир|\\b(build|create|generate|develop)\\b)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
    }

    fun sanitizeArchiveBaseName(raw: String): String {
        val withoutZip = raw.trim().replace(Regex("(?i)\\.zip$"), "")
        return withoutZip
            .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_')
            .take(80)
            .ifBlank { "project" }
    }

    /** Normalizes and validates a ZIP entry or a workspace selection. */
    fun normalizeArchiveEntry(raw: String): String {
        val replaced = raw.trim().replace('\\', '/')
        if (replaced.isBlank() || replaced.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(replaced)) {
            throw ApiFailure("Недопустимый путь в архиве")
        }
        val clean = replaced.trim('/')
        val parts = clean.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || '\u0000' in it }) {
            throw ApiFailure("Недопустимый путь в архиве: $raw")
        }
        if (clean.length > 240) throw ApiFailure("Слишком длинный путь в архиве")
        return clean
    }

    fun isExcludedFromProjectArchive(path: String): Boolean {
        val clean = runCatching { normalizeArchiveEntry(path) }.getOrElse { return true }
        val parts = clean.split('/')
        if (parts.dropLast(1).any { it.lowercase(Locale.ROOT) in excludedDirectoryNames }) return true
        val name = parts.last().lowercase(Locale.ROOT)
        if (name in sensitiveExactNames) return true
        if (name.startsWith("service-account") && name.endsWith(".json")) return true
        if (name.startsWith("secrets.") || name.startsWith("secret.")) return true
        if (name.startsWith(".env")) {
            val safeTemplate = name == ".env.example" || name == ".env.sample" || name == ".env.template"
            if (!safeTemplate) return true
        }
        val extension = name.substringAfterLast('.', "")
        return extension in sensitiveExtensions
    }
}

/** Built-in, dependency-free ZIP operations available to every OmniChat Agent workspace. */
object WorkspaceArchiveSupport {
    private const val MAX_FILES = 1500
    private const val MAX_SOURCE_BYTES = 250L * 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = 120L * 1024L * 1024L
    private const val MAX_EXTRACTED_BYTES = 120L * 1024L * 1024L
    private const val MAX_EXTRACTED_FILE_BYTES = 40L * 1024L * 1024L

    private fun uniqueArchivePath(workspace: WorkspaceTools, requestedName: String): String {
        val base = WorkspaceArchivePolicy.sanitizeArchiveBaseName(requestedName)
        val existing = workspace.list().toHashSet()
        var index = 1
        while (index <= 999) {
            val suffix = if (index == 1) "" else "-$index"
            val candidate = "${WorkspaceArchivePolicy.EXPORT_DIRECTORY}/$base$suffix.zip"
            if (candidate !in existing) return candidate
            index++
        }
        throw ApiFailure("Не удалось подобрать свободное имя ZIP")
    }

    private fun selectedPaths(workspace: WorkspaceTools, selections: List<String>): List<String> {
        if (selections.isEmpty()) throw ApiFailure("Не выбраны файлы для архива")
        val all = workspace.list().sorted()
        val result = linkedSetOf<String>()
        selections.forEach { raw ->
            val selected = WorkspaceArchivePolicy.normalizeArchiveEntry(raw)
            val exact = all.filter { it == selected }
            val nested = all.filter { it.startsWith("$selected/") }
            if (exact.isEmpty() && nested.isEmpty()) throw ApiFailure("Путь не найден: $selected")
            (exact + nested).forEach(result::add)
        }
        if (result.size > MAX_FILES) throw ApiFailure("Архив ограничен $MAX_FILES файлами")
        val sensitive = result.filter(WorkspaceArchivePolicy::isExcludedFromProjectArchive)
        if (sensitive.isNotEmpty()) {
            throw ApiFailure(
                "В выбранных путях есть защищённые/служебные файлы: " + sensitive.take(8).joinToString()
            )
        }
        return result.toList().sorted()
    }

    fun archiveProject(workspace: WorkspaceTools, requestedName: String = "project"): WorkspaceArchiveResult {
        val all = workspace.list().sorted()
        if (all.isEmpty()) throw ApiFailure("Проект пуст — нечего архивировать")
        val skipped = all.filter(WorkspaceArchivePolicy::isExcludedFromProjectArchive)
        val included = all.filterNot(WorkspaceArchivePolicy::isExcludedFromProjectArchive)
        if (included.isEmpty()) throw ApiFailure("После исключения служебных файлов архив оказался пуст")
        return createArchive(workspace, included, requestedName, skipped)
    }

    fun archiveFiles(workspace: WorkspaceTools, selections: List<String>, requestedName: String): WorkspaceArchiveResult =
        createArchive(workspace, selectedPaths(workspace, selections), requestedName, emptyList())

    private fun createArchive(
        workspace: WorkspaceTools,
        paths: List<String>,
        requestedName: String,
        skipped: List<String>
    ): WorkspaceArchiveResult {
        if (paths.size > MAX_FILES) throw ApiFailure("Архив ограничен $MAX_FILES файлами")
        val outputPath = uniqueArchivePath(workspace, requestedName)
        val archive = workspace.trustedTempFile("workspace-${System.nanoTime()}.zip")
        var sourceBytes = 0L
        try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                paths.forEach { path ->
                    val clean = WorkspaceArchivePolicy.normalizeArchiveEntry(path)
                    if (clean.startsWith("${WorkspaceArchivePolicy.EXPORT_DIRECTORY}/")) return@forEach
                    val source = workspace.trustedExportFile(clean, MAX_ARCHIVE_BYTES)
                    try {
                        sourceBytes += source.length()
                        if (sourceBytes > MAX_SOURCE_BYTES) throw ApiFailure("Исходные файлы архива превышают 250 МБ")
                        zip.putNextEntry(ZipEntry(clean))
                        source.inputStream().buffered().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    } finally {
                        source.delete()
                    }
                }
            }
            if (archive.length() <= 0L) throw ApiFailure("Не удалось создать ZIP")
            if (archive.length() > MAX_ARCHIVE_BYTES) throw ApiFailure("ZIP превышает 120 МБ")
            workspace.trustedImportFile(outputPath, archive, MAX_ARCHIVE_BYTES)
            return WorkspaceArchiveResult(outputPath, paths.size, sourceBytes, archive.length(), skipped)
        } finally {
            archive.delete()
        }
    }

    data class ArchiveInspection(
        val path: String,
        val entries: List<String>,
        val declaredBytes: Long
    )

    /** Reads only ZIP central-directory metadata; listing never inflates entry payloads. */
    fun inspectArchive(workspace: WorkspaceTools, path: String): ArchiveInspection {
        val clean = WorkspaceArchivePolicy.normalizeArchiveEntry(path)
        if (!clean.endsWith(".zip", ignoreCase = true)) throw ApiFailure("Ожидался ZIP-архив")
        val local = workspace.trustedExportFile(clean, MAX_ARCHIVE_BYTES)
        try {
            val entries = mutableListOf<String>()
            val seen = hashSetOf<String>()
            var declaredBytes = 0L
            ZipFile(local).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    if (entry.isDirectory) continue
                    val name = WorkspaceArchivePolicy.normalizeArchiveEntry(entry.name)
                    if (!seen.add(name)) throw ApiFailure("Повторный путь внутри ZIP: $name")
                    entries += name
                    if (entries.size > MAX_FILES) throw ApiFailure("ZIP содержит больше $MAX_FILES файлов")
                    if (entry.size > MAX_EXTRACTED_FILE_BYTES) {
                        throw ApiFailure("Файл $name после распаковки превышает 40 МБ")
                    }
                    if (entry.size >= 0L) {
                        declaredBytes += entry.size
                        if (declaredBytes > MAX_EXTRACTED_BYTES) {
                            throw ApiFailure("Распакованный ZIP превышает 120 МБ")
                        }
                    }
                }
            }
            return ArchiveInspection(clean, entries, declaredBytes)
        } finally {
            local.delete()
        }
    }

    private fun uniqueExtractDestination(workspace: WorkspaceTools, archivePath: String, requested: String): String {
        val explicit = requested.trim().takeIf { it.isNotBlank() }
        val base = if (explicit != null) {
            WorkspaceArchivePolicy.normalizeArchiveEntry(explicit)
        } else {
            "imports/" + WorkspaceArchivePolicy.sanitizeArchiveBaseName(
                archivePath.substringAfterLast('/').substringBeforeLast('.')
            )
        }
        val occupiedFiles = workspace.list()
        val occupiedDirs = workspace.directories()
        var index = 1
        while (index <= 999) {
            val candidate = if (index == 1) base else "$base-$index"
            val collision = candidate in occupiedDirs || candidate in occupiedFiles ||
                occupiedFiles.any { it.startsWith("$candidate/") }
            if (!collision) return candidate
            index++
        }
        throw ApiFailure("Не удалось подобрать папку для распаковки")
    }

    fun extractArchive(workspace: WorkspaceTools, path: String, destination: String = ""): Pair<String, Int> {
        val inspection = inspectArchive(workspace, path)
        if (inspection.entries.isEmpty()) throw ApiFailure("ZIP не содержит файлов")
        val targetRoot = uniqueExtractDestination(workspace, inspection.path, destination)
        val local = workspace.trustedExportFile(inspection.path, MAX_ARCHIVE_BYTES)
        val created = mutableListOf<String>()
        var totalBytes = 0L
        try {
            ZipInputStream(local.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val entryName = WorkspaceArchivePolicy.normalizeArchiveEntry(entry.name)
                    val outputPath = "$targetRoot/$entryName"
                    val staged = workspace.trustedTempFile("unzip-${System.nanoTime()}.bin")
                    var fileBytes = 0L
                    try {
                        staged.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                fileBytes += n
                                totalBytes += n
                                if (fileBytes > MAX_EXTRACTED_FILE_BYTES) {
                                    throw ApiFailure("Файл $entryName после распаковки превышает 40 МБ")
                                }
                                if (totalBytes > MAX_EXTRACTED_BYTES) {
                                    throw ApiFailure("Распакованный ZIP превышает 120 МБ")
                                }
                                output.write(buffer, 0, n)
                            }
                        }
                        workspace.trustedImportFile(outputPath, staged, MAX_EXTRACTED_FILE_BYTES)
                        created += outputPath
                        zip.closeEntry()
                    } finally {
                        staged.delete()
                    }
                    if (created.size > MAX_FILES) throw ApiFailure("ZIP содержит больше $MAX_FILES файлов")
                }
            }
            return targetRoot to created.size
        } catch (error: Exception) {
            created.asReversed().forEach { runCatching { workspace.deletePath(it) } }
            runCatching { workspace.deletePath(targetRoot) }
            throw error
        } finally {
            local.delete()
        }
    }
}

object WorkspaceArchiveAgentTools {
    private fun archiveResult(result: WorkspaceArchiveResult): String = buildString {
        append("TOOL archive_project OK path=").append(result.path)
            .append(" files=").append(result.fileCount)
            .append(" sourceBytes=").append(result.sourceBytes)
            .append(" archiveBytes=").append(result.archiveBytes)
        if (result.skipped.isNotEmpty()) {
            append(" skipped=").append(result.skipped.size)
            append("\nИсключены служебные/секретные пути: ")
            append(result.skipped.take(20).joinToString())
            if (result.skipped.size > 20) append(" …")
        }
        append("\n[[workspace-file:").append(result.path).append("]]" )
    }

    fun registerAll(workspace: WorkspaceTools) {
        workspace.register(object : AgentTool {
            override val name = "archive_project"
            override val spec = "Создать скачиваемый ZIP всего текущего проекта. По умолчанию исключает .git/build/node_modules/exports и распространённые секреты. Не требует подтверждения. Аргумент name необязателен."
            override val inputSchema = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("name", JSONObject().put("type", "string")))
                .put("additionalProperties", false)
            override suspend fun execute(arguments: JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val name = arguments.optString("name").ifBlank { workspace.projectUuid ?: "project" }
                return archiveResult(WorkspaceArchiveSupport.archiveProject(workspace, name))
            }
        })

        workspace.register(object : AgentTool {
            override val name = "archive_files"
            override val spec = "Создать ZIP из выбранных файлов/папок проекта. paths — массив путей, name — необязательное имя ZIP. Секретные/служебные пути архивировать запрещено."
            override val inputSchema = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("minItems", 1))
                    .put("name", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("paths"))
                .put("additionalProperties", false)
            override suspend fun execute(arguments: JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val array = arguments.optJSONArray("paths") ?: throw ApiFailure("Укажите paths")
                val paths = (0 until array.length()).map { array.getString(it) }
                val name = arguments.optString("name").ifBlank { "files" }
                val result = WorkspaceArchiveSupport.archiveFiles(workspace, paths, name)
                return archiveResult(result).replace("TOOL archive_project", "TOOL archive_files")
            }
        })

        workspace.register(object : AgentTool {
            override val name = "list_archive"
            override val spec = "Показать содержимое ZIP в текущем workspace без распаковки."
            override val fields = listOf("path")
            override suspend fun execute(arguments: JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val inspected = WorkspaceArchiveSupport.inspectArchive(workspace, arguments.getString("path"))
                val shown = inspected.entries.take(300)
                return buildString {
                    append("TOOL list_archive OK path=").append(inspected.path)
                        .append(" files=").append(inspected.entries.size)
                        .append(" bytes=").append(inspected.declaredBytes).append('\n')
                    append(shown.joinToString("\n"))
                    if (shown.size < inspected.entries.size) append("\n… ещё ${inspected.entries.size - shown.size}")
                }
            }
        })

        workspace.register(object : AgentTool {
            override val name = "extract_archive"
            override val spec = "Безопасно распаковать ZIP из workspace в новую папку. Не перезаписывает существующие файлы, блокирует Zip Slip и архивные бомбы. destination необязателен."
            override val inputSchema = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string"))
                    .put("destination", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("path"))
                .put("additionalProperties", false)
            override suspend fun execute(arguments: JSONObject, workspace: WorkspaceTools, approve: suspend (String, String) -> Boolean): String {
                val path = arguments.getString("path")
                val inspection = WorkspaceArchiveSupport.inspectArchive(workspace, path)
                val destination = arguments.optString("destination")
                val preview = buildString {
                    append("ZIP: ").append(inspection.path).append('\n')
                    append("Файлов: ").append(inspection.entries.size).append('\n')
                    append("Размер после распаковки: ").append(inspection.declaredBytes).append(" байт\n")
                    if (destination.isNotBlank()) append("Папка: ").append(destination).append('\n')
                    append(inspection.entries.take(25).joinToString("\n"))
                    if (inspection.entries.size > 25) append("\n…")
                }
                if (!approve("Распаковать архив $path?", preview)) {
                    return "TOOL extract_archive CANCELLED path=$path"
                }
                val (root, count) = WorkspaceArchiveSupport.extractArchive(workspace, path, destination)
                return "TOOL extract_archive OK path=$path destination=$root files=$count"
            }
        })
    }
}

