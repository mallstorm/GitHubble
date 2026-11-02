package app.githubble.mobile.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs

data class DnsRouteStats(
    val networkKey: String,
    val service: String,
    val profileId: String,
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val dnsEwmaMs: Double? = null,
    val tlsEwmaMs: Double? = null,
    val httpEwmaMs: Double? = null,
    val jitterEwmaMs: Double = 0.0,
    val lastTotalMs: Double? = null,
    val lastCheckedAt: Long = 0L,
    val cooldownUntil: Long = 0L,
    val lastError: String? = null
)

data class DnsRouteLease(
    val networkKey: String,
    val service: String,
    val profileId: String,
    val until: Long
)

/**
 * Persistent Smart Routing health history.
 *
 * No SSID, account ID, token or user content is stored. The network fingerprint is a
 * short hash of transport type, metered state and system DNS addresses.
 */
object DnsRouteStore {
    private const val PREFS = "dns_route_store_v2"
    private const val KEY_STATS = "stats"
    private const val KEY_LEASES = "leases"
    private const val MAX_STATS = 600
    private const val EWMA_ALPHA = 0.35

    @Volatile private var appContext: Context? = null
    private val lock = Any()
    private val stats = LinkedHashMap<String, DnsRouteStats>()
    private val leases = LinkedHashMap<String, DnsRouteLease>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            stats.clear()
            leases.clear()
            loadLocked()
        }
    }

    fun networkKey(): String {
        val context = appContext ?: return "unknown"
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return@runCatching "offline"
            val caps = cm.getNetworkCapabilities(network)
            val transport = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "vpn"
                else -> "other"
            }
            val metered = if (cm.isActiveNetworkMetered) "metered" else "unmetered"
            val dns = cm.getLinkProperties(network)?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.sorted()
                ?.joinToString(",")
                .orEmpty()
            val seed = "$transport|$metered|$dns"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            "$transport:$digest"
        }.getOrDefault("unknown")
    }

    fun stats(networkKey: String, service: String, profileId: String): DnsRouteStats? =
        synchronized(lock) { stats[key(networkKey, service, profileId)] }

    fun statsFor(networkKey: String, service: String): Map<String, DnsRouteStats> =
        synchronized(lock) {
            stats.values
                .filter { it.networkKey == networkKey && it.service == service }
                .associateBy { it.profileId }
        }

    fun record(
        networkKey: String,
        service: String,
        profileId: String,
        success: Boolean,
        dnsMs: Long? = null,
        tlsMs: Long? = null,
        httpMs: Long? = null,
        error: String? = null,
        now: Long = System.currentTimeMillis()
    ): DnsRouteStats {
        synchronized(lock) {
            val k = key(networkKey, service, profileId)
            val old = stats[k] ?: DnsRouteStats(networkKey, service, profileId)
            val total = listOfNotNull(dnsMs, tlsMs, httpMs).sum().toDouble().takeIf {
                dnsMs != null || tlsMs != null || httpMs != null
            }
            val jitterSample = if (total != null && old.lastTotalMs != null) abs(total - old.lastTotalMs) else null
            val consecutive = if (success) 0 else old.consecutiveFailures + 1
            val cooldown = if (success) 0L else now + cooldownMs(consecutive)
            val updated = old.copy(
                successes = old.successes + if (success) 1 else 0,
                failures = old.failures + if (success) 0 else 1,
                consecutiveFailures = consecutive,
                dnsEwmaMs = ewma(old.dnsEwmaMs, dnsMs?.toDouble()),
                tlsEwmaMs = ewma(old.tlsEwmaMs, tlsMs?.toDouble()),
                httpEwmaMs = ewma(old.httpEwmaMs, httpMs?.toDouble()),
                jitterEwmaMs = ewma(old.jitterEwmaMs.takeIf { old.lastCheckedAt > 0 }, jitterSample) ?: 0.0,
                lastTotalMs = total ?: old.lastTotalMs,
                lastCheckedAt = now,
                cooldownUntil = cooldown,
                lastError = if (success) null else error?.take(240)
            )
            stats[k] = updated
            trimLocked()
            persistLocked()
            return updated
        }
    }

    fun currentLease(networkKey: String, service: String): DnsRouteLease? =
        synchronized(lock) { leases[leaseKey(networkKey, service)] }

    fun setLease(networkKey: String, service: String, profileId: String, until: Long) {
        synchronized(lock) {
            leases[leaseKey(networkKey, service)] = DnsRouteLease(networkKey, service, profileId, until)
            persistLocked()
        }
    }

    fun clearLease(networkKey: String, service: String) {
        synchronized(lock) {
            leases.remove(leaseKey(networkKey, service))
            persistLocked()
        }
    }

    private fun cooldownMs(failures: Int): Long = when {
        failures <= 1 -> 60_000L
        failures == 2 -> 5 * 60_000L
        failures == 3 -> 15 * 60_000L
        else -> 30 * 60_000L
    }

    private fun ewma(previous: Double?, sample: Double?): Double? = when {
        sample == null -> previous
        previous == null -> sample
        else -> previous * (1.0 - EWMA_ALPHA) + sample * EWMA_ALPHA
    }

    private fun key(networkKey: String, service: String, profileId: String) =
        "$networkKey|$service|$profileId"

    private fun leaseKey(networkKey: String, service: String) = "$networkKey|$service"

    private fun trimLocked() {
        if (stats.size <= MAX_STATS) return
        stats.entries.sortedBy { it.value.lastCheckedAt }
            .take(stats.size - MAX_STATS)
            .forEach { stats.remove(it.key) }
    }

    private fun loadLocked() {
        val prefs = prefsOrNull() ?: return
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_STATS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val item = DnsRouteStats(
                    networkKey = o.optString("network"),
                    service = o.optString("service"),
                    profileId = o.optString("profile"),
                    successes = o.optInt("successes"),
                    failures = o.optInt("failures"),
                    consecutiveFailures = o.optInt("consecutiveFailures"),
                    dnsEwmaMs = o.optNullableDouble("dns"),
                    tlsEwmaMs = o.optNullableDouble("tls"),
                    httpEwmaMs = o.optNullableDouble("http"),
                    jitterEwmaMs = o.optDouble("jitter", 0.0),
                    lastTotalMs = o.optNullableDouble("lastTotal"),
                    lastCheckedAt = o.optLong("checkedAt"),
                    cooldownUntil = o.optLong("cooldownUntil"),
                    lastError = o.optString("error").takeIf { it.isNotBlank() }
                )
                if (item.networkKey.isNotBlank() && item.service.isNotBlank() && item.profileId.isNotBlank()) {
                    stats[key(item.networkKey, item.service, item.profileId)] = item
                }
            }
        }
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_LEASES, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val lease = DnsRouteLease(
                    o.optString("network"),
                    o.optString("service"),
                    o.optString("profile"),
                    o.optLong("until")
                )
                if (lease.networkKey.isNotBlank() && lease.service.isNotBlank() && lease.profileId.isNotBlank()) {
                    leases[leaseKey(lease.networkKey, lease.service)] = lease
                }
            }
        }
    }

    private fun persistLocked() {
        val prefs = prefsOrNull() ?: return
        val statArray = JSONArray()
        stats.values.forEach { s ->
            statArray.put(JSONObject()
                .put("network", s.networkKey)
                .put("service", s.service)
                .put("profile", s.profileId)
                .put("successes", s.successes)
                .put("failures", s.failures)
                .put("consecutiveFailures", s.consecutiveFailures)
                .put("dns", s.dnsEwmaMs ?: JSONObject.NULL)
                .put("tls", s.tlsEwmaMs ?: JSONObject.NULL)
                .put("http", s.httpEwmaMs ?: JSONObject.NULL)
                .put("jitter", s.jitterEwmaMs)
                .put("lastTotal", s.lastTotalMs ?: JSONObject.NULL)
                .put("checkedAt", s.lastCheckedAt)
                .put("cooldownUntil", s.cooldownUntil)
                .put("error", s.lastError ?: "")
            )
        }
        val leaseArray = JSONArray()
        leases.values.forEach { l ->
            leaseArray.put(JSONObject()
                .put("network", l.networkKey)
                .put("service", l.service)
                .put("profile", l.profileId)
                .put("until", l.until)
            )
        }
        prefs.edit()
            .putString(KEY_STATS, statArray.toString())
            .putString(KEY_LEASES, leaseArray.toString())
            .apply()
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).takeIf { !it.isNaN() }
    }

    private fun prefsOrNull() =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

