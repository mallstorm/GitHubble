package app.githubble.mobile.network

import app.githubble.mobile.agent.OmniChatConnectionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Native OmniChat loopback OAuth clients adapted to the existing encrypted account store. */
object BrowserSubscriptions {
    const val CLAUDE="claude-subscription"
    const val GEMINI="gemini-antigravity"
    class Login(val provider: String,val url: String,internal val session: Any) {
        fun cancel() { when(val s=session) {
            is SubscriptionProxyClaudeClient.LoginSession -> SubscriptionProxyClaudeClient().cancel(s)
            is SubscriptionProxyGeminiClient.LoginSession -> SubscriptionProxyGeminiClient().cancel(s)
        } }
    }
    @JvmStatic fun supports(p: String): Boolean = p==CLAUDE || p==GEMINI
    @JvmStatic fun begin(p: String): Login = when(p) {
        CLAUDE -> SubscriptionProxyClaudeClient().beginLogin().let { Login(p,it.authUrl,it) }
        GEMINI -> SubscriptionProxyGeminiClient().beginLogin().let { Login(p,it.authUrl,it) }
        else -> error("Неизвестная подписка")
    }
    @JvmStatic fun await(login: Login): JSONObject = runBlocking {
        when(val s=login.session) {
            is SubscriptionProxyClaudeClient.LoginSession -> encode(SubscriptionProxyClaudeClient().awaitCredentials(s))
            is SubscriptionProxyGeminiClient.LoginSession -> encode(SubscriptionProxyGeminiClient().awaitCredentials(s))
            else -> error("Нет активного входа")
        }.put("provider",login.provider)
    }
    private fun encode(c: SubscriptionProxyStore.ClaudeCredentials): JSONObject = JSONObject()
        .put("access_token",c.accessToken).put("refresh_token",c.refreshToken).put("expires_at",c.expiresAtMs)
        .put("identity",c.email).put("account_id",c.accountId).put("organization_id",c.organizationId).put("organization_name",c.organizationName)
    private fun encode(c: SubscriptionProxyStore.GeminiCredentials): JSONObject = JSONObject()
        .put("access_token",c.accessToken).put("refresh_token",c.refreshToken).put("expires_at",c.expiresAtMs)
        .put("identity",c.email).put("project_id",c.projectId).put("base_url",c.apiBaseUrl).put("token_type",c.tokenType)
    private fun claude(j: JSONObject)=SubscriptionProxyStore.ClaudeCredentials(j.getString("access_token"),j.optString("refresh_token"),j.optLong("expires_at"),j.optString("identity"),j.optString("account_id"),j.optString("organization_id"),j.optString("organization_name"))
    private fun gemini(j: JSONObject)=SubscriptionProxyStore.GeminiCredentials(j.getString("access_token"),j.optString("refresh_token"),j.optString("token_type","Bearer"),j.optLong("expires_at"),j.optString("identity"),j.optString("project_id"),j.optString("base_url"))
    @JvmStatic fun discoverInitial(p: String,j: JSONObject): List<String> = runBlocking {
        if(p==CLAUDE)SubscriptionProxyClaudeClient().modelCatalog(claude(j))
        else SubscriptionProxyGeminiClient().modelCatalogWithEndpoint(gemini(j)).let { j.put("base_url",it.apiBaseUrl);it.models }
    }
    private fun current(p: String,raw: String,store: OmniChatConnectionStore?): JSONObject = runBlocking {
        val old=JSONObject(raw);require(old.optString("provider")==p && supports(p)) { "Сессия другого провайдера" }
        if(old.optLong("expires_at")>System.currentTimeMillis()+60000)return@runBlocking old
        val fresh=if(p==CLAUDE)encode(SubscriptionProxyClaudeClient().refresh(claude(old)))
            else encode(SubscriptionProxyGeminiClient().refresh(gemini(old)))
        val out=JSONObject(old.toString());fresh.keys().forEach { out.put(it,fresh.get(it)) }
        store?.replaceSubscription(old.getString("owner"),p,raw,out)
        out
    }
    @JvmStatic fun discover(p: String,raw: String,store: OmniChatConnectionStore?): List<String> = runBlocking {
        val j=current(p,raw,store)
        if(p==CLAUDE)SubscriptionProxyClaudeClient().modelCatalog(claude(j))
        else SubscriptionProxyGeminiClient().modelCatalogWithEndpoint(gemini(j)).let { catalog ->
            if(j.optString("base_url")!=catalog.apiBaseUrl && store!=null) {
                val expected=j.toString();j.put("base_url",catalog.apiBaseUrl)
                store.replaceSubscription(j.getString("owner"),p,expected,j)
            }
            catalog.models
        }
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
        val builder: Request.Builder; val body: JSONObject
        if(p==CLAUDE) {
            body=JSONObject().put("model",model).put("max_tokens",8192).put("stream",false).put("system",instructions).put("messages",turns)
            builder=SubscriptionProxyClaudeClient.apiHeaders(Request.Builder().url("https://api.anthropic.com/v1/messages"),j.getString("access_token"))
        } else {
            val contents=JSONArray()
            for(i in 0 until turns.length()) { val e=turns.getJSONObject(i)
                contents.put(JSONObject().put("role",if(e.getString("role")=="assistant")"model" else "user")
                    .put("parts",JSONArray().put(JSONObject().put("text",e.getString("content"))))) }
            val payload=JSONObject().put("contents",contents).put("systemInstruction",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",instructions))))
            val base=j.optString("base_url").takeIf { it in listOf(SubscriptionProxyGeminiClient.DAILY_API,SubscriptionProxyGeminiClient.API_ENDPOINT) } ?: SubscriptionProxyGeminiClient.DAILY_API
            body=JSONObject().put("model",model).put("project",j.getString("project_id")).put("userAgent","antigravity").put("requestType","agent")
                .put("requestId","agent-"+java.util.UUID.randomUUID()).put("request",payload)
            builder=SubscriptionProxyGeminiClient.cloudCodeHeaders(Request.Builder().url(base+"/v1internal:streamGenerateContent?alt=sse"),j.getString("access_token"))
                .header("Accept","text/event-stream")
        }
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
            val rawReply=bytesOut.toString("UTF-8")
            parse(p,rawReply)
        }
    }
    @JvmStatic fun parse(p: String,raw: String): String {
        val out=StringBuilder();var finished=false
        if(p==CLAUDE) {
            val j=JSONObject(raw);if(j.has("error"))throw IOException("Ошибка Claude")
            val a=j.optJSONArray("content") ?: JSONArray()
            for(i in 0 until a.length()){val block=a.optJSONObject(i) ?: continue;if(block.optString("type")=="text")out.append(block.optString("text"))}
            finished=j.optString("stop_reason").isNotBlank()
        } else {
            for(frame in raw.replace("\r\n","\n").split("\n\n")) {
                val data=frame.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.substring(5).trim() }
                if(data.isBlank()||data=="[DONE]")continue
                val event=JSONObject(data);if(event.has("error"))throw IOException("Ошибка Gemini")
                val j=event.optJSONObject("response") ?: event
                val a=j.optJSONArray("candidates") ?: continue
                for(i in 0 until a.length()){val c=a.optJSONObject(i) ?: continue
                    if(c.optString("finishReason").isNotBlank())finished=true
                    val parts=c.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for(k in 0 until parts.length()){val part=parts.optJSONObject(k) ?: continue;if(!part.optBoolean("thought"))out.append(part.optString("text"))}
                }
            }
        }
        if(!finished||out.isBlank())throw IOException("Подписка не завершила текстовый ответ")
        return out.toString()
    }
}
