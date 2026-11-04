package app.githubble.mobile.mcp

import app.githubble.mobile.network.ApiFailure
import app.githubble.mobile.network.NetworkDnsPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Streamable HTTP MCP tool client. Supports 2025-11-25 sessions and 2026-07-28 stateless calls. */
class McpClient(private val config: McpServerConfig) {
    private val url = config.url.toHttpUrlOrNull() ?: throw ApiFailure("Неверный MCP URL")
    init {
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null)
            throw ApiFailure("MCP сервер должен использовать HTTPS без учётных данных в URL")
    }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .dns(NetworkDnsPolicy.dns(publicOnly = true))
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()
    private val sequence = AtomicInteger(1)
    private var session: String? = null
    private val stateless get() = config.version == "2026-07-28"
    private val clientMeta get() = JSONObject()
        .put("io.modelcontextprotocol/protocolVersion", config.version)
        .put("io.modelcontextprotocol/clientInfo", JSONObject().put("name", "OmniChat").put("version", "0.4.0"))
        .put("io.modelcontextprotocol/clientCapabilities", JSONObject())

    suspend fun tools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        if (!stateless) {
            val result = rpc("initialize", JSONObject()
                .put("protocolVersion", "2025-11-25")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "OmniChat").put("version", "0.4.0")))
            val selected = result.optString("protocolVersion")
            if (selected != "2025-11-25") throw ApiFailure("MCP сервер вернул неподдерживаемую версию $selected")
            post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), "notifications/initialized", null, expectResponse = false)
        }
        val tools = mutableListOf<McpToolInfo>()
        val visited = mutableSetOf<String>()
        var cursor: String? = null
        repeat(10) {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val result = rpc("tools/list", params)
            val items = result.optJSONArray("tools") ?: throw ApiFailure("MCP tools/list: нет массива tools")
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val name = entry.optString("name")
                if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,79}"))) continue
                val schema = entry.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
                if (schema.toString().length > 12_000) continue
                if (tools.none { existing -> existing.name == name } && tools.size < 60)
                    tools += McpToolInfo(name, entry.optString("description").take(600), schema)
            }
            val next = result.optString("nextCursor").takeIf { it.isNotBlank() }
            if (next == null) return@withContext tools
            if (!visited.add(next)) throw ApiFailure("MCP: сервер зациклил курсор списка инструментов")
            cursor = next
        }
        throw ApiFailure("MCP: слишком много страниц инструментов")
    }

    suspend fun invoke(tool: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        val result = rpc("tools/call", JSONObject().put("name", tool).put("arguments", args))
        val blocks = result.optJSONArray("content") ?: JSONArray()
        val output = (0 until blocks.length()).mapNotNull { index ->
            val block = blocks.optJSONObject(index) ?: return@mapNotNull null
            when (block.optString("type")) {
                "text" -> block.optString("text")
                "image", "audio" -> "[MCP вернул мультимедийный блок: не передан модели; просмотрите в отдельном интерфейсе сервера]"
                else -> null
            }
        }.joinToString("\n").take(45_000)
        val structured = result.optJSONObject("structuredContent")?.toString()?.take(25_000).orEmpty()
        val rendered = listOf(output, structured).filter { it.isNotBlank() }.joinToString("\n")
        if (result.optBoolean("isError", false)) "Ошибка MCP-инструмента: $rendered" else rendered.ifBlank { "Инструмент выполнен без текстового результата" }
    }

    private fun rpc(method: String, params: JSONObject): JSONObject {
        if (stateless) params.put("_meta", clientMeta)
        val id = sequence.getAndIncrement()
        val envelope = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("method", method).put("params", params)
        val response = post(envelope, method, if (method == "tools/call") params.optString("name") else null)
        if (response.optInt("id", -1) != id) throw ApiFailure("MCP ответ не соответствует запросу")
        val error = response.optJSONObject("error")
        if (error != null) throw ApiFailure("MCP ${error.optInt("code")}: ${error.optString("message").take(250)}")
        return response.optJSONObject("result") ?: throw ApiFailure("MCP сервер вернул некорректный ответ")
    }

    private fun post(payload: JSONObject, method: String, name: String?, expectResponse: Boolean = true): JSONObject {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", config.version)
            .apply {
                if (stateless) {
                    header("Mcp-Method", method)
                    if (name != null) header("Mcp-Name", name)
                } else session?.let { header("Mcp-Session-Id", it) }
                if (config.bearerToken.isNotBlank()) header("Authorization", "Bearer ${config.bearerToken}")
            }.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(req).execute().use { response ->
            if (response.code !in 200..299) throw ApiFailure("MCP HTTP ${response.code} (${response.message.take(120)})")
            if (!stateless) response.header("Mcp-Session-Id")?.let { session = it.take(256) }
            if (!expectResponse) return JSONObject()
            val body = response.body ?: throw ApiFailure("MCP: пустой ответ")
            val bytes = body.byteStream().readBounded(512_001)
            if (bytes.size > 512_000) throw ApiFailure("MCP ответ больше 512 КБ")
            val raw = String(bytes, Charsets.UTF_8)
            val isSse = response.header("Content-Type").orEmpty().contains("text/event-stream")
            if (!isSse) return JSONObject(raw)
            return McpWire.responseFromSse(raw, payload.optInt("id", -1))
                ?: throw ApiFailure("MCP SSE не содержит окончательного ответа на запрос")
        }
    }
}

data class McpServerConfig(
    val id: String, val name: String, val url: String, val bearerToken: String = "",
    val version: String = "2025-11-25", val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)
        .put("bearerToken", bearerToken).put("version", version).put("enabled", enabled)
    companion object {
        fun fromJson(json: JSONObject) = McpServerConfig(
            json.getString("id"), json.getString("name"), json.getString("url"),
            json.optString("bearerToken"), json.optString("version", "2025-11-25"), json.optBoolean("enabled", true))
    }
}

data class McpToolInfo(val name: String, val description: String, val schema: JSONObject)

/** A deliberately conservative address policy: MCP credentials may only reach public HTTPS endpoints. */
private fun publicIp(address: InetAddress): Boolean {
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
        if ((b[0].toInt() and 0xfe) == 0xfc || (b[0].toInt() and 0xff) == 0x20 && (b[1].toInt() and 0xff) == 0x01 &&
            (b[2].toInt() and 0xff) == 0x0d && (b[3].toInt() and 0xff) == 0xb8) return false
        if ((b[0].toInt() and 0xe0) != 0x20) return false
    }
    return true
}

private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

