package app.githubble.mobile.network

/** A transport-independent SSE frame decoder (data may span multiple lines). */
class SseDecoder {
    private val data = ArrayList<String>()
    fun feed(line: String): String? {
        if (line.isEmpty()) {
            if (data.isEmpty()) return null
            val event = data.joinToString("\n")
            data.clear()
            return event
        }
        if (line.startsWith(":")) return null
        if (line.startsWith("data:") && line.length >= 5) {
            data += line.substring(5).removePrefix(" ")
        }
        return null
    }
    fun flush(): String? = feed("")
}

