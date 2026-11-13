package app.githubble.mobile.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Positive, in-memory evidence only; no catalog flags, user content or raw secrets are persisted. */
internal class AgentProtocolNegotiator(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    fun interface Probe { suspend fun turn(protocol: String, prompt: String, items: JSONArray, tools: JSONArray): ApiGateway.NativeAgentTurn }
    private data class Evidence(val protocol: String, val at: Long)
    private val mutex = Mutex()
    private val evidence = linkedMapOf<String, Evidence>()

    suspend fun cached(identity: String): String? = mutex.withLock {
        evidence[identity]?.takeIf { now() - it.at < 30 * 60_000 }?.protocol
    }
    suspend fun invalidate(identity: String) { mutex.withLock { evidence.remove(identity) } }

    suspend fun resolve(identity: String, candidates: List<String>, probe: Probe): String = mutex.withLock {
        evidence[identity]?.takeIf { now() - it.at < 30 * 60_000 && it.protocol in candidates }?.let { return@withLock it.protocol }
        for (protocol in candidates) {
            try {
                withTimeout(60_000) { verify(protocol, probe) }
                if (evidence.size >= 64) evidence.remove(evidence.keys.first())
                evidence[identity] = Evidence(protocol, now())
                return@withLock protocol
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw ApiFailure("Проверка потоковой генерации и инструментов API не завершилась за 60 секунд. Повторите попытку позже.")
            } catch (_: AgentCapabilityUnconfirmed) {
                // Valid HTTP does not guarantee either streaming or function calling.
            } catch (e: ApiFailure) {
                // Authentication, rate limits, outages and ambiguous transport failures are not evidence of an unsupported protocol.
                if (e.statusCode !in setOf(400, 404, 405, 415, 422, 501)) throw e
            }
        }
        throw ApiFailure("Для модели не подтверждены потоковая генерация и нативные вызовы инструментов ни через Responses API, ни через Chat Completions. Проверьте выбранную модель и подключение.")
    }

    private suspend fun verify(protocol: String, probe: Probe) {
        val nonce = UUID.randomUUID().toString()
        val tools = JSONArray().put(JSONObject().put("type", "function").put("strict", false).put("name", "hubble_capability_probe")
            .put("description", "Return the supplied nonce; this is a harmless capability test, with no file or repository access.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("nonce", JSONObject().put("type", "string"))).put("required", JSONArray().put("nonce")).put("additionalProperties", false)))
        val first = probe.turn(protocol, "Call hubble_capability_probe exactly once with nonce $nonce. Do not answer in text yet.", JSONArray(), tools)
        val call = first.calls.singleOrNull() ?: throw AgentCapabilityUnconfirmed("API не подтвердил вызов тестового инструмента")
        if (call.name != "hubble_capability_probe" || call.arguments.optString("nonce") != nonce) throw AgentCapabilityUnconfirmed("API не подтвердил аргументы тестового инструмента")
        val result = "verified-${UUID.randomUUID()}"
        val items = JSONArray()
        appendAssistant(protocol, items, first)
        items.put(toolResult(protocol, call.id, result))
        val final = probe.turn(protocol, "Return exactly the tool result as plain text, without calling another tool.", items, tools)
        if (final.calls.isNotEmpty() || !final.content.contains(result)) throw AgentCapabilityUnconfirmed("API не подтвердил обработку результата инструмента")
    }

    companion object {
        fun fingerprint(vararg fields: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fields.forEach { value -> val bytes = value.toByteArray(Charsets.UTF_8); digest.update("${bytes.size}:".toByteArray()); digest.update(bytes) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun appendAssistant(protocol: String, items: JSONArray, turn: ApiGateway.NativeAgentTurn) {
            if (protocol == ApiGateway.RESPONSES) {
                val output = turn.assistantMessage.getJSONArray("items")
                for (i in 0 until output.length()) items.put(output.get(i))
            } else items.put(turn.assistantMessage)
        }
        fun toolResult(protocol: String, id: String, output: String): JSONObject = if (protocol == ApiGateway.RESPONSES)
            JSONObject().put("type", "function_call_output").put("call_id", id).put("output", output)
        else JSONObject().put("role", "tool").put("tool_call_id", id).put("content", output)
    }
}
