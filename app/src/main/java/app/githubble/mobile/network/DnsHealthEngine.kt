package app.githubble.mobile.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class DnsRouteView(
    val service: String,
    val profileId: String,
    val profileName: String,
    val score: Double,
    val selected: Boolean,
    val dnsMs: Double?,
    val tlsMs: Double?,
    val httpMs: Double?,
    val successRate: Double?,
    val cooldownUntil: Long,
    val lastError: String?
)

data class DnsRoutingSnapshot(
    val networkKey: String = "unknown",
    val updatedAt: Long = 0L,
    val routes: List<DnsRouteView> = emptyList()
)

/**
 * Active route health:
 * DNS lookup -> TLS handshake -> anonymous HTTPS reachability.
 *
 * No API keys, OAuth tokens, cookies, prompts or account identifiers are ever sent.
 */
object DnsHealthEngine {
    private const val CHECK_COOLDOWN_MS = 3 * 60_000L
    private const val NETWORK_SWEEP_COOLDOWN_MS = 2 * 60_000L
    private const val MAX_PARALLEL_PROBES = 3
    private const val PERIODIC_HOURS = 6L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestedAt = ConcurrentHashMap<String, Long>()
    private val semaphore = Semaphore(MAX_PARALLEL_PROBES)
    private val _status = MutableStateFlow(DnsRoutingSnapshot())
    val status: StateFlow<DnsRoutingSnapshot> = _status

    @Volatile private var appContext: Context? = null
    @Volatile private var initialized = false
    @Volatile private var lastNetworkSweepAt = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            DnsRouteStore.initialize(context.applicationContext)
            registerNetworkCallback(context.applicationContext)
            schedulePeriodic(context.applicationContext)
            initialized = true
        }
        scope.launchSafely {
            delay(1_500L)
            runSweep()
        }
    }

    fun requestServiceCheck(service: String) {
        if (service == AiServiceResolver.GENERAL) return
        val network = DnsRouteStore.networkKey()
        val key = "$network|$service"
        val now = System.currentTimeMillis()
        val previous = requestedAt[key] ?: 0L
        if (now - previous < CHECK_COOLDOWN_MS) return
        requestedAt[key] = now
        scope.launchSafely { runSweep(setOf(service)) }
    }

    suspend fun runSweep(serviceIds: Set<String> = emptySet()) {
        val network = DnsRouteStore.networkKey()
        if (network == "offline") {
            publishSnapshot()
            return
        }
        val profiles = DnsRegistryManager.snapshot().allProfiles.filter { it.enabled }
        if (profiles.isEmpty()) {
            publishSnapshot()
            return
        }

        val services = if (serviceIds.isNotEmpty()) {
            serviceIds
        } else {
            profiles.flatMap { it.capabilities }
                .filter { it != AiServiceResolver.GENERAL }
                .toSet()
                .ifEmpty { setOf(AiServiceResolver.OPENAI, AiServiceResolver.GEMINI) }
        }

        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            for (service in services) {
                val hostFallback = AiServiceResolver.defaultProbeHost(service) ?: continue
                val candidates = DnsRegistryManager.profilesForService(service)
                    .take(8)
                for (profile in candidates) {
                    jobs += async {
                        semaphore.withPermit {
                            probeProfile(network, service, profile, hostFallback)
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
        publishSnapshot()
    }

    fun publishSnapshot() {
        val network = DnsRouteStore.networkKey()
        val profiles = DnsRegistryManager.snapshot().allProfiles.filter { it.enabled }
        val services = profiles.flatMap { it.capabilities }.distinct()
        val out = mutableListOf<DnsRouteView>()
        for (service in services) {
            val candidates = DnsRegistryManager.profilesForService(service)
            val stats = DnsRouteStore.statsFor(network, service)
            val ranked = DnsRouteScorer.rank(candidates, stats)
            val lease = DnsRouteStore.currentLease(network, service)
            val chosen = DnsRouteScorer.chooseWithHysteresis(ranked, lease)
            ranked.forEach { item ->
                val s = item.stats
                val attempts = (s?.successes ?: 0) + (s?.failures ?: 0)
                out += DnsRouteView(
                    service = service,
                    profileId = item.profile.id,
                    profileName = item.profile.name,
                    score = item.score,
                    selected = item.profile.id == chosen?.profile?.id,
                    dnsMs = s?.dnsEwmaMs,
                    tlsMs = s?.tlsEwmaMs,
                    httpMs = s?.httpEwmaMs,
                    successRate = if (attempts > 0) (s?.successes ?: 0).toDouble() / attempts else null,
                    cooldownUntil = s?.cooldownUntil ?: 0L,
                    lastError = s?.lastError
                )
            }
        }
        _status.value = DnsRoutingSnapshot(network, System.currentTimeMillis(), out)
    }

    private suspend fun probeProfile(
        network: String,
        service: String,
        profile: DnsProfile,
        fallbackHost: String
    ) {
        val host = profile.probeHosts[service]?.firstOrNull()?.ifBlank { null } ?: fallbackHost
        var dnsMs: Long? = null
        var tlsMs: Long? = null
        var httpMs: Long? = null
        try {
            val dnsStarted = System.nanoTime()
            val addresses = NetworkDnsPolicy.lookupForProfile(profile, host)
            dnsMs = elapsedMs(dnsStarted)
            require(addresses.isNotEmpty()) { "DNS не вернул адрес" }

            val tlsStarted = System.nanoTime()
            probeTls(host, addresses)
            tlsMs = elapsedMs(tlsStarted)

            val httpStarted = System.nanoTime()
            probeHttps(host, addresses)
            httpMs = elapsedMs(httpStarted)

            DnsRouteStore.record(
                network, service, profile.id, true,
                dnsMs = dnsMs, tlsMs = tlsMs, httpMs = httpMs
            )
            DnsRegistryManager.recordHealth(profile.id, true, dnsMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DnsRouteStore.record(
                network, service, profile.id, false,
                dnsMs = dnsMs, tlsMs = tlsMs, httpMs = httpMs,
                error = error.message ?: error.javaClass.simpleName
            )
            DnsRegistryManager.recordHealth(
                profile.id, false, dnsMs ?: 30_000L,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun probeTls(host: String, addresses: List<InetAddress>) {
        var last: Exception? = null
        for (address in addresses.take(3)) {
            try {
                val raw = Socket()
                raw.connect(InetSocketAddress(address, 443), 4_000)
                raw.soTimeout = 4_000
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, 443, true) as SSLSocket
                tls.use {
                    val params = it.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    params.serverNames = listOf(SNIHostName(host))
                    it.sslParameters = params
                    it.startHandshake()
                }
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("TLS недоступен")
    }

    private fun probeHttps(host: String, addresses: List<InetAddress>) {
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(requested: String): List<InetAddress> =
                    if (requested.equals(host, ignoreCase = true)) addresses
                    else Dns.SYSTEM.lookup(requested)
            })
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url("https://$host/")
            .head()
            .header("User-Agent", "OmniChat-DNS-Health/2")
            .build()
        client.newCall(request).execute().use {
            // Any valid HTTP response proves DNS + TCP + TLS + HTTP reachability.
            if (it.code < 100) error("Некорректный HTTP ответ")
        }
    }

    private fun registerNetworkCallback(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = requestNetworkSweep()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = requestNetworkSweep()
            override fun onLost(network: Network) {
                publishSnapshot()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun requestNetworkSweep() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkSweepAt < NETWORK_SWEEP_COOLDOWN_MS) return
        lastNetworkSweepAt = now
        scope.launchSafely {
            delay(1_000L)
            runSweep()
        }
    }

    private fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<DnsHealthWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "omnichat-dns-health-v2",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun elapsedMs(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

    private fun CoroutineScope.launchSafely(block: suspend () -> Unit) =
        launch {
            runCatching { block() }
        }
}

class DnsHealthWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            DnsRegistryManager.refreshIfStale()
            DnsHealthEngine.runSweep()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

