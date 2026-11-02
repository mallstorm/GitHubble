package app.githubble.mobile.agent

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val backend: String
)

/**
 * Keyless public-search adapters. No single engine is authoritative: WorkspaceTools
 * queries all of them concurrently and merges results round-robin.
 */
internal object WebSearchBackends {
    fun parseBingRss(body: String): List<WebSearchHit> =
        Regex("""(?is)<item\b[^>]*>(.*?)</item>""").findAll(body).mapNotNull { match ->
            val block = match.groupValues[1]
            val title = tag(block, "title").let(::text)
            val url = normalizeUrl(tag(block, "link"))
            val snippet = tag(block, "description").let(::text)
            if (title.isBlank() || url.isBlank()) null
            else WebSearchHit(title.take(220), url, snippet.take(600), "bing")
        }.distinctBy { canonicalKey(it.url) }.take(12).toList()

    fun parseBraveHtml(body: String): List<WebSearchHit> =
        parseExternalAnchors(body, backend = "brave", engineHosts = setOf("brave.com", "search.brave.com"))

    fun parseMojeekHtml(body: String): List<WebSearchHit> =
        parseExternalAnchors(body, backend = "mojeek", engineHosts = setOf("mojeek.com", "www.mojeek.com"))

    fun mergeRoundRobin(batches: List<List<WebSearchHit>>, limit: Int): List<WebSearchHit> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<WebSearchHit>()
        val seen = linkedSetOf<String>()
        var row = 0
        while (result.size < limit) {
            var added = false
            for (batch in batches) {
                val hit = batch.getOrNull(row) ?: continue
                val key = canonicalKey(hit.url)
                if (key.isNotBlank() && seen.add(key)) {
                    result += hit
                    added = true
                    if (result.size >= limit) break
                }
            }
            if (!added && batches.none { it.size > row + 1 }) break
            row++
            if (row > 30) break
        }
        return result
    }

    fun independentHosts(hits: List<WebSearchHit>, limit: Int): List<WebSearchHit> {
        if (limit <= 0) return emptyList()
        val unique = mutableListOf<WebSearchHit>()
        val repeated = mutableListOf<WebSearchHit>()
        val hosts = linkedSetOf<String>()
        for (hit in hits) {
            val host = hostKey(hit.url)
            if (host.isNotBlank() && hosts.add(host)) unique += hit else repeated += hit
        }
        return (unique + repeated).take(limit)
    }

    internal fun canonicalKey(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return ""
        if (url.scheme != "https") return ""
        val builder = url.newBuilder().fragment(null)
        url.queryParameterNames.forEach { name ->
            val n = name.lowercase()
            if (n.startsWith("utm_") || n in setOf("fbclid", "gclid", "yclid", "mc_cid", "mc_eid", "ref", "source")) {
                builder.removeAllQueryParameters(name)
            }
        }
        return builder.build().toString().trimEnd('/')
    }

    internal fun hostKey(raw: String): String =
        raw.toHttpUrlOrNull()?.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.").orEmpty()

    private fun parseExternalAnchors(
        body: String,
        backend: String,
        engineHosts: Set<String>
    ): List<WebSearchHit> {
        val results = mutableListOf<WebSearchHit>()
        val anchors = Regex("""(?is)<a\b[^>]*href\s*=\s*["'](https://[^"'#\s]+)["'][^>]*>(.*?)</a>""")
        for (match in anchors.findAll(body)) {
            if (results.size >= 12) break
            val url = normalizeUrl(match.groupValues[1])
            val parsed = url.toHttpUrlOrNull() ?: continue
            val host = parsed.host.lowercase()
            if (engineHosts.any { host == it || host.endsWith(".$it") }) continue
            val title = text(match.groupValues[2]).take(220)
            if (title.length < 8 || looksLikeNavigation(title)) continue
            val from = (match.range.last + 1).coerceAtMost(body.length)
            val to = (from + 1800).coerceAtMost(body.length)
            val tail = body.substring(from, to)
            val snippetRaw = Regex("""(?is)<(?:p|div)[^>]*class\s*=\s*["'][^"']*(?:snippet|description|desc|abstract)[^"']*["'][^>]*>(.*?)</(?:p|div)>""")
                .find(tail)?.groupValues?.getOrNull(1)
                ?: Regex("""(?is)<p\b[^>]*>(.*?)</p>""").find(tail)?.groupValues?.getOrNull(1).orEmpty()
            val snippet = text(snippetRaw).take(600)
            val hit = WebSearchHit(title, url, snippet, backend)
            if (results.none { canonicalKey(it.url) == canonicalKey(hit.url) }) results += hit
        }
        return results
    }

    private fun looksLikeNavigation(title: String): Boolean {
        val value = title.trim().lowercase()
        return value in setOf(
            "images", "videos", "news", "maps", "settings", "privacy", "help",
            "картинки", "видео", "новости", "карты", "настройки", "помощь"
        )
    }

    private fun normalizeUrl(raw: String): String {
        val decoded = raw.replace("&amp;", "&").trim()
        val url = decoded.toHttpUrlOrNull() ?: return ""
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) return ""
        return url.newBuilder().fragment(null).build().toString()
    }

    private fun tag(block: String, name: String): String =
        Regex("""(?is)<$name\b[^>]*>(.*?)</$name>""").find(block)?.groupValues?.getOrNull(1).orEmpty()

    private fun text(value: String): String = value
        .replace(Regex("""(?is)<!\[CDATA\[(.*?)]]>""")) { it.groupValues[1] }
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
}

