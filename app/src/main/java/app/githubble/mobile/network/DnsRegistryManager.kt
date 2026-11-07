package app.githubble.mobile.network

import android.content.Context
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DnsProfile(
    val id: String,
    val name: String,
    val mode: String,
    val endpoint: String,
    val domains: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val bootstrapIps: List<String> = emptyList(),
    val probeHosts: Map<String, List<String>> = emptyMap(),
    val priority: Int = 100,
    val enabled: Boolean = true,
    val source: String = "local"
) {
    fun matches(hostname: String): Boolean {
        if (domains.isEmpty()) return true
        val host = hostname.trim().trimEnd('.').lowercase()
        return domains.any { raw ->
            val rule = raw.trim().trimEnd('.').lowercase()
            when {
                rule.isBlank() -> false
                rule.startsWith("*.") -> {
                    val base = rule.removePrefix("*.")
                    host == base || host.endsWith("." + base)
                }
                rule.startsWith(".") -> {
                    val base = rule.removePrefix(".")
                    host == base || host.endsWith("." + base)
                }
                else -> host == rule || host.endsWith("." + rule)
            }
        }
    }
}

data class DnsProfileHealth(
    val ok: Boolean,
    val latencyMs: Long,
    val checkedAt: Long,
    val error: String? = null
)

data class DnsRegistrySnapshot(
    val remoteUrl: String,
    val localProfiles: List<DnsProfile>,
    val remoteProfiles: List<DnsProfile>,
    val lastUpdatedAt: Long,
    val lastError: String?
) {
    val allProfiles: List<DnsProfile> get() = localProfiles + remoteProfiles
}

/**
 * Dynamic DNS registry for OmniChat.
 *
 * DNS provider endpoints are never compiled into the app. Users can maintain local
 * profiles, while an owner/operator can publish an HTTPS JSON registry that is
 * refreshed independently from APK releases.
 */
object DnsRegistryManager {
    private const val PREFS = "dns_registry_v1"
    private const val KEY_REMOTE_URL = "remote_url"
    const val DEFAULT_REMOTE_URL =
        "https://raw.githubusercontent.com/mallstorm/OmniChat-DNS-Registry/main/dns-registry.json"
    private const val KEY_REMOTE_CACHE = "remote_cache"
    private const val KEY_REMOTE_UPDATED = "remote_updated"
    private const val KEY_LOCAL = "local_profiles"
    private const val MAX_REMOTE_BYTES = 512 * 1024
    private const val DEFAULT_REFRESH_MS = 6 * 60 * 60 * 1000L

    @Volatile private var appContext: Context? = null
    @Volatile private var localCache: List<DnsProfile> = emptyList()
    @Volatile private var remoteCache: List<DnsProfile> = emptyList()
    @Volatile private var lastError: String? = null
    private val health = ConcurrentHashMap<String, DnsProfileHealth>()

    private val registryClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs()
        localCache = decodeLocal(prefs.getString(KEY_LOCAL, "[]").orEmpty())
        remoteCache = parseRemote(prefs.getString(KEY_REMOTE_CACHE, "").orEmpty())
    }

    fun snapshot(): DnsRegistrySnapshot {
        val prefs = prefsOrNull()
        val remoteUrl = when {
            prefs == null -> DEFAULT_REMOTE_URL
            prefs.contains(KEY_REMOTE_URL) -> prefs.getString(KEY_REMOTE_URL, "").orEmpty()
            else -> DEFAULT_REMOTE_URL
        }
        return DnsRegistrySnapshot(
            remoteUrl = remoteUrl,
            localProfiles = localCache,
            remoteProfiles = remoteCache,
            lastUpdatedAt = prefs?.getLong(KEY_REMOTE_UPDATED, 0L) ?: 0L,
            lastError = lastError
        )
    }

    @Synchronized
    fun setRemoteRegistryUrl(value: String): DnsRegistrySnapshot {
        val normalized = normalizeRegistryUrl(value)
        prefs().edit().putString(KEY_REMOTE_URL, normalized).apply()
        lastError = null
        return snapshot()
    }

    @Synchronized
    fun addLocalProfile(
        name: String,
        mode: String,
        endpoint: String,
        domainsText: String,
        capabilitiesText: String = "",
        priority: Int = 50
    ): DnsProfile {
        val normalized = NetworkDnsPolicy.normalize(mode, endpoint)
        require(normalized.mode != NetworkDnsPolicy.MODE_SYSTEM && normalized.mode != NetworkDnsPolicy.MODE_SMART) {
            "Локальный профиль должен использовать DoH или DoT"
        }
        val profile = DnsProfile(
            id = "local:" + System.currentTimeMillis().toString(36),
            name = name.trim().ifBlank { "Мой DNS" },
            mode = normalized.mode,
            endpoint = normalized.endpoint,
            domains = parseDomains(domainsText),
            capabilities = parseCapabilities(capabilitiesText),
            priority = priority.coerceIn(0, 10_000),
            enabled = true,
            source = "local"
        )
        localCache = (localCache + profile).takeLast(50)
        persistLocal()
        NetworkDnsPolicy.clearCache()
        return profile
    }

    @Synchronized
    fun removeLocalProfile(id: String) {
        localCache = localCache.filterNot { it.id == id }
        health.remove(id)
        persistLocal()
        NetworkDnsPolicy.clearCache()
    }

    fun health(profileId: String): DnsProfileHealth? = health[profileId]

    fun recordHealth(profileId: String, ok: Boolean, latencyMs: Long, error: String? = null) {
        health[profileId] = DnsProfileHealth(ok, latencyMs, System.currentTimeMillis(), error)
    }

    fun profileById(id: String): DnsProfile? =
        (localCache + remoteCache).firstOrNull { it.id == id }

    fun profilesForService(service: String): List<DnsProfile> =
        (localCache + remoteCache)
            .filter { it.enabled }
            .filter {
                service == AiServiceResolver.GENERAL ||
                    it.capabilities.isEmpty() ||
                    service in it.capabilities
            }
            .sortedWith(compareBy<DnsProfile> { it.priority }
                .thenBy { if (it.source == "local") 0 else 1 }
                .thenBy { it.id })

    fun candidatesFor(hostname: String, service: String = AiServiceResolver.serviceForHost(hostname)): List<DnsProfile> {
        val capable = profilesForService(service)
        if (capable.isEmpty()) return emptyList()

        val specific = capable.filter { it.domains.isNotEmpty() && it.matches(hostname) }
        val generic = capable.filter { it.domains.isEmpty() }

        // Domain-specific routes always stay ahead of universal fallback profiles.
        return specific + generic
    }

    fun refreshIfStale(maxAgeMs: Long = DEFAULT_REFRESH_MS): DnsRegistrySnapshot {
        val snap = snapshot()
        if (snap.remoteUrl.isBlank()) return snap
        if (System.currentTimeMillis() - snap.lastUpdatedAt < maxAgeMs) return snap
        return refreshRemoteRegistry()
    }

    @Synchronized
    fun refreshRemoteRegistry(): DnsRegistrySnapshot {
        val url = snapshot().remoteUrl
        if (url.isBlank()) {
            lastError = "URL удалённого реестра не задан"
            return snapshot()
        }
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "OmniChat-DNS-Registry/1")
                .get()
                .build()
            registryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Registry HTTP " + response.code)
                val body = response.body?.bytes() ?: error("Пустой ответ реестра")
                if (body.size > MAX_REMOTE_BYTES) error("Реестр больше 512 KiB")
                val text = body.toString(Charsets.UTF_8)
                val parsed = parseRemoteStrict(text)
                remoteCache = parsed
                prefs().edit()
                    .putString(KEY_REMOTE_CACHE, text)
                    .putLong(KEY_REMOTE_UPDATED, System.currentTimeMillis())
                    .apply()
                lastError = null
                NetworkDnsPolicy.clearCache()
            }
            snapshot()
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            snapshot()
        }
    }

    private fun normalizeRegistryUrl(value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) return ""
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw IllegalArgumentException("Некорректный URL реестра")
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank())
            throw IllegalArgumentException("Реестр должен использовать https://")
        if (uri.userInfo != null || uri.fragment != null)
            throw IllegalArgumentException("URL реестра не должен содержать логин или fragment")
        return uri.toString()
    }

    private fun parseDomains(text: String): List<String> =
        text.split(',', ';', '\n', '\r', '\t', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(100)

    private fun parseDomains(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim().lowercase()
            if (value.isNotBlank()) out += value
        }
        return out.distinct().take(100)
    }

    private fun parseCapabilities(text: String): List<String> =
        text.split(',', ';', '\n', '\r', '\t', ' ')
            .map { it.trim().lowercase().replace('-', '_').replace(' ', '_') }
            .filter { it.matches(Regex("[a-z0-9_]{2,64}")) }
            .distinct()
            .take(50)

    private fun parseCapabilities(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i)
                .trim()
                .lowercase()
                .replace('-', '_')
                .replace(' ', '_')
            if (value.matches(Regex("[a-z0-9_]{2,64}"))) out += value
        }
        return out.distinct().take(50)
    }

    private fun parseBootstrapIps(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")) || value.contains(':')) {
                    add(value)
                }
            }
        }.distinct().take(8)
    }

    private fun parseProbeHosts(obj: JSONObject?): Map<String, List<String>> {
        if (obj == null) return emptyMap()
        val out = linkedMapOf<String, List<String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val service = rawKey.trim().lowercase().replace('-', '_').replace(' ', '_')
            if (!service.matches(Regex("[a-z0-9_]{2,64}"))) continue
            val value = obj.opt(rawKey)
            val hosts = mutableListOf<String>()
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    val host = when (item) {
                        is JSONObject -> item.optString("host")
                        is String -> item
                        else -> ""
                    }.trim().trimEnd('.').lowercase()
                    if (host.isNotBlank() && !host.contains('/') && !host.contains(' ')) hosts += host
                }
                is String -> {
                    val host = value.trim().trimEnd('.').lowercase()
                    if (host.isNotBlank() && !host.contains('/') && !host.contains(' ')) hosts += host
                }
            }
            if (hosts.isNotEmpty()) out[service] = hosts.distinct().take(6)
        }
        return out
    }

    private fun parseRemote(raw: String): List<DnsProfile> =
        if (raw.isBlank()) emptyList() else runCatching { parseRemoteStrict(raw) }.getOrDefault(emptyList())

    private fun parseRemoteStrict(raw: String): List<DnsProfile> {
        val root = JSONObject(raw)
        val version = root.optInt("version", 1)
        require(version == 1) { "Неподдерживаемая версия DNS Registry: $version" }
        val providers = root.optJSONArray("providers") ?: JSONArray()
        val out = mutableListOf<DnsProfile>()
        for (i in 0 until providers.length()) {
            val item = providers.optJSONObject(i) ?: continue
            val baseId = item.optString("id").trim().ifBlank { "provider-" + i }
            val name = item.optString("name").trim().ifBlank { baseId }
            val enabled = item.optBoolean("enabled", true)
            val priority = item.optInt("priority", 100).coerceIn(0, 10_000)
            val domains = parseDomains(item.optJSONArray("domains"))
            val capabilities = parseCapabilities(item.optJSONArray("capabilities"))
            val bootstrapIps = parseBootstrapIps(item.optJSONArray("bootstrap_ips"))
            val probeHosts = parseProbeHosts(item.optJSONObject("probes"))

            fun add(mode: String, endpoint: String, suffix: String) {
                if (endpoint.isBlank()) return
                val normalized = NetworkDnsPolicy.normalize(mode, endpoint)
                if (normalized.mode == NetworkDnsPolicy.MODE_SYSTEM || normalized.mode == NetworkDnsPolicy.MODE_SMART) return
                out += DnsProfile(
                    id = "remote:" + baseId + suffix,
                    name = name,
                    mode = normalized.mode,
                    endpoint = normalized.endpoint,
                    domains = domains,
                    capabilities = capabilities,
                    bootstrapIps = bootstrapIps,
                    probeHosts = probeHosts,
                    priority = priority,
                    enabled = enabled,
                    source = "remote"
                )
            }

            val directMode = item.optString("mode").trim()
            val directEndpoint = item.optString("endpoint").trim()
            if (directMode.isNotBlank() && directEndpoint.isNotBlank()) {
                add(directMode, directEndpoint, "")
            }

            val protocols = item.optJSONObject("protocols")
            if (protocols != null) {
                add(NetworkDnsPolicy.MODE_DOH, protocols.optString("doh").trim(), ":doh")
                add(NetworkDnsPolicy.MODE_DOT, protocols.optString("dot").trim(), ":dot")
            }
        }
        return out.distinctBy { it.id }.take(200)
    }

    private fun persistLocal() {
        val arr = JSONArray()
        localCache.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("mode", p.mode)
                    .put("endpoint", p.endpoint)
                    .put("domains", JSONArray(p.domains))
                    .put("capabilities", JSONArray(p.capabilities))
                    .put("bootstrap_ips", JSONArray(p.bootstrapIps))
                    .put("priority", p.priority)
                    .put("enabled", p.enabled)
            )
        }
        prefs().edit().putString(KEY_LOCAL, arr.toString()).apply()
    }

    private fun decodeLocal(raw: String): List<DnsProfile> {
        return runCatching {
            val arr = JSONArray(raw.ifBlank { "[]" })
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val normalized = NetworkDnsPolicy.normalize(
                        item.optString("mode"),
                        item.optString("endpoint")
                    )
                    if (normalized.mode == NetworkDnsPolicy.MODE_SYSTEM || normalized.mode == NetworkDnsPolicy.MODE_SMART) continue
                    add(
                        DnsProfile(
                            id = item.optString("id").ifBlank { "local:" + i },
                            name = item.optString("name").ifBlank { "Мой DNS" },
                            mode = normalized.mode,
                            endpoint = normalized.endpoint,
                            domains = parseDomains(item.optJSONArray("domains")),
                            capabilities = parseCapabilities(item.optJSONArray("capabilities")),
                            bootstrapIps = parseBootstrapIps(item.optJSONArray("bootstrap_ips")),
                            priority = item.optInt("priority", 50).coerceIn(0, 10_000),
                            enabled = item.optBoolean("enabled", true),
                            source = "local"
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs() =
        (appContext ?: throw IllegalStateException("DNS registry is not initialized"))
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun prefsOrNull() =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

