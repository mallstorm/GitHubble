package app.githubble.mobile.network

import android.content.Context
import app.githubble.mobile.agent.OmniChatConnectionStore
import app.githubble.mobile.agent.OmniChatChatClient
import app.githubble.mobile.data.ProviderEntity
import app.githubble.mobile.data.MessageEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Same native protocol adapters as mallstorm OmniChat, behind GitHubble's encrypted store. */
class NativeProviderRuntime(context:Context,private val store:OmniChatConnectionStore){
    private val app=context.applicationContext
    private val gateway=ApiGateway(app)
    // Scoped to this runtime; do not alter the user's explicit provider settings.
    private val protocolCache=app.getSharedPreferences("provider_protocol_cache",Context.MODE_PRIVATE)
    private val toolCache=app.getSharedPreferences("native_tool_capability",Context.MODE_PRIVATE)
    fun handles(p:String):Boolean=NativeSubscriptionRuntime.supports(p)||NativeProviderCatalog.names().containsKey(p)
    private fun provider(p:String,model:String,raw:String,owner:String,instructions:String):Pair<ProviderEntity,String>{
        var type=p;var key=raw;var scope="";var protocol=ApiGateway.CHAT;var base=""
        if(NativeSubscriptionRuntime.supports(p)){
            val j=NativeSubscriptionRuntime.current(p,raw,store);key=j.getString("access_token")
            when(NativeSubscriptionRuntime.base(p)){
                "openrouter-account"->{type="openrouter";base="https://openrouter.ai/api/v1";protocol=ApiGateway.CHAT}
                "kilo-account"->{type="kilo-gateway";base="https://api.kilo.ai/api/openrouter";protocol=ApiGateway.CHAT}
                NativeSubscriptionRuntime.CHATGPT->{type="subscription-proxy-chatgpt";base=ChatGptPlanClient.API_BASE_URL;protocol=ApiGateway.RESPONSES}
                NativeSubscriptionRuntime.COPILOT->{type="github-copilot";base=j.optString("base_url").ifBlank{GitHubCopilotClient.DEFAULT_API};protocol=ApiGateway.CHAT}
                "claude-subscription"->{type="subscription-proxy-claude";base="https://api.anthropic.com/v1";protocol=ApiGateway.ANTHROPIC}
                "grok-subscription"->{type="subscription-proxy-grok";base=SubscriptionProxyGrokClient.GROK_BUILD_BASE;protocol=ApiGateway.RESPONSES}
            }
        }else{
            val template=NativeProviderCatalog.profile(p)
            val c=store.configuration(owner,p)
            base=c.optString("base_url",template.base);protocol=NativeProviderCatalog.wireForModel(p,model,c.optString("protocol",template.protocol));scope=c.optString("auth_scope")
            if(key=="__local_no_auth__")key=""
            if(NativeProviderCatalog.isCustom(p))type="custom"
        }
        return ProviderEntity(id=1,name=store.displayName(p),baseUrl=base,encryptedApiKey="",model=model,protocol=protocol,
            systemPrompt=instructions,providerType=type,authScope=scope,allowLocalHttp=base.startsWith("http://")) to key
    }
    @JvmOverloads fun complete(p:String,model:String,raw:String,owner:String,history:JSONArray,instructions:String,mime:String?=null,image:ByteArray?=null,onText:java.util.function.Consumer<String>?=null):String{
        var inputTokens=0L;var outputTokens=0L;var reported=false
        val response=runBlocking{
        val turns=mutableListOf<MessageEntity>()
        var attachment:LocalAttachment?=null
        if(image!=null){
            require(image.size<=20*1024*1024){"Фото должно быть до 20 MiB"}
            val dir=File(app.filesDir,"attachments").apply{mkdirs()}
            val f=File(dir,"input-${UUID.randomUUID()}."+if(mime=="image/png")"png" else "jpg");f.writeBytes(image)
            attachment=LocalAttachment("Фото",mime?:"image/jpeg",f.path,f.length())
        }
        val lastUser=(history.length()-1 downTo 0).firstOrNull{history.optJSONObject(it)?.optString("role")=="user"}?:-1
        for(i in maxOf(0,history.length()-28) until history.length()){
            val e=history.optJSONObject(i)?:continue;val role=e.optString("role")
            if(role !in setOf("user","agent","assistant"))continue
            turns+=MessageEntity(chatId=1,role=if(role=="agent")"assistant" else role,content=e.optString("body"),
                attachmentsJson=if(i==lastUser&&attachment!=null)LocalAttachment.encode(listOf(attachment)) else "[]")
        }
        suspend fun attempt(session:String):String{
            val (entity,key)=provider(p,model,session,owner,instructions)
            val cacheKey=listOf(owner,p,model,entity.baseUrl).joinToString("|")
            val eligible=!NativeSubscriptionRuntime.supports(p)&&ProtocolResolver.eligible(entity.providerType,entity.baseUrl)
            val remembered=if(eligible)protocolCache.getString(cacheKey,null) else null
            val active=if(remembered!=null&&remembered!=entity.protocol)entity.copy(protocol=remembered) else entity
            val text: suspend (String)->Unit={value->onText?.accept(value)}
            val usage: suspend (UsageSnapshot)->Unit={u->
                inputTokens=(u.inputTokens?:0).toLong()
                outputTokens=(u.outputTokens?:0).toLong()
                reported=u.inputTokens!=null||u.outputTokens!=null
            }
            return try{
                gateway.stream(active,key,turns,text,usage)
            }catch(error:ApiFailure){
                if(!eligible||!ProtocolResolver.mismatch(error))throw error
                // The upstream rejected the wire format before producing content: try the other formats.
                var last:ApiFailure=error
                for(candidate in ProtocolResolver.candidates(active.protocol)){
                    try{
                        val result=gateway.stream(active.copy(protocol=candidate),key,turns,text,usage)
                        protocolCache.edit().putString(cacheKey,candidate).apply()
                        return result
                    }catch(next:ApiFailure){
                        if(!ProtocolResolver.mismatch(next))throw next
                        last=next
                    }
                }
                throw last
            }
        }
        try{attempt(raw)}catch(e:ApiFailure){
            if(e.statusCode!=401||!NativeSubscriptionRuntime.supports(p))throw e
            val fresh=NativeSubscriptionRuntime.current(p,raw,store,true);attempt(fresh.toString())
        }finally{attachment?.let{File(it.path).delete()}}
        }
        OmniChatChatClient.reportUsage(inputTokens,outputTokens,reported)
        return response
    }
    /** Wire protocol on which native tool calling may be attempted for this connection, or null. */
    fun toolWire(p:String,model:String,raw:String,owner:String):String?{
        if(model.isBlank())return null
        val (entity,_)=try{provider(p,model,raw,owner,"")}catch(e:Exception){return null}
        val type=entity.providerType
        if(type=="openai-codex"||type.startsWith("subscription-proxy-")||type in setOf("gigachat","yandex","azure"))return null
        if(NativeSubscriptionRuntime.supports(p)&&type !in setOf("github-copilot","openrouter","kilo-gateway"))return null
        val remembered=if(ProtocolResolver.eligible(type,entity.baseUrl))protocolCache.getString(listOf(owner,p,model,entity.baseUrl).joinToString("|"),null) else null
        val wire=remembered?:entity.protocol
        return wire.takeIf{it==ApiGateway.CHAT||it==ApiGateway.RESPONSES||it==ApiGateway.ANTHROPIC||it==ApiGateway.GEMINI}
    }
    /** true = native tools confirmed, false = rejected within the last week, null = unknown (probe needed). */
    fun toolCapability(key:String):Boolean?{
        val v=toolCache.getString(key,null)?:return null
        if(v=="1")return true
        val at=v.removePrefix("0:").toLongOrNull()?:return null
        return if(System.currentTimeMillis()-at<7L*24*3600*1000)false else null
    }
    fun setToolCapability(key:String,supported:Boolean){
        toolCache.edit().putString(key,if(supported)"1" else "0:"+System.currentTimeMillis()).apply()
    }
    /** Identifies the exact endpoint/model/protocol for capability caching. */
    fun toolKey(p:String,model:String,raw:String,owner:String,wire:String):String=
        listOf(p,model,provider(p,model,raw,owner,"").first.baseUrl,wire).joinToString("|")
    fun postToolJson(p:String,model:String,raw:String,owner:String,wire:String,body:JSONObject):JSONObject=runBlocking{
        suspend fun once(session:String):JSONObject{val (entity,key)=provider(p,model,session,owner,"");return gateway.postJson(entity,key,wire,JSONObject(body.toString()))}
        suspend fun attempt(session:String):JSONObject{
            try{return once(session)}catch(e:ApiFailure){
                if(e.statusCode!=401||!NativeSubscriptionRuntime.supports(p))throw e
                return once(NativeSubscriptionRuntime.current(p,session,store,true).toString())
            }
        }
        var current=raw;var hops=0
        while(true){
            try{return@runBlocking attempt(current)}catch(e:ApiFailure){
                // Several accounts of one subscription: a rejected or exhausted account hands over to the next one.
                val next=if(NativeSubscriptionRuntime.supports(p)&&hops<12)store.failoverSession(owner,p,current,e.statusCode,e.message) else null
                if(next==null)throw e
                current=next;hops++
            }
        }
        JSONObject()
    }
    fun completeStreaming(p:String,model:String,raw:String,owner:String,history:JSONArray,instructions:String,onText:java.util.function.Consumer<String>):String =
        complete(p,model,raw,owner,history,instructions,null,null,onText)
    fun models(p:String,raw:String,owner:String):List<String>{
        if(NativeSubscriptionRuntime.supports(p))return NativeSubscriptionRuntime.discover(p,raw,store)
        val (entity,key)=provider(p,"",raw,owner,"")
        return runBlocking{gateway.models(entity.baseUrl,key,entity.protocol,entity.providerType,entity.authScope)}
    }
    fun entity(p:String,model:String,raw:String,owner:String)=provider(p,model,raw,owner,"")
    fun gateway()=gateway
}
