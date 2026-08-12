package app.githubble.mobile.core.translation

class TechnicalTokenProtector {
    data class Protected(val text: String, val tokens: Map<String, String>)

    private val patterns = listOf(
        Regex("```[\\s\\S]*?```"),
        Regex("`[^`\\n]+`"),
        Regex("https?://[^\\s)]+"),
        Regex("(?<!\\w)(?:/|[A-Za-z]:\\\\)[^\\s`]+"),
        Regex("\\b[0-9a-f]{7,40}\\b"),
        Regex("\\b(?:npm|pnpm|yarn|gradle|git|adb|fastboot|docker|curl)\\s+[^\\n]+")
    )

    fun protect(input: String): Protected {
        var output = input
        val tokens = linkedMapOf<String, String>()
        var index = 0
        patterns.forEach { regex ->
            output = regex.replace(output) { match ->
                val key = "__GH_TECH_${index++}_${match.value.hashCode().toUInt()}__"
                tokens[key] = match.value
                key
            }
        }
        return Protected(output, tokens)
    }

    fun restore(input: String, tokens: Map<String, String>): String {
        var output = input
        tokens.forEach { (key, value) -> output = output.replace(key, value) }
        require(!Regex("__GH_TECH_\\d+_").containsMatchIn(output)) {
            "Переводчик повредил технический токен"
        }
        return output
    }
}
