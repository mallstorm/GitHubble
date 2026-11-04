package app.githubble.mobile.network

import android.content.Context
import app.githubble.mobile.agent.OmniChatConnectionStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Independent direct/proxy credentials with the pre-Gemini-Web mallstorm OAuth clients. */
object NativeSubscriptionRuntime {
    const val CHATGPT="chatgpt-plan"
    private val locks=ConcurrentHashMap<String,Any>()
    @JvmStatic fun base(p:String):String=when(p){"proxy-chatgpt"->CHATGPT;"proxy-gemini"->"gemini-antigravity";"proxy-claude"->"claude-subscription";"proxy-grok"->"grok-subscription";else->p}
    @JvmStatic fun supports(p:String):Boolean=base(p) in setOf(CHATGPT,"gemini-antigravity","claude-subscription","grok-subscription")
    class Login(val provider:String,val url:String,val detail:String,internal val session:Any){
        val cancelled=AtomicBoolean(false)
        fun cancel(){cancelled.set(true);when(val s=session){is ChatGptPlanClient.Login->ChatGptPlanClient().cancel(s);is SubscriptionProxyClaudeClient.LoginSession->SubscriptionProxyClaudeClient().cancel(s);is SubscriptionProxyGeminiClient.LoginSession->SubscriptionProxyGeminiClient().cancel(s)}}
    }
    @JvmStatic fun begin(context:Context,owner:String,p:String):Login=runBlocking {
        when(base(p)){
            CHATGPT->{
                val prefs=context.getSharedPreferences("git_agent_chatgpt_host",0)
                val host=prefs.getString(owner,"").orEmpty().ifBlank{"urn:uuid:"+UUID.randomUUID().also{prefs.edit().putString(owner,"urn:uuid:$it").apply()}}
                ChatGptPlanClient().beginLogin(host).let{Login(p,it.authUrl,"ChatGPT OAuth · без Codex CLI",it)}
            }
            "gemini-antigravity"->SubscriptionProxyGeminiClient().beginLogin().let{Login(p,it.authUrl,"Gemini / Cloud Code OAuth",it)}
            "claude-subscription"->SubscriptionProxyClaudeClient().beginLogin().let{Login(p,it.authUrl,"Claude OAuth",it)}
            "grok-subscription"->SubscriptionProxyGrokClient().beginDeviceLogin().let{Login(p,it.verificationUriComplete.ifBlank{it.verificationUri},"Код xAI/Grok: ${it.userCode}",it)}
            else->error("Нет OAuth-клиента")
        }
    }
    private fun encode(c:ChatGptPlanClient.Credentials)=JSONObject(c.toJson()).put("expires_at",c.expiresAtMs).put("identity",c.email.ifBlank{c.name})
    private fun encode(c:SubscriptionProxyStore.GeminiCredentials)=JSONObject().put("access_token",c.accessToken).put("refresh_token",c.refreshToken).put("expires_at",c.expiresAtMs).put("token_type",c.tokenType).put("identity",c.email).put("project_id",c.projectId).put("base_url",c.apiBaseUrl)
    private fun encode(c:SubscriptionProxyStore.ClaudeCredentials)=JSONObject().put("access_token",c.accessToken).put("refresh_token",c.refreshToken).put("expires_at",c.expiresAtMs).put("identity",c.email).put("account_id",c.accountId).put("organization_id",c.organizationId).put("organization_name",c.organizationName)
    private fun encode(c:SubscriptionProxyStore.GrokCredentials)=JSONObject().put("access_token",c.accessToken).put("refresh_token",c.refreshToken).put("id_token",c.idToken).put("token_type",c.tokenType).put("expires_at",c.expiresAtMs).put("identity",c.email).put("subject",c.subject).put("token_endpoint",c.tokenEndpoint)
    fun gemini(j:JSONObject)=SubscriptionProxyStore.GeminiCredentials(j.getString("access_token"),j.optString("refresh_token"),j.optString("token_type","Bearer"),j.optLong("expires_at"),j.optString("identity"),j.optString("project_id"),j.optString("base_url"))
    fun claude(j:JSONObject)=SubscriptionProxyStore.ClaudeCredentials(j.getString("access_token"),j.optString("refresh_token"),j.optLong("expires_at"),j.optString("identity"),j.optString("account_id"),j.optString("organization_id"),j.optString("organization_name"))
    fun grok(j:JSONObject)=SubscriptionProxyStore.GrokCredentials(j.getString("access_token"),j.optString("refresh_token"),j.optString("id_token"),j.optString("token_type","Bearer"),j.optLong("expires_at"),j.optString("identity"),j.optString("subject"),j.optString("token_endpoint"))
    @JvmStatic fun await(login:Login):JSONObject=runBlocking {
        val j=when(val s=login.session){
            is ChatGptPlanClient.Login->encode(ChatGptPlanClient().awaitCredentials(s))
            is SubscriptionProxyGeminiClient.LoginSession->encode(SubscriptionProxyGeminiClient().awaitCredentials(s))
            is SubscriptionProxyClaudeClient.LoginSession->encode(SubscriptionProxyClaudeClient().awaitCredentials(s))
            is SubscriptionProxyGrokClient.DeviceLogin->{
                var found:SubscriptionProxyStore.GrokCredentials?=null;val client=SubscriptionProxyGrokClient()
                while(found==null&&System.currentTimeMillis()<s.expiresAtMs){
                    if(login.cancelled.get()||Thread.currentThread().isInterrupted)throw InterruptedException("Вход отменён")
                    delay(s.pollIntervalSeconds.coerceAtLeast(1)*1000);found=client.pollDeviceOnce(s)
                };encode(found?:error("Срок кода xAI истёк"))
            }
            else->error("Нет сессии входа")
        }
        if(login.cancelled.get())throw InterruptedException("Вход отменён")
        j.put("provider",login.provider).put("session_generation",UUID.randomUUID().toString())
    }
    @JvmStatic fun discoverInitial(p:String,j:JSONObject):List<String> = runBlocking {
        when(base(p)){
            CHATGPT->ChatGptPlanClient().modelCatalog(ChatGptPlanClient.Credentials.fromJson(j.toString())?:error("Нет ChatGPT credentials"))
            "gemini-antigravity"->SubscriptionProxyGeminiClient().modelCatalogWithEndpoint(gemini(j)).let{j.put("base_url",it.apiBaseUrl);it.models}
            "claude-subscription"->SubscriptionProxyClaudeClient().modelCatalog(claude(j))
            "grok-subscription"->SubscriptionProxyGrokClient().modelCatalog(grok(j))
            else->error("Неизвестная подписка")
        }
    }
    fun current(p:String,raw:String,store:OmniChatConnectionStore,force:Boolean=false):JSONObject {
        val original=JSONObject(raw);val owner=original.getString("owner")
        synchronized(locks.getOrPut("$owner:$p"){Any()}){
            val saved=store.credential(owner,p);require(saved.isNotBlank()){ "Подписка отключена" }
            val old=JSONObject(saved)
            require(saved==raw || (old.optString("session_generation").isNotBlank()&&old.optString("session_generation")==original.optString("session_generation"))){"Сессия изменена; повторите запрос"}
            if(!force&&old.optLong("expires_at")>System.currentTimeMillis()+60000)return old
            if(force&&saved!=raw&&old.optLong("expires_at")>System.currentTimeMillis()+60000)return old
            val fresh=runBlocking {when(base(p)){
                CHATGPT->encode(ChatGptPlanClient().refresh(ChatGptPlanClient.Credentials.fromJson(old.toString())?:error("Нет ChatGPT credentials")))
                "gemini-antigravity"->encode(SubscriptionProxyGeminiClient().refresh(gemini(old)))
                "claude-subscription"->encode(SubscriptionProxyClaudeClient().refresh(claude(old)))
                "grok-subscription"->encode(SubscriptionProxyGrokClient().refresh(grok(old)))
                else->error("Нет refresh")
            }}
            fresh.keys().forEach{old.put(it,fresh.get(it))};store.replaceSubscription(owner,p,saved,old);return old
        }
    }
    @JvmStatic fun discover(p:String,raw:String,store:OmniChatConnectionStore):List<String>{
        var j=current(p,raw,store)
        return try{discoverInitial(p,j)}catch(e:ApiFailure){if(e.statusCode!=401)throw e;j=current(p,j.toString(),store,true);discoverInitial(p,j)}
    }
}
