package app.githubble.mobile.agent

import android.content.Context
import app.githubble.mobile.security.SecureStore
import app.githubble.mobile.network.NetworkDnsPolicy
import app.githubble.mobile.mcp.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** HTTP extensions and Streamable HTTP MCP, with separately encrypted per-profile secrets. */
class NativePluginRegistry(context:Context,private val owner:String){
    private val prefs=context.getSharedPreferences("git_agent_plugins",0)
    private val vault=SecureStore(context)
    private val pref="profile_"+android.util.Base64.encodeToString(owner.toByteArray(),android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    private val client=OkHttpClient.Builder().dns(NetworkDnsPolicy.dns(publicOnly=true)).followRedirects(false).followSslRedirects(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS).build()
    @Synchronized fun list():JSONArray=runCatching{JSONArray(prefs.getString(pref,"[]"))}.getOrDefault(JSONArray())
    private fun secret(id:String)="plugin_secret_${pref}_$id"
    @Synchronized fun save(id:String,name:String,url:String,kind:String,bearer:String,version:String){
        require(name.trim().isNotEmpty()&&name.length<=80){"Нужно название до 80 символов"}
        val target=url.trim().toHttpUrlOrNull()?:error("Неверный URL")
        require(target.scheme=="https"&&target.username.isEmpty()&&target.password.isEmpty()&&target.query==null&&target.fragment==null){"Нужен публичный HTTPS URL без ключей в адресе"}
        require(kind in setOf("GET","POST","MCP")){"Тип: GET, POST или MCP"}
        require(version in setOf("2025-11-25","2026-07-28")){"Неизвестная версия MCP"}
        val rows=list();require(rows.length()<20||id.isNotEmpty()){"Максимум 20 подключений"}
        val selected=id.ifBlank{UUID.randomUUID().toString().replace("-","")};require(selected.matches(Regex("[a-f0-9]{32}")))
        val next=JSONArray();for(i in 0 until rows.length())if(rows.getJSONObject(i).getString("id")!=selected)next.put(rows.getJSONObject(i))
        if(bearer.isNotBlank())vault.put(secret(selected),bearer.trim())
        next.put(JSONObject().put("id",selected).put("name",name.trim()).put("url",target.toString()).put("kind",kind).put("version",version).put("enabled",true).put("tools",JSONArray()))
        prefs.edit().putString(pref,next.toString()).apply();sessions.keys.filter{it.startsWith("$pref:$selected:")}.forEach{sessions.remove(it)}
    }
    @Synchronized fun toggle(id:String){val rows=list();for(i in 0 until rows.length()){val j=rows.getJSONObject(i);if(j.getString("id")==id)j.put("enabled",!j.optBoolean("enabled"))};prefs.edit().putString(pref,rows.toString()).apply()}
    @Synchronized fun remove(id:String){val rows=list();val next=JSONArray();for(i in 0 until rows.length())if(rows.getJSONObject(i).getString("id")!=id)next.put(rows.getJSONObject(i));prefs.edit().putString(pref,next.toString()).apply();vault.remove(secret(id));sessions.keys.filter{it.startsWith("$pref:$id:")}.forEach{sessions.remove(it)}}
    private fun config(id:String):JSONObject{val rows=list();for(i in 0 until rows.length())if(rows.getJSONObject(i).getString("id")==id)return rows.getJSONObject(i);error("Плагин удалён")}
    private fun session(j:JSONObject):McpClient{
        val token=vault.get(secret(j.getString("id")))
        val key="$pref:${j.getString("id")}:${j.optString("url")}:${token.hashCode()}"
        return sessions.getOrPut(key){McpClient(McpServerConfig(j.getString("id"),j.getString("name"),j.getString("url"),token,j.optString("version","2025-11-25")))}
    }
    fun discover(id:String):String=runBlocking {
        val j=config(id)
        if(j.getString("kind")!="MCP")return@runBlocking "HTTPS-расширение сохранено; каждый вызов требует подтверждения."
        val found=session(j).tools();val tools=JSONArray()
        found.take(40).forEachIndexed { index,t->tools.put(JSONObject().put("alias","mcp_${id}_$index").put("name",t.name).put("description",t.description).put("schema",t.schema))}
        synchronized(this@NativePluginRegistry){val rows=list();for(i in 0 until rows.length())if(rows.getJSONObject(i).getString("id")==id)rows.getJSONObject(i).put("tools",tools);prefs.edit().putString(pref,rows.toString()).apply()}
        "Получено инструментов: ${found.size}; доступно в одном каталоге: ${tools.length()}"
    }
    fun catalog():String{
        val result=StringBuilder();val rows=list()
        for(i in 0 until rows.length()){
            val j=rows.getJSONObject(i);if(!j.optBoolean("enabled"))continue
            if(j.optString("kind")!="MCP")result.append("ext_${j.getString("id")} {query}: ").append(j.optString("name").take(80)).append(". User-configured HTTPS ${j.optString("kind")}; explicit review required.\n")
            else {val tools=j.optJSONArray("tools")?:JSONArray();for(n in 0 until tools.length()){val t=tools.getJSONObject(n);result.append(t.getString("alias")).append(" ").append(t.optJSONObject("schema").toString().take(3500)).append(": ").append(t.optString("description").take(180)).append('\n')}}
        }
        return result.toString().take(16000)
    }
    fun operation(name:String):JSONObject?{
        val rows=list();for(i in 0 until rows.length()){
            val j=rows.getJSONObject(i);if(!j.optBoolean("enabled"))continue
            if(name=="ext_${j.getString("id")}")return JSONObject(j.toString())
            val tools=j.optJSONArray("tools")?:JSONArray();for(n in 0 until tools.length())if(tools.getJSONObject(n).optString("alias")==name)return JSONObject(j.toString()).put("tool",tools.getJSONObject(n).getString("name"))
        };return null
    }
    fun invoke(name:String,args:JSONObject,expected:String):String=runBlocking {
        val j=operation(name)?:error("Плагин отключён или удалён")
        require(j.toString()==expected){"Настройки плагина изменились после подтверждения"}
        if(j.optString("kind")=="MCP"){
            val c=McpClient(McpServerConfig(j.getString("id"),j.getString("name"),j.getString("url"),vault.get(secret(j.getString("id"))),j.optString("version","2025-11-25")))
            c.tools();return@runBlocking c.invoke(j.getString("tool"),args)
        }
        val query=args.optString("query");require(query.length<=5000){"Запрос расширения слишком длинный"}
        val base=j.getString("url").toHttpUrlOrNull()!!;val req=Request.Builder()
        if(j.getString("kind")=="GET")req.url(base.newBuilder().addQueryParameter("q",query).build()).get()
        else req.url(base).post(JSONObject().put("query",query).toString().toRequestBody("application/json".toMediaType()))
        val token=vault.get(secret(j.getString("id")));if(token.isNotEmpty())req.header("Authorization","Bearer $token")
        client.newCall(req.build()).execute().use { r->
            if(!r.isSuccessful)error("Плагин: HTTP ${r.code}")
            val input=r.body?.byteStream()?:error("Нет ответа плагина");val out=java.io.ByteArrayOutputStream();input.use{val b=ByteArray(4096);while(true){val n=it.read(b);if(n<0)break;if(out.size()+n>65536)error("Ответ плагина превышает 64 KiB");out.write(b,0,n)}}
            out.toString("UTF-8")
        }
    }
    companion object {private val sessions=ConcurrentHashMap<String,McpClient>()}
}
