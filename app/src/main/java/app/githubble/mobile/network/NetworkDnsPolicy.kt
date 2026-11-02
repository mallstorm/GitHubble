package app.githubble.mobile.network

import android.content.Context
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.IOException
import java.io.DataOutputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class DnsSettings(val mode: String = "system", val endpoint: String = "")

data class DnsProbeResult(
    val mode: String,
    val endpoint: String,
    val hostname: String,
    val addresses: List<String>,
    val latencyMs: Long,
    val ok: Boolean,
    val error: String? = null
)

/**
 * App-scoped DNS policy ported from OmniCodeIDE.
 *
 * It only changes name resolution performed by OmniChat's own OkHttp clients.
 * It never changes Android's global Private DNS / Wi-Fi configuration.
 *
 * DoH uses RFC 8484 wire-format POST requests. DoT uses RFC 7858 length-prefixed
 * DNS messages over a TLS socket. The resolver endpoint itself is bootstrapped
 * through the system resolver, matching OmniCode's behavior and avoiding recursion.
 */
object NetworkDnsPolicy {
    const val MODE_SYSTEM = "system"
    const val MODE_SMART = "smart"
    const val MODE_DOH = "doh"
    const val MODE_DOT = "dot"

    private data class SmartLookupResult(
        val addresses: List<InetAddress>,
        val profile: DnsProfile?
    )

    private const val PREFS = "network_dns"
    private const val KEY_MODE = "mode"
    private const val KEY_ENDPOINT = "endpoint"
    private const val CACHE_MS = 60_000L
    private const val DNS_A = 1
    private const val DNS_AAAA = 28
    private const val DNS_IN = 1

    @Volatile private var appContext: Context? = null
    private val random = SecureRandom()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val activeRouteByHost = ConcurrentHashMap<String, String>()
    private val dohBootstrapClients = ConcurrentHashMap<String, OkHttpClient>()
    private val dnsMediaType = "application/dns-message".toMediaType()

    private val bootstrapHttp by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun current(): DnsSettings {
        val context = appContext ?: return DnsSettings()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            normalize(
                prefs.getString(KEY_MODE, MODE_SYSTEM).orEmpty(),
                prefs.getString(KEY_ENDPOINT, "").orEmpty()
            )
        }.getOrDefault(DnsSettings())
    }

    fun save(mode: String, endpoint: String): DnsSettings {
        val normalized = normalize(mode, endpoint)
        val context = appContext ?: throw IllegalStateException("DNS policy is not initialized")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, normalized.mode)
            .putString(KEY_ENDPOINT, normalized.endpoint)
            .apply()
        cache.clear()
        return normalized
    }

    fun clearCache() {
        cache.clear()
        activeRouteByHost.clear()
    }

    /**
     * Adds real request feedback to Smart Routing. Any HTTP response means the network
     * path worked; transport exceptions penalize the resolver used for this hostname.
     */
    fun feedbackInterceptor(): Interceptor = Interceptor { chain ->
        val host = chain.request().url.host
        val started = System.nanoTime()
        try {
            val response = chain.proceed(chain.request())
            reportTransport(host, true, (System.nanoTime() - started) / 1_000_000L, null)
            response
        } catch (error: IOException) {
            reportTransport(
                host,
                false,
                (System.nanoTime() - started) / 1_000_000L,
                error.message ?: error.javaClass.simpleName
            )
            throw error
        }
    }

    private fun reportTransport(hostname: String, success: Boolean, elapsedMs: Long, error: String?) {
        if (current().mode != MODE_SMART) return
        val service = AiServiceResolver.serviceForHost(hostname)
        if (service == AiServiceResolver.GENERAL) return
        val network = DnsRouteStore.networkKey()
        val routeKey = activeRouteKey(network, hostname)
        val profileId = activeRouteByHost[routeKey] ?: return
        DnsRouteStore.record(
            networkKey = network,
            service = service,
            profileId = profileId,
            success = success,
            httpMs = elapsedMs,
            error = error
        )
        if (!success) {
            DnsRouteStore.clearLease(network, service)
            cache.keys.filter { it.startsWith("smart|$network|$service|") }.forEach(cache::remove)
        }
        DnsHealthEngine.publishSnapshot()
        if (!success) DnsHealthEngine.requestServiceCheck(service)
    }

    fun normalize(modeInput: String, endpointInput: String): DnsSettings {
        var mode = modeInput.trim().lowercase()
        var raw = endpointInput.trim()
        if (mode.isBlank()) {
            mode = when {
                raw.startsWith("tls://", ignoreCase = true) -> MODE_DOT
                raw.isBlank() -> MODE_SYSTEM
                else -> MODE_DOH
            }
        }
        if (mode == MODE_SYSTEM || mode == "inherit") return DnsSettings(MODE_SYSTEM, "")
        if (mode == MODE_SMART || mode == "auto") return DnsSettings(MODE_SMART, "")
        if (mode == "doq" || mode == "quic")
            throw IllegalArgumentException("DNS-over-QUIC пока не поддерживается. Выберите DoH или DoT.")
        if (mode !in setOf(MODE_DOH, MODE_DOT))
            throw IllegalArgumentException("Неизвестный режим DNS")

        if (raw.isBlank()) throw IllegalArgumentException("Укажите DNS endpoint")

        if (mode == MODE_DOH) {
            if (!raw.contains("://")) raw = "https://" + raw.trimEnd('/') + "/dns-query"
            val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("Некорректный DoH endpoint")
            if (!url.isHttps) throw IllegalArgumentException("DoH endpoint должен использовать https://")
            if (url.username.isNotEmpty() || url.password.isNotEmpty())
                throw IllegalArgumentException("Учётные данные в DNS URL запрещены")
            val normalized = if (url.encodedPath == "/" || url.encodedPath.isBlank()) {
                url.newBuilder().encodedPath("/dns-query").build().toString()
            } else url.toString()
            return DnsSettings(MODE_DOH, normalized)
        }

        if (!raw.contains("://")) raw = "tls://$raw"
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw IllegalArgumentException("Некорректный DoT endpoint")
        if (!uri.scheme.equals("tls", ignoreCase = true) || uri.host.isNullOrBlank())
            throw IllegalArgumentException("DoT endpoint должен иметь вид tls://dns.example.org:853")
        if (uri.userInfo != null || uri.query != null || uri.fragment != null)
            throw IllegalArgumentException("DoT endpoint не должен содержать логин, query или fragment")
        val port = if (uri.port == -1) 853 else uri.port
        if (port !in 1..65535) throw IllegalArgumentException("Некорректный порт DoT")
        val host = uri.host
        val endpoint = if (port == 853 && uri.port == -1) "tls://$host" else "tls://$host:$port"
        return DnsSettings(MODE_DOT, endpoint)
    }

    /**
     * Dynamic OkHttp DNS implementation: every lookup reads the current preference,
     * so changing DNS in Settings takes effect without restarting OmniChat.
     */
    fun dns(publicOnly: Boolean = false): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = this@NetworkDnsPolicy.lookup(hostname)
            if (addresses.isEmpty()) throw UnknownHostException("DNS не вернул адрес для $hostname")
            if (publicOnly && addresses.any { !isPublicAddress(it) })
                throw UnknownHostException("Закрытые и зарезервированные IP запрещены")
            return addresses
        }
    }

    fun probe(hostnameInput: String): DnsProbeResult =
        probe(current().mode, current().endpoint, hostnameInput)

    fun probe(mode: String, endpoint: String, hostnameInput: String): DnsProbeResult {
        val hostname = hostnameInput.trim().ifBlank { "example.com" }
        val settings = normalize(mode, endpoint)
        val started = System.nanoTime()
        return try {
            if (settings.mode == MODE_SMART) {
                val smart = lookupSmart(hostname, useCache = false)
                DnsProbeResult(
                    mode = settings.mode,
                    endpoint = smart.profile?.let { it.name + " · " + it.endpoint } ?: "System fallback",
                    hostname = hostname,
                    addresses = smart.addresses.map { it.hostAddress ?: it.toString() },
                    latencyMs = (System.nanoTime() - started) / 1_000_000L,
                    ok = smart.addresses.isNotEmpty()
                )
            } else {
                val addresses = lookupUncached(hostname, settings)
                DnsProbeResult(
                    mode = settings.mode,
                    endpoint = settings.endpoint,
                    hostname = hostname,
                    addresses = addresses.map { it.hostAddress ?: it.toString() },
                    latencyMs = (System.nanoTime() - started) / 1_000_000L,
                    ok = addresses.isNotEmpty()
                )
            }
        } catch (e: Exception) {
            DnsProbeResult(
                mode = settings.mode,
                endpoint = settings.endpoint,
                hostname = hostname,
                addresses = emptyList(),
                latencyMs = (System.nanoTime() - started) / 1_000_000L,
                ok = false,
                error = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun lookup(hostname: String): List<InetAddress> {
        if (isIpLiteral(hostname)) return listOf(InetAddress.getByName(hostname))
        val settings = current()
        if (settings.mode == MODE_SYSTEM) return Dns.SYSTEM.lookup(hostname)
        if (settings.mode == MODE_SMART) return lookupSmart(hostname).addresses

        val key = "${settings.mode}|${settings.endpoint}|${hostname.lowercase()}"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.first <= CACHE_MS }?.let { return it.second }

        val resolved = lookupUncached(hostname, settings)
        if (resolved.isNotEmpty()) cache[key] = now to resolved
        return resolved
    }

    private fun lookupSmart(hostname: String, useCache: Boolean = true): SmartLookupResult {
        if (isIpLiteral(hostname)) {
            return SmartLookupResult(listOf(InetAddress.getByName(hostname)), null)
        }

        val service = AiServiceResolver.serviceForHost(hostname)
        val network = DnsRouteStore.networkKey()
        val key = "smart|$network|$service|" + hostname.lowercase()
        val routeKey = activeRouteKey(network, hostname)
        val now = System.currentTimeMillis()

        if (useCache) {
            cache[key]?.takeIf { now - it.first <= CACHE_MS }?.let {
                val profile = activeRouteByHost[routeKey]?.let(DnsRegistryManager::profileById)
                return SmartLookupResult(it.second, profile)
            }
        }

        val candidates = DnsRegistryManager.candidatesFor(hostname, service)
        val stats = DnsRouteStore.statsFor(network, service)
        val ranked = DnsRouteScorer.rank(candidates, stats, now)
        val leased = DnsRouteStore.currentLease(network, service)
        val chosen = DnsRouteScorer.chooseWithHysteresis(ranked, leased, now)
        val ordered = buildList {
            chosen?.let { add(it) }
            ranked.filterNot { it.profile.id == chosen?.profile?.id }.forEach(::add)
        }

        var lastError: Exception? = null
        for (candidate in ordered) {
            val profile = candidate.profile
            val started = System.nanoTime()
            try {
                val addresses = lookupForProfile(profile, hostname)
                val latency = (System.nanoTime() - started) / 1_000_000L
                DnsRegistryManager.recordHealth(profile.id, true, latency)
                DnsRouteStore.record(
                    networkKey = network,
                    service = service,
                    profileId = profile.id,
                    success = true,
                    dnsMs = latency
                )
                if (addresses.isNotEmpty()) {
                    if (useCache) cache[key] = now to addresses
                    activeRouteByHost[routeKey] = profile.id
                    DnsRouteStore.setLease(
                        network, service, profile.id,
                        now + DnsRouteScorer.LEASE_MS
                    )
                    DnsHealthEngine.requestServiceCheck(service)
                    return SmartLookupResult(addresses, profile)
                }
            } catch (e: Exception) {
                val latency = (System.nanoTime() - started) / 1_000_000L
                DnsRegistryManager.recordHealth(profile.id, false, latency, e.message)
                DnsRouteStore.record(
                    networkKey = network,
                    service = service,
                    profileId = profile.id,
                    success = false,
                    dnsMs = latency,
                    error = e.message ?: e.javaClass.simpleName
                )
                if (leased?.profileId == profile.id) DnsRouteStore.clearLease(network, service)
                lastError = e
            }
        }

        return try {
            val fallback = Dns.SYSTEM.lookup(hostname)
            if (fallback.isEmpty()) throw UnknownHostException("System DNS не вернул адрес для " + hostname)
            if (useCache) cache[key] = now to fallback
            activeRouteByHost.remove(routeKey)
            DnsHealthEngine.requestServiceCheck(service)
            SmartLookupResult(fallback, null)
        } catch (e: Exception) {
            throw UnknownHostException(lastError?.message ?: e.message ?: "DNS lookup failed")
        }
    }

    fun lookupForProfile(profile: DnsProfile, hostname: String): List<InetAddress> {
        if (isIpLiteral(hostname)) return listOf(InetAddress.getByName(hostname))
        return when (profile.mode) {
            MODE_DOH -> resolveDoH(hostname, profile.endpoint, profile.bootstrapIps)
            MODE_DOT -> resolveDoT(hostname, profile.endpoint, profile.bootstrapIps)
            else -> lookupUncached(hostname, DnsSettings(profile.mode, profile.endpoint))
        }
    }

    private fun activeRouteKey(network: String, hostname: String) =
        "$network|" + hostname.trim().trimEnd('.').lowercase()

    private fun lookupUncached(hostname: String, settings: DnsSettings): List<InetAddress> {
        if (isIpLiteral(hostname)) return listOf(InetAddress.getByName(hostname))
        return when (settings.mode) {
            MODE_SYSTEM -> Dns.SYSTEM.lookup(hostname)
            MODE_DOH -> resolveDoH(hostname, settings.endpoint)
            MODE_DOT -> resolveDoT(hostname, settings.endpoint)
            else -> throw UnknownHostException("Неизвестный режим DNS")
        }
    }

    private fun resolveDoH(
        hostname: String,
        endpoint: String,
        bootstrapIps: List<String> = emptyList()
    ): List<InetAddress> {
        val all = mutableListOf<InetAddress>()
        val firstError = runCatching {
            all += resolveDoHType(hostname, endpoint, DNS_A, bootstrapIps)
        }.exceptionOrNull()
        runCatching { all += resolveDoHType(hostname, endpoint, DNS_AAAA, bootstrapIps) }
        if (all.isEmpty()) throw UnknownHostException(firstError?.message ?: "DoH не вернул адреса для $hostname")
        return all.distinctBy { it.hostAddress }
    }

    private fun resolveDoHType(
        hostname: String,
        endpoint: String,
        type: Int,
        bootstrapIps: List<String> = emptyList()
    ): List<InetAddress> {
        val query = buildQuery(hostname, type)
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/dns-message")
            .post(query.toRequestBody(dnsMediaType))
            .build()
        dohClient(endpoint, bootstrapIps).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UnknownHostException("DoH HTTP ${response.code}")
            val body = response.body?.bytes() ?: throw UnknownHostException("Пустой ответ DoH")
            if (body.size > 65_535) throw UnknownHostException("Слишком большой ответ DoH")
            return parseResponse(body, query, type)
        }
    }

    private fun dohClient(endpoint: String, bootstrapIps: List<String>): OkHttpClient {
        if (bootstrapIps.isEmpty()) return bootstrapHttp
        val endpointHost = endpoint.toHttpUrlOrNull()?.host ?: return bootstrapHttp
        val addresses = bootstrapIps.mapNotNull {
            runCatching { InetAddress.getByName(it) }.getOrNull()
        }
        if (addresses.isEmpty()) return bootstrapHttp
        val key = endpointHost + "|" + addresses.joinToString(",") { it.hostAddress ?: "" }
        return dohBootstrapClients.getOrPut(key) {
            OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(requested: String): List<InetAddress> =
                        if (requested.equals(endpointHost, ignoreCase = true)) addresses
                        else Dns.SYSTEM.lookup(requested)
                })
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }

    private fun resolveDoT(
        hostname: String,
        endpoint: String,
        bootstrapIps: List<String> = emptyList()
    ): List<InetAddress> {
        val uri = URI(endpoint)
        val resolverHost = uri.host ?: throw UnknownHostException("Некорректный DoT endpoint")
        val port = if (uri.port == -1) 853 else uri.port
        val all = mutableListOf<InetAddress>()
        val firstError = runCatching {
            all += resolveDoTType(hostname, resolverHost, port, DNS_A, bootstrapIps)
        }.exceptionOrNull()
        runCatching { all += resolveDoTType(hostname, resolverHost, port, DNS_AAAA, bootstrapIps) }
        if (all.isEmpty()) throw UnknownHostException(firstError?.message ?: "DoT не вернул адреса для $hostname")
        return all.distinctBy { it.hostAddress }
    }

    private fun resolveDoTType(
        hostname: String,
        resolverHost: String,
        port: Int,
        type: Int,
        bootstrapIps: List<String> = emptyList()
    ): List<InetAddress> {
        val bootstrapTargets = bootstrapIps.mapNotNull {
            runCatching { InetAddress.getByName(it) }.getOrNull()
        }
        val targets: List<InetSocketAddress> = if (bootstrapTargets.isNotEmpty()) {
            bootstrapTargets.map { InetSocketAddress(it, port) }
        } else {
            listOf(InetSocketAddress(resolverHost, port))
        }
        var lastError: Exception? = null
        for (target in targets) {
            try {
                val raw = java.net.Socket()
                raw.connect(target, 8_000)
                raw.soTimeout = 8_000
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val socket = factory.createSocket(raw, resolverHost, port, true) as SSLSocket
                socket.use { tls ->
                    val params = tls.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    params.serverNames = listOf(SNIHostName(resolverHost))
                    tls.sslParameters = params
                    tls.enabledProtocols = tls.enabledProtocols.filterNot {
                        it.equals("SSLv3", true) || it.equals("TLSv1", true) || it.equals("TLSv1.1", true)
                    }.toTypedArray()
                    tls.startHandshake()

                    val query = buildQuery(hostname, type)
                    val output = DataOutputStream(tls.outputStream)
                    output.writeShort(query.size)
                    output.write(query)
                    output.flush()

                    val input = DataInputStream(tls.inputStream)
                    val size = input.readUnsignedShort()
                    if (size !in 12..65_535) throw UnknownHostException("Некорректная длина ответа DoT")
                    val body = ByteArray(size)
                    input.readFully(body)
                    return parseResponse(body, query, type)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw UnknownHostException(lastError?.message ?: "DoT bootstrap недоступен")
    }

    private fun buildQuery(hostname: String, type: Int): ByteArray {
        val host = IDN.toASCII(hostname.trim().trimEnd('.'))
        if (host.isBlank()) throw UnknownHostException("Пустое имя")
        val out = java.io.ByteArrayOutputStream(512)
        val data = DataOutputStream(out)
        val id = random.nextInt(0x10000)
        data.writeShort(id)
        data.writeShort(0x0100) // recursion desired
        data.writeShort(1)
        data.writeShort(0)
        data.writeShort(0)
        data.writeShort(0)
        host.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) throw UnknownHostException("Некорректное DNS-имя")
            data.writeByte(bytes.size)
            data.write(bytes)
        }
        data.writeByte(0)
        data.writeShort(type)
        data.writeShort(DNS_IN)
        data.flush()
        return out.toByteArray()
    }

    private fun parseResponse(response: ByteArray, query: ByteArray, wantedType: Int): List<InetAddress> {
        if (response.size < 12 || query.size < 2) throw UnknownHostException("Повреждённый DNS-ответ")
        if (response[0] != query[0] || response[1] != query[1])
            throw UnknownHostException("DNS transaction ID не совпадает")
        val flags = u16(response, 2)
        if ((flags and 0x8000) == 0) throw UnknownHostException("DNS-пакет не является ответом")
        val rcode = flags and 0x000f
        if (rcode != 0) throw UnknownHostException("DNS ошибка RCODE=$rcode")
        val questions = u16(response, 4)
        val answers = u16(response, 6)
        var pos = 12
        repeat(questions) {
            pos = skipName(response, pos)
            if (pos + 4 > response.size) throw UnknownHostException("Повреждённый DNS question")
            pos += 4
        }
        val result = mutableListOf<InetAddress>()
        repeat(answers) {
            pos = skipName(response, pos)
            if (pos + 10 > response.size) throw UnknownHostException("Повреждённый DNS answer")
            val type = u16(response, pos)
            val clazz = u16(response, pos + 2)
            val length = u16(response, pos + 8)
            pos += 10
            if (pos + length > response.size) throw UnknownHostException("Повреждённый DNS RDATA")
            if (clazz == DNS_IN && type == wantedType) {
                if (type == DNS_A && length == 4) result += InetAddress.getByAddress(response.copyOfRange(pos, pos + 4))
                if (type == DNS_AAAA && length == 16) result += InetAddress.getByAddress(response.copyOfRange(pos, pos + 16))
            }
            pos += length
        }
        if (result.isEmpty()) throw UnknownHostException("DNS не вернул записи нужного типа")
        return result
    }

    private fun skipName(data: ByteArray, start: Int): Int {
        var pos = start
        var labels = 0
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xff
            if (len == 0) return pos + 1
            if ((len and 0xc0) == 0xc0) {
                if (pos + 1 >= data.size) throw UnknownHostException("Повреждённый DNS pointer")
                return pos + 2
            }
            if (len > 63 || pos + 1 + len > data.size) throw UnknownHostException("Повреждённое DNS имя")
            pos += 1 + len
            if (++labels > 127) throw UnknownHostException("Слишком длинное DNS имя")
        }
        throw UnknownHostException("Обрезанное DNS имя")
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) throw UnknownHostException("Обрезанный DNS packet")
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun isIpLiteral(value: String): Boolean {
        val s = value.trim()
        return s.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")) || s.contains(':')
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        if (address is Inet4Address) {
            val b = address.address.map { it.toInt() and 255 }
            if (b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
                b[0] == 169 && b[1] == 254 || b[0] == 172 && b[1] in 16..31 ||
                b[0] == 192 && b[1] == 168 || b[0] == 100 && b[1] in 64..127 ||
                b[0] == 192 && b[1] == 0 || b[0] == 198 && b[1] in 18..19 ||
                b[0] == 198 && b[1] == 51 && b[2] == 100 || b[0] == 203 && b[1] == 0 && b[2] == 113) return false
        }
        if (address is Inet6Address) {
            val b = address.address
            if ((b[0].toInt() and 0xfe) == 0xfc) return false
            if ((b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x01 &&
                (b[2].toInt() and 0xff) == 0x0d && (b[3].toInt() and 0xff) == 0xb8) return false
            if ((b[0].toInt() and 0xe0) != 0x20) return false
        }
        return true
    }
}

