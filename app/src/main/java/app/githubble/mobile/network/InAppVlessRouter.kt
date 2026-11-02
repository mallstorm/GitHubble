package app.githubble.mobile.network

import android.content.Context
import android.net.Uri
import app.githubble.mobile.security.KeyVault
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-local Xray/VLESS routing for the whole OmniChat process.
 *
 * Xray exposes both loopback SOCKS and HTTP proxy listeners. A process-wide ProxySelector plus
 * JVM HTTP/SOCKS proxy properties make regular OkHttp/URLConnection/WebSocket traffic from every
 * OmniChat provider use the active Xray route. Android VPN APIs are intentionally not used, so
 * traffic from other applications is not affected.
 */
data class VlessProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uri: String,
    val enabled: Boolean = true
)

data class VlessRouteStatus(
    val enabled: Boolean = false,
    val activeProfileId: String? = null,
    val localPort: Int? = null,
    val httpPort: Int? = null,
    val error: String? = null
)

object InAppVlessRouter {
    private const val PREFS = "in_app_vless_router_v1"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active"
    private const val KEY_ENABLED = "enabled"

    private val lock = Any()
    // Avoid AndroidKeyStore initialization during JVM-only network unit tests.
    private val vault by lazy { KeyVault() }
    private val statusRef = AtomicReference(VlessRouteStatus())
    @Volatile private var appContext: Context? = null
    @Volatile private var installedSelector = false
    @Volatile private var systemSelector: ProxySelector? = null
    @Volatile private var capturedProxyProperties = false

    private val proxyPropertyKeys = listOf(
        "socksProxyHost",
        "socksProxyPort",
        "http.proxyHost",
        "http.proxyPort",
        "https.proxyHost",
        "https.proxyPort",
        "http.nonProxyHosts"
    )
    private val inheritedProxyProperties = linkedMapOf<String, String?>()

    private val routingSelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val state = statusRef.get()
            val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
            if (!state.enabled || isLocal(host)) {
                return systemSelector?.select(uri).orEmpty().ifEmpty { listOf(Proxy.NO_PROXY) }
            }
            // While a VLESS profile is active there is deliberately no DIRECT fallback.
            return listOf(
                Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress("127.0.0.1", state.localPort ?: 0)
                )
            )
        }

        override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
            if (statusRef.get().enabled) {
                reportFailure(ioe)
            } else {
                runCatching { systemSelector?.connectFailed(uri, sa, ioe) }
            }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        captureProcessProxyProperties()
        installSelector()
        synchronized(lock) { restoreRunningRoute() }
    }

    fun status(): VlessRouteStatus = statusRef.get()

    /** Stable selector for transports that want to bind explicitly instead of using the JVM default. */
    fun proxySelector(): ProxySelector = routingSelector

    fun profiles(): List<VlessProfile> = synchronized(lock) { readProfiles() }

    fun upsert(profile: VlessProfile) {
        require(profile.name.isNotBlank()) { "Укажите имя VLESS-профиля" }
        validateProfile(profile.uri)
        synchronized(lock) {
            val items = readProfiles().toMutableList()
            val index = items.indexOfFirst { it.id == profile.id }
            if (index >= 0) items[index] = profile else items += profile
            writeProfiles(items)
        }
    }

    fun remove(profileId: String) {
        synchronized(lock) {
            val active = activeId()
            writeProfiles(readProfiles().filterNot { it.id == profileId })
            if (active == profileId) disable()
        }
    }

    fun enable(profileId: String) {
        synchronized(lock) {
            val selected = readProfiles().firstOrNull { it.id == profileId && it.enabled }
                ?: error("VLESS-профиль не найден или отключён")
            prefs().edit().putBoolean(KEY_ENABLED, true).putString(KEY_ACTIVE, selected.id).apply()
            try { start(selected) } catch(t: Throwable) {
                statusRef.set(VlessRouteStatus(enabled=true,activeProfileId=selected.id,error=t.message ?: "Xray unavailable"))
                throw t
            }
        }
    }

    fun disable() {
        synchronized(lock) {
            statusRef.set(VlessRouteStatus())
            restoreProcessProxyProperties()
            XrayBridge.stop()
            prefs().edit().putBoolean(KEY_ENABLED, false).remove(KEY_ACTIVE).apply()
            installSelector()
        }
    }

    /** Called by a network layer after transport failures to try the next enabled profile. */
    fun reportFailure(cause: Throwable?) {
        val current = statusRef.get()
        val active = current.activeProfileId ?: return
        synchronized(lock) {
            val alternatives = readProfiles().filter { it.enabled && it.id != active }
            val next = alternatives.firstOrNull() ?: return
            runCatching { start(next) }.onSuccess {
                prefs().edit().putBoolean(KEY_ENABLED, true).putString(KEY_ACTIVE, next.id).apply()
            }.onFailure {
                statusRef.set(VlessRouteStatus(enabled=true,activeProfileId=active,error = it.message ?: cause?.message ?: "VLESS unavailable"))
                restoreProcessProxyProperties()
                prefs().edit().putBoolean(KEY_ENABLED, true).putString(KEY_ACTIVE,active).apply()
            }
        }
    }

    private fun restoreRunningRoute() {
        val prefs = prefs()
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val id = prefs.getString(KEY_ACTIVE, null) ?: return
        val profile = readProfiles().firstOrNull { it.id == id && it.enabled } ?: return
        runCatching { start(profile) }.onFailure {
            statusRef.set(VlessRouteStatus(enabled=true,activeProfileId=id,error = it.message ?: "Не удалось запустить VLESS"))
            restoreProcessProxyProperties()
        }
    }

    private fun start(profile: VlessProfile) {
        validateProfile(profile.uri)

        // Keep requests blocked until the new listeners have been verified.
        statusRef.set(VlessRouteStatus(enabled=true,activeProfileId=profile.id))
        restoreProcessProxyProperties()
        XrayBridge.stop()

        val socksPort = freePort()
        var httpPort = freePort()
        while (httpPort == socksPort) httpPort = freePort()

        XrayBridge.run(if(profile.uri.trim().startsWith("{")) importedConfig(profile.uri,socksPort,httpPort) else xrayConfig(parse(profile.uri), socksPort, httpPort))
        try {
            waitForPort(socksPort, "SOCKS")
            waitForPort(httpPort, "HTTP")
        } catch (t: Throwable) {
            XrayBridge.stop()
            throw t
        }

        val state = VlessRouteStatus(
            enabled = true,
            activeProfileId = profile.id,
            localPort = socksPort,
            httpPort = httpPort
        )
        statusRef.set(state)
        installSelector()
        applyProcessProxyProperties(state)
    }

    fun validateProfile(raw: String) {
        require(raw.length <= 512 * 1024) { "Профиль превышает 512 КБ" }
        if (raw.trim().startsWith("{")) {
            val j=JSONObject(raw)
            require((j.optJSONArray("outbounds")?.length() ?: 0) > 0) { "В JSON нет outbounds" }
        } else parse(raw)
    }

    private fun importedConfig(raw: String,socksPort: Int,httpPort: Int): String {
        val j=JSONObject(raw)
        j.put("inbounds",JSONArray()
            .put(JSONObject().put("listen","127.0.0.1").put("port",socksPort).put("protocol","socks")
                .put("settings",JSONObject().put("auth","noauth").put("udp",true)))
            .put(JSONObject().put("listen","127.0.0.1").put("port",httpPort).put("protocol","http").put("settings",JSONObject())))
        j.put("log",JSONObject().put("loglevel","warning"))
        return j.toString()
    }

    private fun xrayConfig(profile: ParsedVless, socksPort: Int, httpPort: Int): String {
        val user = JSONObject()
            .put("id", profile.uuid)
            .put("encryption", profile.encryption.ifBlank { "none" })
        profile.flow?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }

        val server = JSONObject()
            .put("address", profile.host)
            .put("port", profile.port)
            .put("users", JSONArray().put(user))

        val outbound = JSONObject()
            .put("tag", "vless-out")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(server)))
            .put("streamSettings", streamSettings(profile))

        val inbounds = JSONArray()
            .put(
                JSONObject()
                    .put("tag", "omnichat-socks")
                    .put("listen", "127.0.0.1")
                    .put("port", socksPort)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
            )
            .put(
                JSONObject()
                    .put("tag", "omnichat-http")
                    .put("listen", "127.0.0.1")
                    .put("port", httpPort)
                    .put("protocol", "http")
                    .put("settings", JSONObject())
            )

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", JSONArray().put(outbound))
            .put("routing", JSONObject().put("domainStrategy", "AsIs"))
            .toString()
    }

    private fun streamSettings(profile: ParsedVless): JSONObject {
        val network = profile.type.ifBlank { "tcp" }.lowercase()
        return JSONObject().put("network", network).apply {
            when (network) {
                "ws" -> put(
                    "wsSettings",
                    JSONObject().apply {
                        put("path", profile.path.ifBlank { "/" })
                        profile.hostHeader?.takeIf { it.isNotBlank() }?.let {
                            put("headers", JSONObject().put("Host", it))
                        }
                    }
                )
                "grpc" -> put("grpcSettings", JSONObject().put("serviceName", profile.serviceName.orEmpty()))
                "httpupgrade" -> put(
                    "httpupgradeSettings",
                    JSONObject().apply {
                        put("path", profile.path.ifBlank { "/" })
                        profile.hostHeader?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                    }
                )
                "xhttp" -> put(
                    "xhttpSettings",
                    JSONObject().apply {
                        put("path", profile.path.ifBlank { "/" })
                        profile.hostHeader?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                    }
                )
            }
            when (profile.security.lowercase()) {
                "tls" -> {
                    put("security", "tls")
                    put(
                        "tlsSettings",
                        JSONObject().apply {
                            put("serverName", profile.sni ?: profile.host)
                            profile.fingerprint?.let { put("fingerprint", it) }
                            put("allowInsecure", false)
                        }
                    )
                }
                "reality" -> {
                    put("security", "reality")
                    put(
                        "realitySettings",
                        JSONObject().apply {
                            put("serverName", profile.sni ?: profile.host)
                            profile.publicKey?.let { put("publicKey", it) }
                            profile.shortId?.let { put("shortId", it) }
                            profile.fingerprint?.let { put("fingerprint", it) }
                            put("spiderX", profile.spiderX.orEmpty())
                        }
                    )
                }
                else -> put("security", "none")
            }
        }
    }

    private fun installSelector() {
        synchronized(lock) {
            val current = ProxySelector.getDefault()
            if (!installedSelector) {
                systemSelector = current
                installedSelector = true
            }
            // Self-heal if another component replaced the JVM default selector after app startup.
            if (current !== routingSelector) ProxySelector.setDefault(routingSelector)
        }
    }

    private fun captureProcessProxyProperties() {
        if (capturedProxyProperties) return
        synchronized(lock) {
            if (capturedProxyProperties) return
            proxyPropertyKeys.forEach { inheritedProxyProperties[it] = System.getProperty(it) }
            capturedProxyProperties = true
        }
    }

    private fun applyProcessProxyProperties(state: VlessRouteStatus) {
        val socksPort = state.localPort ?: return
        val httpPort = state.httpPort ?: return
        captureProcessProxyProperties()

        System.setProperty("socksProxyHost", "127.0.0.1")
        System.setProperty("socksProxyPort", socksPort.toString())
        System.setProperty("http.proxyHost", "127.0.0.1")
        System.setProperty("http.proxyPort", httpPort.toString())
        System.setProperty("https.proxyHost", "127.0.0.1")
        System.setProperty("https.proxyPort", httpPort.toString())

        val inheritedNoProxy = inheritedProxyProperties["http.nonProxyHosts"].orEmpty().trim()
        val localNoProxy = "localhost|127.*|[::1]"
        System.setProperty(
            "http.nonProxyHosts",
            if (inheritedNoProxy.isBlank()) localNoProxy else "$inheritedNoProxy|$localNoProxy"
        )
    }

    private fun restoreProcessProxyProperties() {
        if (!capturedProxyProperties) return
        proxyPropertyKeys.forEach { key ->
            val original = inheritedProxyProperties[key]
            if (original == null) System.clearProperty(key) else System.setProperty(key, original)
        }
    }

    private fun isLocal(host: String): Boolean =
        host.equals("localhost", true) || host == "127.0.0.1" || host == "::1"

    private fun waitForPort(port: Int, label: String) {
        var last: Throwable? = null
        repeat(20) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                }
                return
            } catch (t: Throwable) {
                last = t
                Thread.sleep(80)
            }
        }
        throw IllegalStateException("Xray $label-порт не открылся", last)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun activeId(): String? = prefs().getString(KEY_ACTIVE, null)

    private fun prefs() = requireNotNull(appContext) { "VLESS router is not initialized" }
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readProfiles(): List<VlessProfile> {
        val encrypted = prefs().getString(KEY_PROFILES, "").orEmpty()
        if (encrypted.isBlank()) return emptyList()
        val raw = runCatching { vault.decrypt(encrypted) }.getOrDefault("[]")
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val uri = item.optString("uri")
                if (uri.isBlank()) continue
                add(
                    VlessProfile(
                        item.optString("id"),
                        item.optString("name", "VLESS"),
                        uri,
                        item.optBoolean("enabled", true)
                    )
                )
            }
        }
    }

    private fun writeProfiles(profiles: List<VlessProfile>) {
        val json = JSONArray()
        profiles.forEach { profile ->
            json.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("uri", profile.uri)
                    .put("enabled", profile.enabled)
            )
        }
        prefs().edit().putString(KEY_PROFILES, vault.encrypt(json.toString())).apply()
    }

    private data class ParsedVless(
        val uuid: String,
        val host: String,
        val port: Int,
        val encryption: String,
        val security: String,
        val type: String,
        val sni: String?,
        val flow: String?,
        val fingerprint: String?,
        val publicKey: String?,
        val shortId: String?,
        val path: String,
        val hostHeader: String?,
        val serviceName: String?,
        val spiderX: String?
    )

    private fun parse(link: String): ParsedVless {
        val uri = Uri.parse(link.trim())
        require(uri.scheme.equals("vless", true)) { "Нужна ссылка формата vless://…" }
        val userInfo = uri.userInfo ?: error("В VLESS-ссылке отсутствует UUID")
        require(runCatching { UUID.fromString(userInfo) }.isSuccess) { "Некорректный UUID VLESS" }
        val host = uri.host ?: error("В VLESS-ссылке отсутствует сервер")
        val port = uri.port.takeIf { it in 1..65535 } ?: error("Некорректный порт VLESS")
        fun q(name: String) = uri.getQueryParameter(name)?.trim()?.takeIf { it.isNotBlank() }
        return ParsedVless(
            uuid = userInfo,
            host = host,
            port = port,
            encryption = q("encryption") ?: "none",
            security = q("security") ?: "none",
            type = q("type") ?: "tcp",
            sni = q("sni") ?: q("serverName"),
            flow = q("flow"),
            fingerprint = q("fp"),
            publicKey = q("pbk"),
            shortId = q("sid"),
            path = q("path").orEmpty(),
            hostHeader = q("host"),
            serviceName = q("serviceName"),
            spiderX = q("spx")
        )
    }

    /** Reflection keeps source independent from a particular gomobile package namespace. */
    private object XrayBridge {
        private val classNames = listOf(
            "libxray.LibXray",
            "libXray.LibXray",
            "com.xtls.libxray.LibXray"
        )

        fun run(config: String) {
            val type = runtimeClass()
            val structured = type.methods.firstOrNull {
                it.name.equals("invoke", true) &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            if (structured != null) {
                val request = JSONObject()
                    .put("apiVersion", 3)
                    .put("method", "runXray")
                    .put("payload", JSONObject().put("xrayJson", config))
                    .toString()
                checkResponse(structured.invoke(null, request)?.toString().orEmpty())
                return
            }

            val request = invoke(type, "newXrayRunFromJSONRequest", "", config)
            checkResponse(invoke(type, "runXrayFromJSON", request))
        }

        fun stop() {
            runCatching {
                val type = runtimeClass()
                val structured = type.methods.firstOrNull {
                    it.name.equals("invoke", true) &&
                        it.parameterTypes.contentEquals(arrayOf(String::class.java))
                }
                if (structured != null) {
                    val request = JSONObject()
                        .put("apiVersion", 3)
                        .put("method", "stopXray")
                        .put("payload", JSONObject())
                        .toString()
                    checkResponse(structured.invoke(null, request)?.toString().orEmpty())
                } else {
                    checkResponse(invoke(type, "stopXray"))
                }
            }
        }

        private fun runtimeClass(): Class<*> =
            classNames.firstNotNullOfOrNull { runCatching { Class.forName(it) }.getOrNull() }
                ?: error("Xray runtime не загружен")

        private fun invoke(type: Class<*>, name: String, vararg arguments: String): String {
            val method = type.methods.firstOrNull {
                it.name.equals(name, true) &&
                    it.parameterTypes.size == arguments.size &&
                    it.parameterTypes.all { parameter -> parameter == String::class.java }
            } ?: error("Xray runtime не содержит $name")
            return method.invoke(null, *arguments)?.toString().orEmpty()
        }

        private fun checkResponse(response: String) {
            val json = runCatching { JSONObject(response) }.getOrNull() ?: return
            if (!json.optBoolean("success", true)) {
                error(json.optString("error", json.optString("message", "Xray не принял конфигурацию")))
            }
        }
    }
}

