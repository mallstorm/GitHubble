package app.githubble.mobile.mcp

import org.json.JSONObject

/** Decode bounded, fully received MCP SSE data events, selecting the matching JSON-RPC response. */
object McpWire {
    fun responseFromSse(raw: String, requestId: Int): JSONObject? {
        for (event in raw.replace("\r\n", "\n").split("\n\n")) {
            val lines = event.lineSequence().filter { it.startsWith("data:") }
            val payload = lines.joinToString("\n") { it.removePrefix("data:").trimStart() }
            if (payload.isBlank()) continue
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            if (json.optInt("id", -2) == requestId) return json
        }
        return null
    }
}

