package app.githubble.mobile.network

import android.content.Context
import app.githubble.mobile.agent.OmniChatConnectionStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Native account OAuth clients. Legacy "proxy-*" ids are aliases of the same accounts (merged by the connection store). */
object NativeSubscriptionRuntime {
    const val CHATGPT="chatgpt-plan"
    const val COPILOT="github-copilot"
    private val locks=ConcurrentHashMap<String,Any>()
    @JvmStatic fun base(p:String):String=when(p){"proxy-chatgpt"->CHATGPT;"proxy-claude"->"claude-subscription";"proxy-grok"->"grok-subscription";else->p}
    @JvmStatic fun supports(p:String):Boolean=base(p) in setOf(CHATGPT,COPILOT,"claude-subscription","grok-subscription","openrouter-account","kilo-account")
    class Login(val provider:String,val url:String,val detail:String,internal val session:Any){
        val cancelled=AtomicBoolean(false)
        fun cancel(){cancelled.set(true);when(val s=session){is NativeAccountOAuth.Session->s.server?.close();is ChatGptPlanClient.Login->ChatGptPlanClient().cancel(s);is SubscriptionProxyClaudeClient.LoginSession->SubscriptionProxyClaudeClient().cancel(s);else->Unit}}
    }
    @JvmStatic fun begin(context:Context,owner:String,p:String):Login=runBlocking {
        when(base(p)){
            "openrouter-account","kilo-account"->NativeAccountOAuth.begin(p).let{Login(p,it.url,"Вход в аккаунт · OAuth",it)}
            CHATGPT->{
                val prefs=context.getSharedPreferences("git_agent_chatgpt_host",0)
                val host=prefs.getString(owner,"").orEmpty().ifBlank{"urn:uuid:"+UUID.randomUUID().also{prefs.edit().putString(owner,"urn:uuid:$it").apply()}}
                ChatGptPlanClient().beginLogin(host).let{Login(p,it.authUrl,"ChatGPT OAuth · без Codex CLI",it)}
            }
            COPILOT->GitHubCopilotClient().beginLogin().let{Login(p,it.verificationUri+"?user_code="+java.net.URLEncoder.encode(it.userCode,"UTF-8"),"Код GitHub: ${it.userCode}",it)}
            "claude-subscription"->SubscriptionProxyClaudeClient().beginLogin().let{Login(p,it.authUrl,"Claude OAuth",it)}
            "grok-subscription"->SubscriptionProxyGrokClient().beginDeviceLogin().let{Login(p,it.verificationUriComplete.ifBlank{it.verificationUri},"Код xAI/Grok: ${it.userCode}",it)}
            else->error("Нет OAuth-клиента")
        }
    }
    /** refresh_token holds the long-lived GitHub token; access_token is the short-lived Copilot token. */
    private fun encodeCopilot(github:String,t:GitHubCopilotClient.ApiToken)=JSONObject().put("access_token",t.token).put("refresh_token",github).put("expires_at",t.expiresAtMs).put("base_url",t.apiBase).put("identity","GitHub Copilot")
    private fun encode(c:ChatGptPlanClient.Credentials)=JSONObject(c.toJson()).put("expires_at",c.expiresAtMs).put("identity",c.email.ifBlank{c.name})
    private fun encode(c:SubscriptionProxyStore.ClaudeCredentials)=JSONObject().put("access_token",c.accessToken).put("refresh_token",c.refreshToken).put("expires_at",c.expiresAtMs).put("identity",c.email).put("account_id",c.accountId).put("organization_id",c.organizationId).put("organization_name",c.organizationName)
    private fun encode(c:SubscriptionProxyStore.GrokCredentials)=JSONObject().put("access_token",c.accessToken).put("refresh_token",c.refreshToken).put("id_token",c.idToken).put("token_type",c.tokenType).put("expires_at",c.expiresAtMs).put("identity",c.email).put("subject",c.subject).put("token_endpoint",c.tokenEndpoint)
    fun claude(j:JSONObject)=SubscriptionProxyStore.ClaudeCredentials(j.getString("access_token"),j.optString("refresh_token"),j.optLong("expires_at"),j.optString("identity"),j.optString("account_id"),j.optString("organization_id"),j.optString("organization_name"))
    fun grok(j:JSONObject)=SubscriptionProxyStore.GrokCredentials(j.getString("access_token"),j.optString("refresh_token"),j.optString("id_token"),j.optString("token_type","Bearer"),j.optLong("expires_at"),j.optString("identity"),j.optString("subject"),j.optString("token_endpoint"))
    @JvmStatic fun await(login:Login):JSONObject=runBlocking {
        val j=when(val s=login.session){
            is NativeAccountOAuth.Session->NativeAccountOAuth.await(s){login.cancelled.get()}
            is ChatGptPlanClient.Login->encode(ChatGptPlanClient().awaitCredentials(s))
            is GitHubCopilotClient.DeviceLogin->{
                val client=GitHubCopilotClient();var github:String?=null
                while(github==null&&System.currentTimeMillis()<s.expiresAtMs){
                    if(login.cancelled.get()||Thread.currentThread().isInterrupted)throw InterruptedException("Вход отменён")
                    delay(s.pollIntervalSeconds.coerceAtLeast(1)*1000);github=client.pollOnce(s)
                }
                val long=github?:error("Срок кода GitHub истёк")
                encodeCopilot(long,client.exchange(long))
            }
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
            "openrouter-account","kilo-account"->NativeAccountOAuth.models(base(p),j.getString("access_token"))
            CHATGPT->ChatGptPlanClient().modelCatalog(ChatGptPlanClient.Credentials.fromJson(j.toString())?:error("Нет ChatGPT credentials"))
            COPILOT->GitHubCopilotClient().models(GitHubCopilotClient.ApiToken(j.getString("access_token"),j.optLong("expires_at"),j.optString("base_url").ifBlank{GitHubCopilotClient.DEFAULT_API}))
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
                COPILOT->{val g=GitHubCopilotClient();val long=old.optString("refresh_token");require(long.isNotBlank()){"Нет токена GitHub; войдите заново"};encodeCopilot(long,g.exchange(long))}
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
