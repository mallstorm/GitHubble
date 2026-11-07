package app.githubble.mobile.agent

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class WorkspaceSymbol(
    val name: String,
    val kind: String,
    val path: String,
    val line: Int,
    val column: Int,
    val signature: String
)

data class ProjectIndexStatus(
    val indexedFiles: Int,
    val symbolCount: Int,
    val changedFiles: Int,
    val builtAt: Long
)

data class ProjectIndexResult(
    val status: ProjectIndexStatus,
    val symbols: List<WorkspaceSymbol>
)

data class ProjectIndexFileStat(
    val size: Long,
    val modifiedAt: Long
)

data class WorkspaceUsage(
    val path: String,
    val line: Int,
    val column: Int,
    val preview: String,
    val isDefinition: Boolean
)

data class WorkspaceCompletion(
    val label: String,
    val kind: String,
    val detail: String,
    val path: String,
    val line: Int
)

data class WorkspaceDiagnostic(
    val path: String,
    val line: Int,
    val column: Int,
    val severity: String,
    val message: String
)

data class ProjectUsageResult(
    val status: ProjectIndexStatus,
    val usages: List<WorkspaceUsage>
)

data class ProjectCompletionResult(
    val status: ProjectIndexStatus,
    val items: List<WorkspaceCompletion>
)

/**
 * Lightweight incremental source index.
 *
 * This is intentionally not a language server. It gives the mobile Workspace a fast local
 * outline/definition layer while remaining provider-agnostic and cheap enough for SAF folders.
 * Cache is process-local and keyed by ProjectFileSystem.identity.
 */
object ProjectIndexRegistry {
    private data class IndexedFile(
        val stamp: String,
        val symbols: List<WorkspaceSymbol>
    )

    private data class Cache(
        val files: MutableMap<String, IndexedFile> = linkedMapOf(),
        var builtAt: Long = 0L
    )

    private val caches = ConcurrentHashMap<String, Cache>()

    private val sourceExtensions = setOf(
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx",
        "cs", "go", "rs", "c", "h", "cc", "cpp", "cxx", "hpp", "hh",
        "swift", "php", "rb"
    )

    private val ignoredSegments = setOf(
        ".git", ".gradle", ".idea", "build", "out", "dist", "target",
        "node_modules", ".next", ".nuxt", "vendor", "__pycache__"
    )

    fun refresh(
        identity: String,
        allFiles: List<String>,
        stat: (String) -> ProjectIndexFileStat,
        readText: (String) -> String
    ): ProjectIndexResult {
        val cache = caches.computeIfAbsent(identity) { Cache() }
        synchronized(cache) {
            val candidates = allFiles.asSequence()
                .filter(::isIndexable)
                .take(1200)
                .toList()
            val live = candidates.toSet()
            cache.files.keys.filterNot(live::contains).toList().forEach(cache.files::remove)

            var changed = 0
            candidates.forEach { path ->
                val metadata = runCatching { stat(path) }.getOrNull() ?: return@forEach
                if (metadata.size < 0L || metadata.size > 200_000L) {
                    cache.files.remove(path)
                    return@forEach
                }

                val metadataStamp = if (metadata.modifiedAt > 0L) {
                    "m:${metadata.size}:${metadata.modifiedAt}"
                } else null

                val previous = cache.files[path]
                if (metadataStamp != null && previous?.stamp == metadataStamp) return@forEach

                val text = runCatching { readText(path) }.getOrNull() ?: run {
                    cache.files.remove(path)
                    return@forEach
                }
                val stamp = metadataStamp ?: "h:${sha256(text)}"
                if (previous?.stamp == stamp) return@forEach

                cache.files[path] = IndexedFile(stamp, parseSymbols(path, text))
                changed++
            }

            cache.builtAt = System.currentTimeMillis()
            val symbols = cache.files.values
                .asSequence()
                .flatMap { it.symbols.asSequence() }
                .sortedWith(compareBy<WorkspaceSymbol> { it.path }.thenBy { it.line }.thenBy { it.name })
                .toList()
            return ProjectIndexResult(
                ProjectIndexStatus(
                    indexedFiles = cache.files.size,
                    symbolCount = symbols.size,
                    changedFiles = changed,
                    builtAt = cache.builtAt
                ),
                symbols
            )
        }
    }

    fun search(
        identity: String,
        query: String,
        allFiles: List<String>,
        stat: (String) -> ProjectIndexFileStat,
        readText: (String) -> String,
        limit: Int = 80
    ): ProjectIndexResult {
        val snapshot = refresh(identity, allFiles, stat, readText)
        val q = query.trim()
        if (q.isBlank()) return snapshot.copy(symbols = snapshot.symbols.take(limit))
        val ranked = snapshot.symbols.asSequence()
            .mapNotNull { symbol ->
                val name = symbol.name
                val score = when {
                    name.equals(q, ignoreCase = true) -> 0
                    name.startsWith(q, ignoreCase = true) -> 1
                    name.contains(q, ignoreCase = true) -> 2
                    symbol.signature.contains(q, ignoreCase = true) -> 3
                    else -> return@mapNotNull null
                }
                score to symbol
            }
            .sortedWith(
                compareBy<Pair<Int, WorkspaceSymbol>> { it.first }
                    .thenBy { it.second.name.length }
                    .thenBy { it.second.path }
                    .thenBy { it.second.line }
            )
            .take(limit)
            .map { it.second }
            .toList()
        return snapshot.copy(symbols = ranked)
    }

    fun definition(
        identity: String,
        name: String,
        fromPath: String?,
        allFiles: List<String>,
        stat: (String) -> ProjectIndexFileStat,
        readText: (String) -> String
    ): ProjectIndexResult {
        val snapshot = refresh(identity, allFiles, stat, readText)
        val q = name.trim().substringAfterLast('.')
        val matches = snapshot.symbols
            .filter { it.name.equals(q, ignoreCase = false) }
            .sortedWith(
                compareByDescending<WorkspaceSymbol> { fromPath != null && it.path == fromPath }
                    .thenBy { kindPriority(it.kind) }
                    .thenBy { it.path }
                    .thenBy { it.line }
            )
            .take(20)
        return snapshot.copy(symbols = matches)
    }

    fun outline(
        identity: String,
        path: String,
        allFiles: List<String>,
        stat: (String) -> ProjectIndexFileStat,
        readText: (String) -> String
    ): ProjectIndexResult {
        val snapshot = refresh(identity, allFiles, stat, readText)
        return snapshot.copy(
            symbols = snapshot.symbols
                .filter { it.path == path }
                .sortedWith(compareBy<WorkspaceSymbol> { it.line }.thenBy { it.column })
        )
    }

    fun usages(
        identity: String,
        name: String,
        allFiles: List<String>,
        stat: (String) -> ProjectIndexFileStat,
        readText: (String) -> String,
        limit: Int = 200
    ): ProjectUsageResult {
        val snapshot = refresh(identity, allFiles, stat, readText)
        val clean = name.trim()
        if (clean.isBlank()) return ProjectUsageResult(snapshot.status, emptyList())
        val definitionKeys = snapshot.symbols
            .filter { it.name == clean }
            .map { "${it.path}:${it.line}" }
            .toSet()
        val found = mutableListOf<WorkspaceUsage>()
        allFiles.asSequence().filter(::isIndexable).take(1200).forEach { path ->
            if (found.size >= limit) return@forEach
            val text = runCatching { readText(path) }.getOrNull() ?: return@forEach
            identifierOccurrences(path, text, clean)
                .take(limit - found.size)
                .forEach { occurrence ->
                    found += WorkspaceUsage(
                        path = path,
                        line = occurrence.line,
                        column = occurrence.column,
                        preview = occurrence.preview,
                        isDefinition = "$path:${occurrence.line}" in definitionKeys
                    )
                }
        }
        return ProjectUsageResult(
            snapshot.status,
            found.sortedWith(
                compareByDescending<WorkspaceUsage> { it.isDefinition }
                    .thenBy { it.path }.thenBy { it.line }.thenBy { it.column }
            )
        )
    }

    fun completions(
        identity: String,
        prefix: String,
        fromPath: String?,
        allFiles: List<String>,
        stat: (String) -> ProjectIndexFileStat,
        readText: (String) -> String,
        limit: Int = 24
    ): ProjectCompletionResult {
        val snapshot = refresh(identity, allFiles, stat, readText)
        val q = prefix.trim()
        if (q.length < 2) return ProjectCompletionResult(snapshot.status, emptyList())
        val items = snapshot.symbols.asSequence()
            .filter { it.name.startsWith(q, ignoreCase = true) && it.name != q }
            .distinctBy { it.name to it.kind }
            .sortedWith(
                compareByDescending<WorkspaceSymbol> { fromPath != null && it.path == fromPath }
                    .thenBy { if (it.name.startsWith(q, ignoreCase = false)) 0 else 1 }
                    .thenBy { kindPriority(it.kind) }
                    .thenBy { it.name.length }
                    .thenBy { it.name }
            )
            .take(limit)
            .map {
                WorkspaceCompletion(
                    label = it.name,
                    kind = it.kind,
                    detail = it.signature,
                    path = it.path,
                    line = it.line
                )
            }
            .toList()
        return ProjectCompletionResult(snapshot.status, items)
    }

    fun diagnostics(path: String, text: String): List<WorkspaceDiagnostic> {
        if (!isIndexable(path)) return emptyList()
        val out = mutableListOf<WorkspaceDiagnostic>()
        text.lineSequence().forEachIndexed { index, raw ->
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("<<<<<<<") || trimmed.startsWith("=======") || trimmed.startsWith(">>>>>>>")) {
                out += WorkspaceDiagnostic(
                    path, index + 1, raw.indexOf(trimmed).coerceAtLeast(0) + 1,
                    "error", "Git merge conflict: неразрешённый конфликт"
                )
            }
        }
        out += delimiterDiagnostics(path, text)
        return out.distinctBy { "${it.line}:${it.column}:${it.message}" }.take(80)
    }

    fun invalidate(identity: String) {
        caches.remove(identity)
    }

    private fun isIndexable(path: String): Boolean {
        val clean = path.replace('\\', '/')
        if (clean.split('/').any { it in ignoredSegments }) return false
        val ext = clean.substringAfterLast('.', "").lowercase()
        return ext in sourceExtensions
    }

    private data class IdentifierOccurrence(
        val line: Int,
        val column: Int,
        val preview: String
    )

    /**
     * Conservative lexical scan for code identifiers. Strings and comments are skipped so
     * Find Usages / Rename Symbol do not rewrite user-facing text or commented-out code.
     */
    private fun identifierOccurrences(path: String, text: String, name: String): List<IdentifierOccurrence> {
        if (name.isBlank()) return emptyList()
        val out = mutableListOf<IdentifierOccurrence>()
        val lines = text.lines()
        val ext = path.substringAfterLast('.', "").lowercase()
        var line = 1
        var column = 1
        var i = 0
        var quoteCode = 0
        var tripleQuote = false
        var escaped = false
        var inLineComment = false
        var inBlockComment = false

        fun word(ch: Char?): Boolean = ch?.let { it.isLetterOrDigit() || it == '_' } == true
        fun advance(ch: Char) {
            if (ch.code == 10) {
                line++
                column = 1
                inLineComment = false
            } else column++
        }

        while (i < text.length) {
            val ch = text[i]
            val next = text.getOrNull(i + 1)

            if (inLineComment) {
                advance(ch); i++; continue
            }
            if (inBlockComment) {
                if (ch.code == 42 && next?.code == 47) {
                    advance(ch); advance(next); i += 2; inBlockComment = false; continue
                }
                advance(ch); i++; continue
            }

            if (quoteCode != 0) {
                if (tripleQuote) {
                    if (ch.code == quoteCode && text.getOrNull(i + 1)?.code == quoteCode && text.getOrNull(i + 2)?.code == quoteCode) {
                        repeat(3) { advance(text[i + it]) }
                        i += 3
                        quoteCode = 0
                        tripleQuote = false
                        continue
                    }
                } else if (escaped) {
                    escaped = false
                } else if (ch.code == 92) {
                    escaped = true
                } else if (ch.code == quoteCode) {
                    quoteCode = 0
                }
                advance(ch); i++; continue
            }

            if (ch.code == 47 && next?.code == 47) {
                advance(ch); advance(next); i += 2; inLineComment = true; continue
            }
            if (ch.code == 47 && next?.code == 42) {
                advance(ch); advance(next); i += 2; inBlockComment = true; continue
            }
            if (ch.code == 35 && ext in setOf("py", "rb")) {
                advance(ch); i++; inLineComment = true; continue
            }
            if (ch.code == 39 || ch.code == 34 || ch.code == 96) {
                val isTriple = ch.code != 96 &&
                    text.getOrNull(i + 1)?.code == ch.code &&
                    text.getOrNull(i + 2)?.code == ch.code
                quoteCode = ch.code
                tripleQuote = isTriple
                if (isTriple) {
                    repeat(3) { advance(text[i + it]) }
                    i += 3
                } else {
                    advance(ch)
                    i++
                }
                continue
            }

            if (
                i + name.length <= text.length &&
                text.regionMatches(i, name, 0, name.length, ignoreCase = false) &&
                !word(text.getOrNull(i - 1)) &&
                !word(text.getOrNull(i + name.length))
            ) {
                out += IdentifierOccurrence(
                    line = line,
                    column = column,
                    preview = lines.getOrNull(line - 1).orEmpty().trim().take(300)
                )
                repeat(name.length) { offset -> advance(text[i + offset]) }
                i += name.length
                continue
            }

            advance(ch)
            i++
        }
        return out
    }

    private fun delimiterDiagnostics(path: String, text: String): List<WorkspaceDiagnostic> {
        data class Open(val ch: Char, val line: Int, val column: Int)
        val stack = ArrayDeque<Open>()
        val out = mutableListOf<WorkspaceDiagnostic>()
        var line = 1
        var column = 1
        var quoteCode = 0
        var escaped = false
        var inLineComment = false
        var inBlockComment = false
        var i = 0
        val ext = path.substringAfterLast('.', "").lowercase()

        fun advance(ch: Char) {
            if (ch.code == 10) {
                line++
                column = 1
                inLineComment = false
            } else {
                column++
            }
        }

        while (i < text.length) {
            val ch = text[i]
            val next = text.getOrNull(i + 1)

            if (inLineComment) {
                advance(ch)
                i++
                continue
            }
            if (inBlockComment) {
                if (ch.code == 42 && next?.code == 47) {
                    advance(ch)
                    advance(next)
                    i += 2
                    inBlockComment = false
                    continue
                }
                advance(ch)
                i++
                continue
            }

            if (quoteCode != 0) {
                if (escaped) {
                    escaped = false
                } else if (ch.code == 92) {
                    escaped = true
                } else if (ch.code == quoteCode) {
                    quoteCode = 0
                }
                advance(ch)
                i++
                continue
            }

            if (ch.code == 47 && next?.code == 47) {
                advance(ch)
                advance(next)
                i += 2
                inLineComment = true
                continue
            }
            if (ch.code == 47 && next?.code == 42) {
                advance(ch)
                advance(next)
                i += 2
                inBlockComment = true
                continue
            }
            if (ch.code == 35 && ext in setOf("py", "rb")) {
                advance(ch)
                i++
                inLineComment = true
                continue
            }
            if (ch.code == 39 || ch.code == 34 || ch.code == 96) {
                quoteCode = ch.code
                advance(ch)
                i++
                continue
            }

            when (ch) {
                '(', '[', '{' -> stack.addLast(Open(ch, line, column))
                ')', ']', '}' -> {
                    val expected = when (ch) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    val top = stack.lastOrNull()
                    if (top == null || top.ch != expected) {
                        out += WorkspaceDiagnostic(
                            path, line, column, "error",
                            "Лишний закрывающий символ '$ch'"
                        )
                    } else {
                        stack.removeLast()
                    }
                }
            }
            advance(ch)
            i++
        }

        if (inBlockComment) {
            out += WorkspaceDiagnostic(path, line, column.coerceAtLeast(1), "error", "Незакрытый блочный комментарий")
        }
        if (quoteCode != 0) {
            out += WorkspaceDiagnostic(path, line, column.coerceAtLeast(1), "error", "Незакрытая строка")
        }
        stack.takeLast(20).forEach { open ->
            out += WorkspaceDiagnostic(
                path, open.line, open.column, "error",
                "Нет закрывающего символа для '${open.ch}'"
            )
        }
        return out
    }

    private fun kindPriority(kind: String): Int = when (kind) {
        "class", "interface", "object", "struct", "enum", "record", "trait", "type" -> 0
        "function", "method" -> 1
        "property", "field", "constant" -> 2
        else -> 3
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun parseSymbols(path: String, text: String): List<WorkspaceSymbol> {
        val ext = path.substringAfterLast('.', "").lowercase()
        val out = mutableListOf<WorkspaceSymbol>()
        text.lineSequence().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = raw.take(600)
            when (ext) {
                "kt", "kts" -> parseKotlin(path, lineNo, line, out)
                "java" -> parseJava(path, lineNo, line, out)
                "py" -> parsePython(path, lineNo, line, out)
                "js", "jsx", "ts", "tsx" -> parseJsTs(path, lineNo, line, out)
                "cs" -> parseCSharp(path, lineNo, line, out)
                "go" -> parseGo(path, lineNo, line, out)
                "rs" -> parseRust(path, lineNo, line, out)
                "c", "h", "cc", "cpp", "cxx", "hpp", "hh" -> parseCpp(path, lineNo, line, out)
                "swift" -> parseSwift(path, lineNo, line, out)
                "php" -> parsePhp(path, lineNo, line, out)
                "rb" -> parseRuby(path, lineNo, line, out)
            }
        }
        return out
    }

    private fun add(
        out: MutableList<WorkspaceSymbol>,
        path: String,
        lineNo: Int,
        line: String,
        name: String,
        kind: String
    ) {
        if (name.isBlank() || name.length > 120) return
        val column = (line.indexOf(name).takeIf { it >= 0 } ?: 0) + 1
        out += WorkspaceSymbol(name, kind, path, lineNo, column, line.trim().take(300))
    }

    private fun parseKotlin(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""\b(data\s+class|sealed\s+class|enum\s+class|annotation\s+class|value\s+class|class|interface|object|typealias)\s+([A-Za-z_]\w*)""")
            .find(line)?.let {
                val rawKind = it.groupValues[1]
                val kind = when {
                    "interface" in rawKind -> "interface"
                    "object" in rawKind -> "object"
                    "typealias" in rawKind -> "type"
                    "enum" in rawKind -> "enum"
                    else -> "class"
                }
                add(out, path, lineNo, line, it.groupValues[2], kind)
            }
        Regex("""\bfun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_][\w?.<>]*\.)?([A-Za-z_]\w*)\s*\(""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[1], "function") }
        Regex("""\b(?:const\s+)?(?:val|var)\s+([A-Za-z_]\w*)\b""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[1], if ("const " in line) "constant" else "property") }
    }

    private fun parseJava(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""\b(class|interface|enum|record)\s+([A-Za-z_]\w*)""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[2], it.groupValues[1]) }
        if (!line.trimStart().startsWith("//")) {
            Regex("""(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp|\s)+[\w<>,.?\[\]]+\s+([A-Za-z_]\w*)\s*\(""")
                .find(line)?.let { m ->
                    val name = m.groupValues[1]
                    if (name !in setOf("if", "for", "while", "switch", "catch", "new")) add(out, path, lineNo, line, name, "method")
                }
        }
    }

    private fun parsePython(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""^\s*class\s+([A-Za-z_]\w*)""").find(line)
            ?.let { add(out, path, lineNo, line, it.groupValues[1], "class") }
        Regex("""^\s*(?:async\s+)?def\s+([A-Za-z_]\w*)\s*\(""").find(line)
            ?.let { add(out, path, lineNo, line, it.groupValues[1], "function") }
    }

    private fun parseJsTs(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""\b(class|interface|enum|type|function)\s+([A-Za-z_$][\w$]*)""")
            .find(line)?.let {
                val kind = if (it.groupValues[1] == "function") "function" else it.groupValues[1]
                add(out, path, lineNo, line, it.groupValues[2], kind)
            }
        Regex("""\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[1], "function") }
    }

    private fun parseCSharp(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""\b(class|interface|struct|enum|record)\s+([A-Za-z_]\w*)""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[2], it.groupValues[1]) }
        Regex("""(?:public|protected|private|internal|static|virtual|override|abstract|async|sealed|extern|partial|\s)+[\w<>,.?\[\]]+\s+([A-Za-z_]\w*)\s*\(""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[1], "method") }
    }

    private fun parseGo(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""^\s*type\s+([A-Za-z_]\w*)\s+(struct|interface)\b""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[1], it.groupValues[2]) }
        Regex("""^\s*func\s+(?:\([^)]*\)\s*)?([A-Za-z_]\w*)\s*\(""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[1], "function") }
    }

    private fun parseRust(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?(struct|enum|trait|type|mod)\s+([A-Za-z_]\w*)""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[2], if (it.groupValues[1] == "mod") "module" else it.groupValues[1]) }
        Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?fn\s+([A-Za-z_]\w*)\s*""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[1], "function") }
    }

    private fun parseCpp(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""\b(class|struct|enum)\s+(?:class\s+)?([A-Za-z_]\w*)""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[2], it.groupValues[1]) }
        Regex("""^[\t ]*(?:template\s*<[^>]+>\s*)?(?:[\w:&*<>~,]+[\t ]+)+([A-Za-z_]\w*)\s*\([^;]*\)\s*(?:const\s*)?(?:\{|$)""")
            .find(line)?.let { m ->
                val name = m.groupValues[1]
                if (name !in setOf("if", "for", "while", "switch", "catch")) add(out, path, lineNo, line, name, "function")
            }
    }

    private fun parseSwift(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""\b(class|struct|enum|protocol|typealias)\s+([A-Za-z_]\w*)""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[2], if (it.groupValues[1] == "protocol") "interface" else it.groupValues[1]) }
        Regex("""\bfunc\s+([A-Za-z_]\w*)\s*\(""").find(line)
            ?.let { add(out, path, lineNo, line, it.groupValues[1], "function") }
    }

    private fun parsePhp(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""\b(class|interface|trait|enum)\s+([A-Za-z_]\w*)""")
            .find(line)?.let { add(out, path, lineNo, line, it.groupValues[2], it.groupValues[1]) }
        Regex("""\bfunction\s+&?\s*([A-Za-z_]\w*)\s*\(""").find(line)
            ?.let { add(out, path, lineNo, line, it.groupValues[1], "function") }
    }

    private fun parseRuby(path: String, lineNo: Int, line: String, out: MutableList<WorkspaceSymbol>) {
        Regex("""^\s*class\s+([A-Za-z_]\w*(?:::[A-Za-z_]\w*)*)""").find(line)
            ?.let { add(out, path, lineNo, line, it.groupValues[1], "class") }
        Regex("""^\s*module\s+([A-Za-z_]\w*(?:::[A-Za-z_]\w*)*)""").find(line)
            ?.let { add(out, path, lineNo, line, it.groupValues[1], "module") }
        Regex("""^\s*def\s+(?:self\.)?([A-Za-z_]\w*[!?=]?)""").find(line)
            ?.let { add(out, path, lineNo, line, it.groupValues[1], "function") }
    }
}

