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
    fun handles(p:String):Boolean=NativeSubscriptionRuntime.supports(p)||NativeProviderCatalog.names().containsKey(p)
    private fun provider(p:String,model:String,raw:String,owner:String,instructions:String):Pair<ProviderEntity,String>{
        var type=p;var key=raw;var scope="";var protocol=ApiGateway.CHAT;var base=""
        if(NativeSubscriptionRuntime.supports(p)){
            val j=NativeSubscriptionRuntime.current(p,raw,store);key=j.getString("access_token")
            when(NativeSubscriptionRuntime.base(p)){
                "openrouter-account"->{type="openrouter";base="https://openrouter.ai/api/v1";protocol=ApiGateway.CHAT}
                "kilo-account"->{type="kilo-gateway";base="https://api.kilo.ai/api/openrouter";protocol=ApiGateway.CHAT}
                NativeSubscriptionRuntime.CHATGPT->{type="subscription-proxy-chatgpt";base=ChatGptPlanClient.API_BASE_URL;protocol=ApiGateway.RESPONSES}
                "gemini-antigravity"->{type="subscription-proxy-gemini";base=j.optString("base_url").takeIf{it in listOf(SubscriptionProxyGeminiClient.DAILY_API,SubscriptionProxyGeminiClient.API_ENDPOINT)}?:SubscriptionProxyGeminiClient.DAILY_API;scope=j.getString("project_id");protocol=ApiGateway.GEMINI}
                "claude-subscription"->{type="subscription-proxy-claude";base="https://api.anthropic.com/v1";protocol=ApiGateway.ANTHROPIC}
                "grok-subscription"->{type="subscription-proxy-grok";base=SubscriptionProxyGrokClient.GROK_BUILD_BASE;protocol=ApiGateway.RESPONSES}
            }
        }else{
            val template=NativeProviderCatalog.profile(p)
            val c=store.configuration(owner,p)
            base=c.optString("base_url",template.base);protocol=NativeProviderCatalog.wireForModel(p,model,c.optString("protocol",template.protocol));scope=c.optString("auth_scope")
            if(key=="__local_no_auth__")key=""
            if(p=="proxy-nvidia")type="nvidia"
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
            return gateway.stream(entity,key,turns,{value->onText?.accept(value)}, {u->inputTokens=(u.inputTokens?:0).toLong();outputTokens=(u.outputTokens?:0).toLong();reported=u.inputTokens!=null||u.outputTokens!=null})
        }
        try{attempt(raw)}catch(e:ApiFailure){
            if(e.statusCode!=401||!NativeSubscriptionRuntime.supports(p))throw e
            val fresh=NativeSubscriptionRuntime.current(p,raw,store,true);attempt(fresh.toString())
        }finally{attachment?.let{File(it.path).delete()}}
        }
        OmniChatChatClient.reportUsage(inputTokens,outputTokens,reported)
        return response
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
