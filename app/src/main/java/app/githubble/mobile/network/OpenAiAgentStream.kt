package app.githubble.mobile.network

import org.json.JSONArray
import org.json.JSONObject

/** Parses a complete native tool turn. A 200 response or an ordinary text answer proves no tools. */
internal class AgentCapabilityUnconfirmed(message: String) : java.io.IOException(message)

internal class OpenAiAgentStream(private val protocol: String) {
    private data class Fragments(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())
    private val fragments = sortedMapOf<Int, Fragments>()
    private val items = linkedMapOf<String, JSONObject>()
    private var completed: JSONObject? = null
    private var chatFinished = false
    private var bytes = 0
    val text = StringBuilder()
    var done = false
        private set
    private var usage: JSONObject? = null

    fun accept(payload: String) {
        bytes += payload.length
        if (bytes > 2 * 1024 * 1024) throw AgentCapabilityUnconfirmed("Агентный ответ превышает допустимый размер")
        if (payload == "[DONE]") {
            if (protocol == ApiGateway.CHAT && chatFinished) done = true
            return
        }
        val event = try { JSONObject(payload) } catch (_: Exception) { throw AgentCapabilityUnconfirmed("Некорректное событие потока API") }
        if (event.has("error") && !event.isNull("error")) throw ApiFailure("API вернул ошибку в агентном потоке")
        if (protocol == ApiGateway.CHAT) {
            event.optJSONObject("usage")?.let { usage = it }
            val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: return
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                if (!delta.isNull("content")) text.append(delta.optString("content", ""))
                val calls = delta.optJSONArray("tool_calls") ?: JSONArray()
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val index = call.optInt("index", -1)
                    if (index !in 0..95) throw AgentCapabilityUnconfirmed("Некорректный индекс вызова инструмента")
                    val part = fragments.getOrPut(index) { Fragments() }
                    if (!call.isNull("id")) part.id.append(call.optString("id"))
                    call.optJSONObject("function")?.let { fn ->
                        if (!fn.isNull("name")) part.name.append(fn.optString("name"))
                        if (!fn.isNull("arguments")) part.args.append(fn.optString("arguments"))
                    }
                }
            }
            if (!choice.isNull("finish_reason")) {
                val reason = choice.optString("finish_reason")
                if (reason !in setOf("stop", "tool_calls")) throw AgentCapabilityUnconfirmed("Агентный ответ не завершён: $reason")
                if (fragments.isNotEmpty() && reason != "tool_calls") throw AgentCapabilityUnconfirmed("API не подтвердил завершение вызова инструмента")
                chatFinished = true
            }
        } else {
            when (event.optString("type")) {
                "response.output_text.delta" -> text.append(event.optString("delta", ""))
                "response.output_item.done" -> event.optJSONObject("item")?.let { items[it.optString("id", "item-${items.size}")] = it }
                "response.completed" -> {
                    completed = event.optJSONObject("response") ?: throw AgentCapabilityUnconfirmed("Responses API не вернул завершённый ответ")
                    if (completed!!.optString("status", "completed") != "completed") throw AgentCapabilityUnconfirmed("Responses API не завершил ответ")
                    usage = completed!!.optJSONObject("usage")
                    done = true
                }
                "response.failed", "response.incomplete", "error" -> throw ApiFailure("Responses API не завершил агентный ответ")
            }
        }
    }

    fun result(): ApiGateway.NativeAgentTurn {
        if (!done) throw AgentCapabilityUnconfirmed("Соединение API оборвалось до окончания агентного ответа")
        val calls = mutableListOf<ApiGateway.NativeToolCall>()
        val assistant: JSONObject
        if (protocol == ApiGateway.CHAT) {
            val array = JSONArray()
            fragments.values.forEach { f ->
                val call = parseCall(f.id.toString(), f.name.toString(), f.args.toString())
                calls += call
                array.put(JSONObject().put("id", call.id).put("type", "function").put("function", JSONObject().put("name", call.name).put("arguments", call.arguments.toString())))
            }
            assistant = JSONObject().put("role", "assistant").put("content", text.toString())
            if (array.length() > 0) assistant.put("tool_calls", array)
        } else {
            val output = completed?.optJSONArray("output") ?: JSONArray(items.values.toList())
            val finalText = StringBuilder()
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                if (item.optString("type") == "function_call") calls += parseCall(item.optString("call_id"), item.optString("name"), item.optString("arguments"))
                if (item.optString("type") == "message") {
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (j in 0 until content.length()) if (content.getJSONObject(j).optString("type") == "output_text") finalText.append(content.getJSONObject(j).optString("text"))
                }
            }
            if (finalText.isNotEmpty()) { text.clear(); text.append(finalText) }
            assistant = JSONObject().put("type", "response_items").put("items", output)
        }
        if (calls.map { it.id }.toSet().size != calls.size) throw AgentCapabilityUnconfirmed("API вернул повторяющиеся идентификаторы инструментов")
        return ApiGateway.NativeAgentTurn(text.toString(), calls, UsageSnapshot(
            usage?.let { if (it.has("input_tokens")) it.optLong("input_tokens") else if (it.has("prompt_tokens")) it.optLong("prompt_tokens") else null },
            usage?.let { if (it.has("output_tokens")) it.optLong("output_tokens") else if (it.has("completion_tokens")) it.optLong("completion_tokens") else null }
        ), assistant)
    }

    private fun parseCall(id: String, name: String, raw: String): ApiGateway.NativeToolCall {
        if (id.isBlank() || name.isBlank() || raw.isBlank()) throw AgentCapabilityUnconfirmed("API вернул неполный вызов инструмента")
        val args = try { JSONObject(raw) } catch (_: Exception) { throw AgentCapabilityUnconfirmed("API вернул некорректные аргументы инструмента") }
        return ApiGateway.NativeToolCall(id, name, args)
    }
}
