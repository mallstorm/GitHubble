package app.githubble.mobile.network

/**
 * Maps network hostnames to stable Smart Routing capability IDs.
 *
 * The IDs intentionally match Remote DNS Registry capabilities so routing policy can
 * change remotely without baking resolver endpoints into the APK.
 */
object AiServiceResolver {
    const val OPENAI = "openai"
    const val GEMINI = "gemini"
    const val CLAUDE = "claude"
    const val GROK = "grok"
    const val GITHUB_COPILOT = "github_copilot"
    const val MICROSOFT_COPILOT = "microsoft_copilot"
    const val KILO = "kilo"
    const val CLINE = "cline"
    const val OPENROUTER = "openrouter"
    const val KIMI = "kimi"
    const val POLZA = "polza"
    const val GIGACHAT = "gigachat"
    const val GROQ = "groq"
    const val ELEVENLABS = "elevenlabs"
    const val GENERAL = "general"

    private val known = listOf(
        OPENAI, GEMINI, CLAUDE, GROK, GITHUB_COPILOT, MICROSOFT_COPILOT,
        KILO, CLINE, OPENROUTER, KIMI, POLZA, GIGACHAT, GROQ, ELEVENLABS
    )

    fun knownServices(): List<String> = known

    fun serviceForHost(hostname: String): String {
        val host = hostname.trim().trimEnd('.').lowercase()
        return when {
            host == "chatgpt.com" || host.endsWith(".chatgpt.com") ||
                host == "openai.com" || host.endsWith(".openai.com") ||
                host == "oaistatic.com" || host.endsWith(".oaistatic.com") ||
                host == "oaiusercontent.com" || host.endsWith(".oaiusercontent.com") -> OPENAI

            host == "gemini.google.com" || host == "gemini.google" ||
                host == "aistudio.google.com" || host == "ai.google.dev" ||
                host == "generativelanguage.googleapis.com" ||
                host == "daily-cloudcode-pa.googleapis.com" ||
                host == "cloudcode-pa.googleapis.com" ||
                host == "geminiweb-pa.clients6.google.com" ||
                host == "oauth2.googleapis.com" -> GEMINI

            host == "claude.ai" || host.endsWith(".claude.ai") ||
                host == "claude.com" || host.endsWith(".claude.com") ||
                host == "anthropic.com" || host.endsWith(".anthropic.com") -> CLAUDE

            host == "grok.com" || host.endsWith(".grok.com") ||
                host == "x.ai" || host.endsWith(".x.ai") ||
                host == "cli-chat-proxy.grok.com" || host == "auth.x.ai" -> GROK

            host == "githubcopilot.com" || host.endsWith(".githubcopilot.com") ||
                host == "api.githubcopilot.com" -> GITHUB_COPILOT

            host == "copilot.microsoft.com" || host.endsWith(".copilot.microsoft.com") -> MICROSOFT_COPILOT
            host == "api.kilo.ai" || host.endsWith(".kilo.ai") -> KILO
            host == "api.cline.bot" || host.endsWith(".cline.bot") || host == "api.workos.com" -> CLINE
            host == "openrouter.ai" || host.endsWith(".openrouter.ai") -> OPENROUTER
            host == "kimi.com" || host.endsWith(".kimi.com") -> KIMI
            host == "polza.ai" || host.endsWith(".polza.ai") -> POLZA
            host.endsWith(".sberbank.ru") || host.contains("gigachat") -> GIGACHAT
            host == "groq.com" || host.endsWith(".groq.com") -> GROQ
            host == "elevenlabs.io" || host.endsWith(".elevenlabs.io") -> ELEVENLABS
            else -> GENERAL
        }
    }

    fun defaultProbeHost(service: String): String? = when (service) {
        OPENAI -> "chatgpt.com"
        GEMINI -> "gemini.google.com"
        CLAUDE -> "claude.ai"
        GROK -> "grok.com"
        GITHUB_COPILOT -> "api.githubcopilot.com"
        MICROSOFT_COPILOT -> "copilot.microsoft.com"
        KILO -> "api.kilo.ai"
        CLINE -> "api.cline.bot"
        OPENROUTER -> "openrouter.ai"
        KIMI -> "auth.kimi.com"
        POLZA -> "polza.ai"
        GIGACHAT -> "ngw.devices.sberbank.ru"
        GROQ -> "api.groq.com"
        ELEVENLABS -> "elevenlabs.io"
        else -> null
    }
}

