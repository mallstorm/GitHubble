package app.githubble.mobile.memory
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln
data class MemoryItemEntity(
    val id: Long = 0,
    val scopeType: String = MemoryScope.GLOBAL,
    val scopeId: Long = 0,
    val kind: String = MemoryKind.FACT,
    val normalizedKey: String,
    val content: String,
    val keywords: String = "",
    val importance: Int = 50,
    val confidence: Int = 80,
    val pinned: Boolean = false,
    val active: Boolean = true,
    val sourceChatId: Long = 0,
    val sourceMessageId: Long = 0,
    val sourceModel: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = 0,
    val accessCount: Int = 0
)

object MemoryScope {
    const val GLOBAL = "global"
    const val PROJECT = "project"
}

object MemoryKind {
    const val FACT = "fact"
    const val PREFERENCE = "preference"
    const val DECISION = "decision"
    const val WORKFLOW = "workflow"
    const val PERSON = "person"
    const val PROJECT = "project"
    const val EPISODE = "episode"

    val durable = setOf(FACT, PREFERENCE, DECISION, WORKFLOW, PERSON, PROJECT)
}


data class MemoryCandidate(
    val kind: String,
    val scope: String,
    val key: String,
    val content: String,
    val keywords: List<String>,
    val importance: Int,
    val confidence: Int
)

data class MemoryExtraction(
    val memories: List<MemoryCandidate>,
    val forget: List<String>
)

data class MemoryRecall(
    val items: List<MemoryItemEntity>,
    val context: String
)

/** Pure parser, privacy guard and deterministic lexical scorer; kept Android-free for unit tests. */
object MemoryPolicy {
    const val EXTRACTION_SYSTEM_PROMPT = """
You are OmniChat's private long-term-memory extractor. Return strict JSON only.
Store only durable information explicitly stated by the user or clearly confirmed by the current project state: stable facts, preferences, people/relations, decisions, workflows, constraints and project facts.
Do not store guesses, model opinions, temporary small-talk, one-off questions, transient errors, passwords, API keys, tokens, cookies, private keys or other secrets.
Current explicit user statements override older recollections. Never turn assistant speculation into a user fact.
Schema: {"memories":[{"kind":"fact|preference|decision|workflow|person|project","scope":"global|project","key":"stable short key","content":"standalone factual memory","keywords":["term"],"importance":0,"confidence":0}],"forget":["memory topic explicitly requested to be forgotten"]}
importance and confidence are integers 0..100. Use project scope only for information that belongs to the current software/project context.
"""

    private val stopWords = setOf(
        "это", "как", "что", "для", "или", "она", "они", "его", "еще", "ещё", "уже", "мне", "тебя",
        "мой", "моя", "мои", "мы", "вы", "так", "там", "тут", "вот", "при", "без", "надо", "нужно",
        "the", "and", "that", "this", "with", "from", "have", "has", "for", "you", "your", "are", "was",
        "but", "not", "into", "about", "can", "will", "just", "then", "than", "our", "its", "use"
    )

    private val secretPatterns = listOf(
        Regex("(?iu)(?<![\\p{L}\\p{N}_])(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|token|пароль|токен)(?![\\p{L}\\p{N}_])\\s*[:=]\\s*[^\\s,;]{8,}"),
        Regex("(?iu)\\bBearer\\s+[A-Za-z0-9._~+/-]{8,}"),
        Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b"),
        Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{8,}\\b"),
        Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
    )

    fun containsSecret(text: String): Boolean = secretPatterns.any { it.containsMatchIn(text) }

    fun redactSecrets(text: String): String {
        var value = text
        secretPatterns.forEach { regex -> value = value.replace(regex, "[секрет скрыт]") }
        return value
    }

    fun tokens(text: String): Set<String> = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{1,}")
        .findAll(text.lowercase(Locale.ROOT).replace('ё', 'е'))
        .map { stem(it.value.trim('_', '-')) }
        .filter { it.length >= 2 && it !in stopWords }
        .take(160)
        .toSet()

    private fun stem(token: String): String {
        if (token.length < 6 || token.none { it in 'а'..'я' }) return token
        val endings = listOf(
            "иями", "ями", "ами", "ого", "ему", "ыми", "ими", "ость", "ение", "ений", "аться", "яться",
            "ами", "ями", "ов", "ев", "ах", "ях", "ой", "ий", "ый", "ая", "яя", "ое", "ее", "ам", "ям",
            "ом", "ем", "ами", "ями", "ить", "ать", "ять", "ешь", "ете", "ют", "ет", "ла", "ли", "ло"
        )
        val ending = endings.firstOrNull { token.endsWith(it) && token.length - it.length >= 4 }
        return if (ending == null) token else token.dropLast(ending.length)
    }

    fun keywords(text: String, limit: Int = 24): String = tokens(text).take(limit).joinToString(" ")

    fun normalizeKey(raw: String, content: String): String {
        val basis = raw.ifBlank { content }
        val clean = tokens(basis).take(10).joinToString("-")
        return clean.take(96).ifBlank { "memory-${content.hashCode().toUInt().toString(16)}" }
    }

    fun explicitRememberText(text: String): String? {
        val match = Regex(
            "(?isu)^\\s*(?:запомни|запомни\\s+это|сохрани\\s+в\\s+памят(?:ь|и)|remember|remember\\s+this)\\s*[:—,-]?\\s*(.+)$"
        ).find(text) ?: return null
        return match.groupValues[1].trim().takeIf { it.length >= 3 }
    }

    fun explicitForgetTarget(text: String): String? {
        val match = Regex(
            "(?isu)^\\s*(?:забудь|удали\\s+из\\s+памят(?:и|ь)|не\\s+помни|forget|remove\\s+from\\s+memory)\\s*[:—,-]?\\s*(.*)$"
        ).find(text) ?: return null
        return match.groupValues[1].trim()
    }

    fun isForgetAll(text: String): Boolean {
        val target = explicitForgetTarget(text)?.lowercase(Locale.ROOT)?.replace('ё', 'е') ?: return false
        return target in setOf("все", "всю информацию", "всю память", "everything", "all", "all memories")
    }

    fun shouldExtract(userText: String, projectId: Long?): Boolean {
        if (userText.isBlank() || explicitForgetTarget(userText) != null) return false
        if (explicitRememberText(userText) != null) return true
        if (projectId != null && userText.length >= 16) return true
        if (userText.length >= 220) return true
        return Regex(
            "(?iu)(?:\\bя\\s+(?:люблю|предпочитаю|хочу|использую|работаю|живу|называюсь|планирую)|" +
                "\\bмне\\s+(?:нравится|нужно|важно)|\\bмой|\\bмоя|\\bмои|\\bвсегда\\b|\\bникогда\\b|" +
                "\\bрешил|\\bрешили|\\bдоговорились|\\bдля\\s+проекта|\\bтребование|\\bограничение|" +
                "\\bi\\s+(?:prefer|like|use|work|live|am)|\\bmy\\s+|\\balways\\b|\\bnever\\b|\\bdecision\\b)"
        ).containsMatchIn(userText)
    }

    fun extractionPrompt(userText: String, assistantText: String, projectName: String?): String = buildString {
        append("CURRENT USER TURN:\n").append(redactSecrets(userText).take(6_000))
        append("\n\nASSISTANT RESULT (evidence only; do not promote unsupported claims):\n")
        append(redactSecrets(assistantText).take(5_000))
        if (!projectName.isNullOrBlank()) append("\n\nCURRENT PROJECT: ").append(projectName.take(120))
        append("\n\nExtract durable memory as strict JSON only.")
    }

    fun parseExtraction(raw: String): MemoryExtraction {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val first = cleaned.indexOf('{')
        val last = cleaned.lastIndexOf('}')
        if (first < 0 || last <= first) return MemoryExtraction(emptyList(), emptyList())
        val root = runCatching { JSONObject(cleaned.substring(first, last + 1)) }.getOrNull()
            ?: return MemoryExtraction(emptyList(), emptyList())
        val allowedKinds = MemoryKind.durable
        val memories = mutableListOf<MemoryCandidate>()
        val array = root.optJSONArray("memories") ?: JSONArray()
        for (i in 0 until array.length().coerceAtMost(30)) {
            val item = array.optJSONObject(i) ?: continue
            val kind = item.optString("kind").lowercase(Locale.ROOT).takeIf { it in allowedKinds } ?: MemoryKind.FACT
            val scope = item.optString("scope").lowercase(Locale.ROOT).takeIf {
                it == MemoryScope.GLOBAL || it == MemoryScope.PROJECT
            } ?: MemoryScope.GLOBAL
            val content = item.optString("content").trim().take(1_200)
            if (content.length < 3 || containsSecret(content)) continue
            val keywordArray = item.optJSONArray("keywords") ?: JSONArray()
            val keywordList = (0 until keywordArray.length().coerceAtMost(24))
                .mapNotNull { index -> keywordArray.optString(index).trim().takeIf(String::isNotBlank) }
            memories += MemoryCandidate(
                kind = kind,
                scope = scope,
                key = item.optString("key").trim().take(120),
                content = content,
                keywords = keywordList,
                importance = item.optInt("importance", 55).coerceIn(0, 100),
                confidence = item.optInt("confidence", 80).coerceIn(0, 100)
            )
        }
        val forgetArray = root.optJSONArray("forget") ?: JSONArray()
        val forget = (0 until forgetArray.length().coerceAtMost(20))
            .mapNotNull { forgetArray.optString(it).trim().take(200).takeIf(String::isNotBlank) }
        return MemoryExtraction(memories, forget)
    }

    fun score(item: MemoryItemEntity, query: String, projectId: Long?, now: Long = System.currentTimeMillis()): Double {
        val queryTokens = tokens(query)
        val contentTokens = tokens(item.content + " " + item.keywords + " " + item.normalizedKey)
        val overlap = queryTokens.count { it in contentTokens }
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        // A short "continue where we stopped" is meaningful for a recent episodic memory
        // even without shared topic words. Do not relax matching for other queries.
        val recentContinuation = item.kind == MemoryKind.EPISODE &&
            (now - item.updatedAt).coerceAtLeast(0L) <= 7L * 86_400_000L &&
            Regex("""(?iu)^(?:продолж(?:и|им|аем|ать|ить)(?![\p{L}\p{N}_])|на\s+ч[её]м\s+остановились(?![\p{L}\p{N}_])|continue(?![\p{L}\p{N}_]))""")
                .containsMatchIn(normalizedQuery)
        if (queryTokens.isNotEmpty() && overlap == 0 && !recentContinuation && !item.pinned && item.importance < 90) return -1.0
        if (item.kind == MemoryKind.EPISODE && queryTokens.isNotEmpty() && overlap == 0 && !recentContinuation) return -1.0
        if (queryTokens.isEmpty() && !item.pinned && item.importance < 75 && !recentContinuation) return -1.0

        var score = overlap * 18.0
        if (recentContinuation) score += 25.0
        if (queryTokens.isNotEmpty()) score += 24.0 * overlap / queryTokens.size.coerceAtLeast(1)
        if (normalizedQuery.length >= 5 && item.content.lowercase(Locale.ROOT).contains(normalizedQuery)) score += 28.0
        if (item.pinned) score += 34.0
        score += item.importance / 5.0
        score += item.confidence / 12.0
        if (item.scopeType == MemoryScope.PROJECT && projectId != null && item.scopeId == projectId) score += 18.0
        if (item.kind == MemoryKind.EPISODE) score -= 7.0
        val ageDays = ((now - item.updatedAt).coerceAtLeast(0L) / 86_400_000.0)
        score += (8.0 - ln(1.0 + ageDays) * 1.8).coerceAtLeast(0.0)
        score += (item.accessCount.coerceAtMost(20) * 0.35)
        return score
    }
}

