package app.githubble.mobile.network

import org.json.JSONObject
import java.time.Instant

data class GrokSubscriptionUsage(
    val plan: String,
    val usedPercent: Double?,
    val remainingPercent: Double?,
    val periodLabel: String,
    val resetsAtMs: Long?,
    val updatedAtMs: Long = System.currentTimeMillis()
)

object GrokSubscriptionSupport {
    fun wantsVideoGeneration(text: String): Boolean {
        val q = text.trim().lowercase()
        if (q.isBlank()) return false
        val video = Regex("""(видео|ролик|клип|анимац|video|movie|clip|animate|animation)""")
            .containsMatchIn(q)
        val create = Regex("""(сгенер|созда|сделай|сделать|генерир|оживи|анимиру|generate|create|make|animate)""")
            .containsMatchIn(q)
        return video && create
    }

    fun videoDuration(text: String): Int {
        val q = text.lowercase()
        return if (Regex("""\b10\s*(с|сек|секунд|s|sec|seconds?)\b""").containsMatchIn(q)) 10 else 6
    }

    fun videoResolution(text: String): String {
        val q = text.lowercase()
        return if (q.contains("720p") || q.contains("720 p") || q.contains("hd")) "720p" else "480p"
    }

    fun videoAspectRatio(size: String, text: String): String {
        val q = text.lowercase()
        return when {
            Regex("""\b9[:xх]16\b""").containsMatchIn(q) ||
                q.contains("вертикал") || q.contains("vertical") || q.contains("portrait") -> "9:16"
            Regex("""\b16[:xх]9\b""").containsMatchIn(q) ||
                q.contains("широкоэкран") || q.contains("widescreen") || q.contains("landscape") -> "16:9"
            Regex("""\b1[:xх]1\b""").containsMatchIn(q) ||
                q.contains("квадрат") || q.contains("square") -> "1:1"
            else -> when (size.lowercase()) {
                "1024x1536" -> "9:16"
                "1536x1024" -> "16:9"
                else -> "16:9"
            }
        }
    }

    fun wantsImageGeneration(text: String): Boolean {
        val q = text.trim().lowercase()
        if (q.isBlank()) return false
        val image = Regex("""(изображ|картин|фото|рисунк|постер|обложк|иллюстрац|image|picture|photo|poster|illustration)""")
            .containsMatchIn(q)
        val create = Regex("""(сгенер|нарис|созда|сделай|сделать|генерир|generate|draw|create|make)""")
            .containsMatchIn(q)
        return image && create
    }

    fun aspectRatio(size: String): String = when (size.lowercase()) {
        "1536x1024" -> "3:2"
        "1024x1536" -> "2:3"
        else -> "1:1"
    }

    fun isImagineRestricted(plan: String): Boolean {
        val p = plan.lowercase()
        return p.contains("free") || p.contains("x basic") || p == "basic"
    }

    fun parseUsage(billing: JSONObject, settings: JSONObject?): GrokSubscriptionUsage {
        val config = billing.optJSONObject("config") ?: JSONObject()
        val direct = config.takeIf { it.has("creditUsagePercent") }
            ?.optDouble("creditUsagePercent", Double.NaN)
            ?.takeIf { it.isFinite() }
        val legacyUsed = config.optJSONObject("used")?.optDouble("val", Double.NaN)
            ?.takeIf { it.isFinite() }
        val legacyLimit = config.optJSONObject("monthlyLimit")?.optDouble("val", Double.NaN)
            ?.takeIf { it.isFinite() && it > 0.0 }
        val used = (direct ?: if (legacyUsed != null && legacyLimit != null) legacyUsed / legacyLimit * 100.0 else null)
            ?.coerceIn(0.0, 100.0)
        val current = config.optJSONObject("currentPeriod")
        val resetRaw = current?.optString("end").orEmpty()
            .ifBlank { config.optString("billingPeriodEnd") }
        val periodType = current?.optString("type").orEmpty().lowercase()
        val period = when {
            periodType.contains("weekly") -> "недельный"
            periodType.contains("monthly") -> "месячный"
            periodType.contains("daily") -> "дневной"
            else -> "кредитный"
        }
        val plan = settings?.optString("subscription_tier_display").orEmpty()
            .ifBlank { billing.optString("subscriptionTier") }
            .ifBlank { "Grok Build" }
        val reset = runCatching { Instant.parse(resetRaw).toEpochMilli() }.getOrNull()
        return GrokSubscriptionUsage(
            plan = plan,
            usedPercent = used,
            remainingPercent = used?.let { (100.0 - it).coerceIn(0.0, 100.0) },
            periodLabel = period,
            resetsAtMs = reset
        )
    }
}

