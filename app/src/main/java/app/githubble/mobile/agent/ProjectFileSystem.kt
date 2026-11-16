package app.githubble.mobile.agent

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import app.githubble.mobile.network.ApiFailure
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Canonical file-system boundary for Project Workspace.
 *
 * InternalProjectFileSystem uses app-private storage.
 * SafProjectFileSystem works directly with a user-selected Android SAF tree and never resolves
 * it to an absolute filesystem path.
 */
interface ProjectFileSystem {
    val identity: String
    val isLiveFolder: Boolean

    fun listFiles(limit: Int = 1500): List<String>
    fun listDirectories(limit: Int = 2000): List<String>
    fun exists(path: String): Boolean
    fun isFile(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun size(path: String): Long
    fun lastModified(path: String): Long
    fun openInput(path: String): InputStream
    fun readBytes(path: String, maxBytes: Long): ByteArray
    fun writeBytes(path: String, data: ByteArray, overwrite: Boolean = true)
    fun createDirectory(path: String)
    fun delete(path: String): Boolean
    fun move(from: String, to: String)
    fun clear()
}

internal fun normalizeProjectPath(path: String): String {
    val clean = path.trim().replace('\\', '/').trim('/')
    if (clean.isBlank() || clean.length > 240 || clean.startsWith('/') ||
        clean.split('/').any { it.isBlank() || it == "." || it == ".." } ||
        clean.contains('\u0000')
    ) throw ApiFailure("Недопустимый путь")
    return clean
}

private fun InputStream.readBounded(maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw ApiFailure("Файл превышает допустимый размер")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

class InternalProjectFileSystem(rootDirectory: File) : ProjectFileSystem {
    private val root = rootDirectory.also { it.mkdirs() }.canonicalFile
    override val identity: String = "file:${root.path}"
    override val isLiveFolder: Boolean = false

    private fun entry(path: String): File {
        val clean = normalizeProjectPath(path)
        val target = File(root, clean).canonicalFile
        if (!target.path.startsWith(root.path + File.separator))
            throw ApiFailure("Доступ вне рабочего каталога запрещён")
        return target
    }

    override fun listFiles(limit: Int): List<String> =
        root.walkTopDown()
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile && it.canonicalPath.startsWith(root.path + File.separator) }
            .take(limit + 1)
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
            .also { if (it.size > limit) throw ApiFailure("Рабочее пространство ограничено $limit файлами") }

    override fun listDirectories(limit: Int): List<String> =
        root.walkTopDown()
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isDirectory && it != root && it.canonicalPath.startsWith(root.path + File.separator) }
            .map { it.relativeTo(root).invariantSeparatorsPath.trimEnd('/') }
            .filterNot { it.substringAfterLast('/').startsWith(".import-") }
            .take(limit + 1)
            .toList()
            .also { if (it.size > limit) throw ApiFailure("Слишком много папок в проекте") }
            .sorted()

    override fun exists(path: String): Boolean = entry(path).exists()
    override fun isFile(path: String): Boolean = entry(path).isFile
    override fun isDirectory(path: String): Boolean = entry(path).isDirectory
    override fun size(path: String): Long = entry(path).takeIf { it.isFile }?.length() ?: -1L
    override fun lastModified(path: String): Long = entry(path).takeIf { it.exists() }?.lastModified() ?: 0L

    override fun openInput(path: String): InputStream {
        val source = entry(path)
        if (!source.isFile) throw ApiFailure("Файл не найден")
        return source.inputStream()
    }

    override fun readBytes(path: String, maxBytes: Long): ByteArray =
        openInput(path).use { it.readBounded(maxBytes) }

    override fun writeBytes(path: String, data: ByteArray, overwrite: Boolean) {
        val target = entry(path)
        if (target.isDirectory) throw ApiFailure("Ожидался файл, но указан каталог")
        if (!overwrite && target.exists()) throw ApiFailure("Путь уже существует")
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile("omni-", ".tmp", target.parentFile)
        try {
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = overwrite)
                tmp.delete()
            }
        } finally {
            tmp.delete()
        }
    }

    override fun createDirectory(path: String) {
        val target = entry(path)
        if (target.exists()) throw ApiFailure("Путь уже существует")
        if (!target.mkdirs()) throw ApiFailure("Не удалось создать папку")
    }

    override fun delete(path: String): Boolean {
        val target = entry(path)
        if (!target.exists()) return false
        return if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    override fun move(from: String, to: String) {
        val source = entry(from)
        val destination = entry(to)
        if (!source.exists()) throw ApiFailure("Исходный путь не найден")
        if (destination.exists()) throw ApiFailure("Целевой путь уже существует")
        destination.parentFile?.mkdirs()
        if (source.renameTo(destination)) return
        if (source.isDirectory) {
            if (!source.copyRecursively(destination, overwrite = false))
                throw ApiFailure("Не удалось переместить папку")
            if (!source.deleteRecursively()) throw ApiFailure("Не удалось завершить перемещение")
        } else {
            source.copyTo(destination, overwrite = false)
            if (!source.delete()) throw ApiFailure("Не удалось завершить перемещение")
        }
    }

    override fun clear() {
        root.listFiles().orEmpty().forEach {
            if (it.isDirectory) it.deleteRecursively() else it.delete()
        }
    }
}

class SafProjectFileSystem(
    private val context: Context,
    private val treeUri: Uri
) : ProjectFileSystem {
    private val resolver = context.contentResolver
    override val identity: String = "saf:$treeUri"
    override val isLiveFolder: Boolean = true

    private fun root(): DocumentFile {
        val persisted = resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        if (!persisted) throw ApiFailure("Доступ к живой папке потерян. Подключите папку проекта заново.")
        return DocumentFile.fromTreeUri(context, treeUri)
            ?.takeIf { it.exists() && it.isDirectory && it.canRead() && it.canWrite() }
            ?: throw ApiFailure("Живая папка проекта недоступна. Подключите её заново.")
    }

    private fun resolve(path: String): DocumentFile? {
        var current = root()
        normalizeProjectPath(path).split('/').forEach { segment ->
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun ensureDirectory(path: String): DocumentFile {
        var current = root()
        val clean = path.trim().replace('\\', '/').trim('/')
        if (clean.isBlank()) return current
        normalizeProjectPath(clean).split('/').forEach { segment ->
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment)
                    ?: throw ApiFailure("Не удалось создать папку $segment")
                existing.isDirectory -> existing
                else -> throw ApiFailure("$segment уже существует как файл")
            }
        }
        return current
    }

    private fun parentAndName(path: String): Pair<DocumentFile, String> {
        val clean = normalizeProjectPath(path)
        val parentPath = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')
        return ensureDirectory(parentPath) to name
    }

    private fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun walk(
        directory: DocumentFile,
        prefix: String,
        files: MutableList<String>,
        directories: MutableList<String>,
        fileLimit: Int,
        directoryLimit: Int
    ) {
        directory.listFiles().sortedBy { it.name.orEmpty().lowercase() }.forEach { child ->
            val name = child.name ?: return@forEach
            if (name.isBlank() || '/' in name || '\u0000' in name) return@forEach
            val path = if (prefix.isBlank()) name else "$prefix/$name"
            when {
                child.isDirectory -> {
                    directories += path
                    if (directories.size > directoryLimit) throw ApiFailure("Слишком много папок в проекте")
                    walk(child, path, files, directories, fileLimit, directoryLimit)
                }
                child.isFile -> {
                    files += path
                    if (files.size > fileLimit) throw ApiFailure("Рабочее пространство ограничено $fileLimit файлами")
                }
            }
        }
    }

    private fun snapshot(fileLimit: Int = 1500, directoryLimit: Int = 2000): Pair<List<String>, List<String>> {
        val files = mutableListOf<String>()
        val directories = mutableListOf<String>()
        walk(root(), "", files, directories, fileLimit, directoryLimit)
        return files.sorted() to directories.sorted()
    }

    override fun listFiles(limit: Int): List<String> = snapshot(fileLimit = limit).first
    override fun listDirectories(limit: Int): List<String> = snapshot(directoryLimit = limit).second
    override fun exists(path: String): Boolean = resolve(path)?.exists() == true
    override fun isFile(path: String): Boolean = resolve(path)?.isFile == true
    override fun isDirectory(path: String): Boolean = resolve(path)?.isDirectory == true
    override fun size(path: String): Long = resolve(path)?.takeIf { it.isFile }?.length() ?: -1L
    override fun lastModified(path: String): Long = resolve(path)?.takeIf { it.exists() }?.lastModified() ?: 0L

    override fun openInput(path: String): InputStream {
        val source = resolve(path)?.takeIf { it.isFile } ?: throw ApiFailure("Файл не найден")
        return resolver.openInputStream(source.uri) ?: throw ApiFailure("Не удалось открыть файл")
    }

    override fun readBytes(path: String, maxBytes: Long): ByteArray =
        openInput(path).use { it.readBounded(maxBytes) }

    override fun writeBytes(path: String, data: ByteArray, overwrite: Boolean) {
        val (parent, name) = parentAndName(path)
        val existing = parent.findFile(name)
        if (existing?.isDirectory == true) throw ApiFailure("Ожидался файл, но указан каталог")
        if (!overwrite && existing != null) throw ApiFailure("Путь уже существует")
        val target = existing ?: parent.createFile(mimeType(name), name)
            ?: throw ApiFailure("Не удалось создать файл $name")
        val stream = runCatching { resolver.openOutputStream(target.uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(target.uri)
            ?: throw ApiFailure("Не удалось открыть файл для записи")
        stream.use { it.write(data) }
    }

    override fun createDirectory(path: String) {
        val clean = normalizeProjectPath(path)
        if (resolve(clean) != null) throw ApiFailure("Путь уже существует")
        val parentPath = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')
        val parent = ensureDirectory(parentPath)
        parent.createDirectory(name) ?: throw ApiFailure("Не удалось создать папку")
    }

    private fun deleteRecursive(doc: DocumentFile): Boolean {
        if (doc.isDirectory) doc.listFiles().forEach { child ->
            if (!deleteRecursive(child)) return false
        }
        return doc.delete()
    }

    override fun delete(path: String): Boolean =
        resolve(path)?.let(::deleteRecursive) ?: false

    private data class MoveBudget(var files: Int = 0, var bytes: Long = 0L)

    private fun copyNode(source: DocumentFile, destinationPath: String, budget: MoveBudget) {
        if (source.isDirectory) {
            if (!exists(destinationPath)) createDirectory(destinationPath)
            source.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                if (name.isBlank() || '/' in name || '\u0000' in name) return@forEach
                copyNode(child, "$destinationPath/$name", budget)
            }
            return
        }
        if (!source.isFile) return
        budget.files++
        if (budget.files > 1500) throw ApiFailure("Перемещение превышает лимит 1500 файлов")
        val (parent, name) = parentAndName(destinationPath)
        if (parent.findFile(name) != null) throw ApiFailure("Целевой путь уже существует")
        val target = parent.createFile(mimeType(name), name)
            ?: throw ApiFailure("Не удалось создать $destinationPath")
        val input = resolver.openInputStream(source.uri) ?: throw ApiFailure("Не удалось прочитать исходный файл")
        val output = resolver.openOutputStream(target.uri) ?: throw ApiFailure("Не удалось создать целевой файл")
        input.use { src ->
            output.use { dst ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    budget.bytes += n
                    if (budget.bytes > 100L * 1024L * 1024L)
                        throw ApiFailure("Перемещение превышает 100 МБ")
                    dst.write(buffer, 0, n)
                }
            }
        }
    }

    override fun move(from: String, to: String) {
        val sourcePath = normalizeProjectPath(from)
        val destinationPath = normalizeProjectPath(to)
        val source = resolve(sourcePath) ?: throw ApiFailure("Исходный путь не найден")
        if (resolve(destinationPath) != null) throw ApiFailure("Целевой путь уже существует")

        val sourceParent = sourcePath.substringBeforeLast('/', "")
        val destinationParent = destinationPath.substringBeforeLast('/', "")
        if (sourceParent == destinationParent) {
            val renamed = source.renameTo(destinationPath.substringAfterLast('/'))
            if (renamed) return
        }

        copyNode(source, destinationPath, MoveBudget())
        if (!deleteRecursive(source)) {
            runCatching { delete(destinationPath) }
            throw ApiFailure("Не удалось завершить перемещение")
        }
    }

    override fun clear() {
        root().listFiles().forEach { child ->
            if (!deleteRecursive(child)) throw ApiFailure("Не удалось очистить живую папку")
        }
    }
}

