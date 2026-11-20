package app.githubble.mobile.network

import app.githubble.mobile.agent.OmniChatConnectionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Native loopback OAuth for Claude Code adapted to the existing encrypted account store. */
object BrowserSubscriptions {
    const val CLAUDE="claude-subscription"
    class Login(val provider: String,val url: String,internal val session: Any) {
        fun cancel() { when(val s=session) {
            is SubscriptionProxyClaudeClient.LoginSession -> SubscriptionProxyClaudeClient().cancel(s)
        } }
    }
    @JvmStatic fun supports(p: String): Boolean = p==CLAUDE
    @JvmStatic fun begin(p: String): Login = when(p) {
        CLAUDE -> SubscriptionProxyClaudeClient().beginLogin().let { Login(p,it.authUrl,it) }
        else -> error("Неизвестная подписка")
    }
    @JvmStatic fun await(login: Login): JSONObject = runBlocking {
        when(val s=login.session) {
            is SubscriptionProxyClaudeClient.LoginSession -> encode(SubscriptionProxyClaudeClient().awaitCredentials(s))
            else -> error("Нет активного входа")
        }.put("provider",login.provider)
    }
    private fun encode(c: SubscriptionProxyStore.ClaudeCredentials): JSONObject = JSONObject()
        .put("access_token",c.accessToken).put("refresh_token",c.refreshToken).put("expires_at",c.expiresAtMs)
        .put("identity",c.email).put("account_id",c.accountId).put("organization_id",c.organizationId).put("organization_name",c.organizationName)
    private fun claude(j: JSONObject)=SubscriptionProxyStore.ClaudeCredentials(j.getString("access_token"),j.optString("refresh_token"),j.optLong("expires_at"),j.optString("identity"),j.optString("account_id"),j.optString("organization_id"),j.optString("organization_name"))
    @JvmStatic fun discoverInitial(p: String,j: JSONObject): List<String> = runBlocking {
        require(p==CLAUDE) { "Неизвестная подписка" }
        SubscriptionProxyClaudeClient().modelCatalog(claude(j))
    }
    private fun current(p: String,raw: String,store: OmniChatConnectionStore?): JSONObject = runBlocking {
        val old=JSONObject(raw);require(old.optString("provider")==p && supports(p)) { "Сессия другого провайдера" }
        if(old.optLong("expires_at")>System.currentTimeMillis()+60000)return@runBlocking old
        val fresh=encode(SubscriptionProxyClaudeClient().refresh(claude(old)))
        val out=JSONObject(old.toString());fresh.keys().forEach { out.put(it,fresh.get(it)) }
        store?.replaceSubscription(old.getString("owner"),p,raw,out)
        out
    }
    @JvmStatic fun discover(p: String,raw: String,store: OmniChatConnectionStore?): List<String> = runBlocking {
        val j=current(p,raw,store)
        SubscriptionProxyClaudeClient().modelCatalog(claude(j))
    }
    @JvmStatic fun complete(p: String,model: String,raw: String,history: JSONArray,project: String,repository: String,store: OmniChatConnectionStore?): String {
        val j=current(p,raw,store)
        val turns=JSONArray()
        for(i in maxOf(0,history.length()-28) until history.length()) {
            val e=history.optJSONObject(i) ?: continue
            if(e.optString("role") !in setOf("user","agent"))continue
            turns.put(JSONObject().put("role",if(e.optString("role")=="agent")"assistant" else "user").put("content",e.optString("body")))
        }
        val instructions="Вы помощник Git Agent. Проект: $project. Репозиторий: $repository. Не заявляйте выполнение операций без инструментального результата."
        val body=JSONObject().put("model",model).put("max_tokens",8192).put("stream",false).put("system",instructions).put("messages",turns)
        val builder=SubscriptionProxyClaudeClient.apiHeaders(Request.Builder().url("https://api.anthropic.com/v1/messages"),j.getString("access_token"))
        val bytes=body.toString().toByteArray();require(bytes.size<=160*1024) { "Контекст слишком большой" }
        return AiNetwork.client().newCall(builder.post(bytes.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if(!r.isSuccessful)throw IOException("Подписка HTTP ${r.code}; проверьте авторизацию, доступ к модели и лимиты")
            val bytesOut=java.io.ByteArrayOutputStream()
            (r.body?.byteStream() ?: throw IOException("Пустой ответ")).use { input ->
                val buffer=ByteArray(4096)
                while(true) {
                    if(Thread.currentThread().isInterrupted)throw InterruptedException()
                    val n=input.read(buffer);if(n<0)break
                    if(bytesOut.size()+n>4*1024*1024)throw IOException("Ответ превышает 4 МБ")
                    bytesOut.write(buffer,0,n)
                }
            }
            parse(p,bytesOut.toString("UTF-8"))
        }
    }
    @JvmStatic fun parse(p: String,raw: String): String {
        require(p==CLAUDE) { "Неизвестная подписка" }
        val out=StringBuilder()
        val j=JSONObject(raw);if(j.has("error"))throw IOException("Ошибка Claude")
        val a=j.optJSONArray("content") ?: JSONArray()
        for(i in 0 until a.length()){val block=a.optJSONObject(i) ?: continue;if(block.optString("type")=="text")out.append(block.optString("text"))}
        val finished=j.optString("stop_reason").isNotBlank()
        if(!finished||out.isBlank())throw IOException("Подписка не завершила текстовый ответ")
        return out.toString()
    }
}
