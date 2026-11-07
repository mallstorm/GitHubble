package app.githubble.mobile.agent

import android.content.Context
import app.githubble.mobile.security.KeyVault
import app.githubble.mobile.network.ApiFailure
import app.githubble.mobile.network.NetworkDnsPolicy
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Official Brave Search API: never mix this token with the existing Brave HTML scraper. */
internal object BraveSearchApiPolicy {
    const val API_URL = "https://api.search.brave.com/res/v1/web/search"
    const val DASHBOARD_URL = "https://api-dashboard.search.brave.com/"
    // Conservative local guardrail; the provider's billing dashboard remains authoritative.
    const val DAILY_CAP = 20
    const val MONTHLY_CAP = 400

    fun validKey(value: String): Boolean {
        val key = value.trim()
        return key.length in 12..512 && key.all { it.code in 33..126 }
    }

    fun url(query: String, count: Int = 8): HttpUrl {
        val clean = query.replace(Regex("\\s+"), " ").trim()
        require(clean.length in 2..300 && clean.split(' ').size <= 75) {
            "Brave: длина поискового запроса должна быть 2–300 символов, не более 75 слов"
        }
        return API_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", clean)
            .addQueryParameter("count", count.coerceIn(1, 20).toString())
            .addQueryParameter("result_filter", "web")
            .addQueryParameter("text_decorations", "false")
            .build()
    }

    fun parse(body: String): List<WebSearchHit> {
        val data = JSONObject(body).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        val hits = mutableListOf<WebSearchHit>()
        val dedup = mutableSetOf<String>()
        for (i in 0 until minOf(data.length(), 30)) {
            val item = data.optJSONObject(i) ?: continue
            val url = item.optString("url").trim()
            val canonical = WebSearchBackends.canonicalKey(url)
            if (canonical.isBlank() || !dedup.add(canonical)) continue
            val title = stripHtml(item.optString("title")).take(220)
            if (title.isBlank()) continue
            val snippet = stripHtml(item.optString("description")).take(600)
            hits += WebSearchHit(title, canonical, snippet, "brave-api")
            if (hits.size >= 12) break
        }
        return hits
    }

    private fun stripHtml(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    fun cycle(now: Date = Date()): Pair<String, String> {
        fun format(pattern: String): String =
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(now)
        return format("yyyy-MM-dd") to format("yyyy-MM")
    }

    fun canSpend(dayCount: Int, monthCount: Int): Boolean =
        dayCount < DAILY_CAP && monthCount < MONTHLY_CAP
}

/**
 * An opt-in encrypted local configuration, shared by UI and web search.
 * A request consumes one conservative local allowance before network I/O, including errors.
 * This does not replace monthly API billing caps in the Brave dashboard.
 */
class BraveSearchApiStore(context: Context) {
    private val app = context.applicationContext
    private val keyVault=KeyVault()
    private val prefs = app.getSharedPreferences("agent_integrations", Context.MODE_PRIVATE)

    data class Status(val hasKey: Boolean, val enabled: Boolean, val dailyUsed: Int, val monthlyUsed: Int)

    fun status(): Status = synchronized(lock) {
        val dayMonth = BraveSearchApiPolicy.cycle()
        val day = if (prefs.getString("brave_api_day", "") == dayMonth.first)
            prefs.getInt("brave_api_day_used", 0) else 0
        val month = if (prefs.getString("brave_api_month", "") == dayMonth.second)
            prefs.getInt("brave_api_month_used", 0) else 0
        val hasKey = prefs.getString("brave_api_secret_v1", "").orEmpty().isNotBlank()
        Status(hasKey, prefs.getBoolean("brave_api_enabled", false) && hasKey, day, month)
    }

    fun saveAndEnable(rawKey: String) = synchronized(lock) {
        val key = rawKey.trim()
        require(BraveSearchApiPolicy.validKey(key)) {
            "Brave API-ключ должен содержать 12–512 печатных символов, без пробелов"
        }
        val encrypted = keyVault.encrypt(key)
        check(prefs.edit()
            .putString("brave_api_secret_v1", encrypted)
            .putBoolean("brave_api_enabled", true).commit()) {
                "Не удалось сохранить Brave API-ключ"
            }
    }

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        if (enabled) {
            val cipher = prefs.getString("brave_api_secret_v1", "").orEmpty()
            val key = runCatching { keyVault.decrypt(cipher).trim() }.getOrNull().orEmpty()
            require(BraveSearchApiPolicy.validKey(key)) { "Сначала сохраните действительный Brave API-ключ" }
        }
        prefs.edit().putBoolean("brave_api_enabled", enabled).apply()
    }

    fun remove() = synchronized(lock) {
        prefs.edit().remove("brave_api_secret_v1").putBoolean("brave_api_enabled", false).apply()
    }

    fun reserveTokenForSearch(): String? = synchronized(lock) {
        val state = status()
        if (!state.enabled || !BraveSearchApiPolicy.canSpend(state.dailyUsed, state.monthlyUsed))
            return@synchronized null
        val cipher = prefs.getString("brave_api_secret_v1", "").orEmpty()
        val key = runCatching { keyVault.decrypt(cipher).trim() }.getOrNull().orEmpty()
        if (!BraveSearchApiPolicy.validKey(key)) return@synchronized null
        val (day, month) = BraveSearchApiPolicy.cycle()
        val wrote = prefs.edit()
            .putString("brave_api_day", day).putInt("brave_api_day_used", state.dailyUsed + 1)
            .putString("brave_api_month", month).putInt("brave_api_month_used", state.monthlyUsed + 1)
            .commit()
        if (wrote) key else null
    }

    private companion object { val lock = Any() }
}

/**
 * Fixed-host search with header-only authentication and no redirect forwarding.
 * Never logs a key, injects it in the URL, or retries billable requests automatically.
 */
internal class BraveSearchApiClient(context: Context) {
    private val store = BraveSearchApiStore(context)
    private val client = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns(publicOnly = true))
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** null means disabled or local budget exhausted; caller should use the HTML fallback. */
    fun searchIfEnabled(query: String): List<WebSearchHit>? {
        val token = store.reserveTokenForSearch() ?: return null
        val url = BraveSearchApiPolicy.url(query)
        val request = Request.Builder().url(url)
            .header("X-Subscription-Token", token)
            .header("Accept", "application/json")
            .header("User-Agent", "OmniChat-Android")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = when (response.code) {
                    401, 403 -> "API-ключ не принят"
                    402 -> "исчерпаны кредиты"
                    429 -> "квота или ограничение частоты"
                    else -> "HTTP ${response.code}"
                }
                throw ApiFailure("Brave Search API: $detail")
            }
            val input = response.body?.byteStream() ?: throw ApiFailure("Brave Search API: пустой ответ")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val maxBytes = 350_000
            while (out.size() <= maxBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            if (out.size() > maxBytes) throw ApiFailure("Brave Search API: ответ превышает 350 КБ")
            return BraveSearchApiPolicy.parse(out.toString("UTF-8"))
        }
    }

    fun status(): BraveSearchApiStore.Status = store.status()
}

