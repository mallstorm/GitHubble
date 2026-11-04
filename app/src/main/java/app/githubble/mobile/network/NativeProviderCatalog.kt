package app.githubble.mobile.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** mallstorm/OmniChat registry; credentials remain in the per-account connection store. */
object NativeProviderCatalog {
    data class Profile(val id:String,val name:String,val base:String,val protocol:String,val auth:String)
    private var profiles:Map<String,Profile> = emptyMap()
    @Synchronized @JvmStatic fun initialize(context:Context){
        if(profiles.isNotEmpty())return
        profiles=parse(context.assets.open("omnichat/provider_catalog.json").bufferedReader().use{it.readText()})
    }
    internal fun parse(raw:String):Map<String,Profile>{
        val result=linkedMapOf<String,Profile>()
        val items=JSONArray(raw)
        for(i in 0 until items.length()){
            val j=items.getJSONObject(i);val id=j.getString("id");val auth=j.optString("auth_type","bearer")
            if(auth !in setOf("bearer","x-api-key","none","gigachat-oauth","yandex-api-key"))continue
            val wires=j.optJSONArray("protocols")?.toString().orEmpty()
            val protocol=when(id){"anthropic"->ApiGateway.ANTHROPIC;"gemini"->ApiGateway.GEMINI;"ollama"->ApiGateway.OLLAMA;"openai"->ApiGateway.RESPONSES;else->if("chat-completions" in wires)ApiGateway.CHAT else if("responses" in wires)ApiGateway.RESPONSES else ApiGateway.CHAT}
            val base=when(id){"gemini"->"https://generativelanguage.googleapis.com/v1beta";"ollama"->"http://127.0.0.1:11434";"gigachat"->"https://api.giga.chat/v1";"yandex"->"https://ai.api.cloud.yandex.net/v1";else->if(j.isNull("base_url"))"" else j.optString("base_url").trimEnd('/')}
            result[id]=Profile(id,j.optString("name",id),base,protocol,auth)
        }
        result["custom"]=Profile("custom","Свой OpenAI-совместимый API","",ApiGateway.CHAT,"bearer")
        result["proxy-external"]=Profile("proxy-external","Внешний CLIProxyAPI","http://127.0.0.1:8787/v1",ApiGateway.CHAT,"bearer")
        result["proxy-nvidia"]=Profile("proxy-nvidia","NVIDIA NIM · Subscription Proxy","https://integrate.api.nvidia.com/v1",ApiGateway.CHAT,"bearer")
        return result
    }
    @JvmStatic fun names():Map<String,String> = profiles.mapValues{it.value.name}
    @JvmStatic fun profile(id:String):Profile=profiles[id]?:error("Нет API-профиля: $id")
    @JvmStatic fun configuration(id:String):JSONObject=profile(id).let{JSONObject().put("base_url",it.base).put("protocol",it.protocol).put("auth_scope","")}
}
