package app.githubble.mobile.network

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Remaining GigaChat tokens per model for one authorization key (token packages only). */
object GigaChatBalance {
    private val scopes = listOf("GIGACHAT_API_PERS", "GIGACHAT_API_B2B", "GIGACHAT_API_CORP")

    /** `{"balance":[{"usage":"GigaChat","value":100500}]}` → model/value pairs; empty list when nothing is reported. */
    @JvmStatic fun parse(body: String): List<Pair<String, Long>> {
        val array = runCatching { JSONObject(body.ifBlank { "{}" }).optJSONArray("balance") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val usage = item.optString("usage").trim()
            if (usage.isEmpty()) null else usage to item.optLong("value", 0L)
        }
    }

    @JvmStatic fun group(value: Long): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("ru")).format(value).replace(' ', ' ').replace(' ', ' ')

    /** One line per model, the biggest balance first. */
    @JvmStatic fun format(items: List<Pair<String, Long>>): String =
        if (items.isEmpty()) "Сервис не вернул остаток токенов"
        else items.sortedByDescending { it.second }.joinToString("\n") { "${it.first}: ${group(it.second)} токенов" }

    /**
     * Asks the balance with the scope saved for the connection; without a saved scope the personal, prepaid-business and
     * postpaid access versions are tried in turn, because the key only works with its own one.
     */
    @JvmStatic fun fetch(context: Context, key: String, savedScope: String): String {
        val gateway = ApiGateway(context.applicationContext)
        val order = if (savedScope in scopes) listOf(savedScope) else scopes
        var last: Throwable? = null
        for (scope in order) {
            try {
                return format(runBlocking { gateway.gigaBalance(key, scope) })
            } catch (error: ApiFailure) {
                if (error.statusCode == 403) throw ApiFailure("Баланс по API отдаётся только при покупке пакетов токенов; при оплате по факту (pay-as-you-go) его смотрите в личном кабинете (HTTP 403)", 403)
                last = error
            }
        }
        throw last ?: ApiFailure("Не удалось получить баланс GigaChat")
    }
}
