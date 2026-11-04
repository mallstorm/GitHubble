package app.githubble.mobile.agent

import android.content.Context
import app.githubble.mobile.network.NetworkDnsPolicy
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The mallstorm OmniChat public-search adapters, available without a Workspace. */
object NativeWebSearch {
    @JvmStatic fun enabled(c:Context,owner:String)=c.getSharedPreferences("agent_tool_features",0).getBoolean("web_$owner",false)
    @JvmStatic fun enable(c:Context,owner:String,on:Boolean){c.getSharedPreferences("agent_tool_features",0).edit().putBoolean("web_$owner",on).apply()}
    private val client=OkHttpClient.Builder().dns(NetworkDnsPolicy.dns(publicOnly=true)).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
    @JvmStatic fun search(query:String):JSONObject=runBlocking {
        val q=query.trim();require(q.length in 2..300){"Поисковый запрос: 2–300 символов"}
        val urls=listOf("https://www.bing.com/search","https://search.brave.com/search","https://www.mojeek.com/search")
        val errors=java.util.Collections.synchronizedList(mutableListOf<String>())
        val batches=urls.mapIndexed { index,base->async(Dispatchers.IO){
            try{
                val url=base.toHttpUrl().newBuilder().addQueryParameter("q",q).apply{if(index==0)addQueryParameter("format","rss")}.build()
                client.newCall(Request.Builder().url(url).header("User-Agent","Mozilla/5.0 GitHubble/1.0").get().build()).execute().use { r->
                    require(r.isSuccessful){"HTTP ${r.code}"};val body=r.body?:error("Пустой ответ")
                    val bytes=body.byteStream().use{input->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                        while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>2*1024*1024)error("Ответ превышает 2 MiB");out.write(buffer,0,n)};out.toByteArray()
                    }
                    require(bytes.size<=2*1024*1024){"Ответ превышает 2 MiB"}
                    val raw=bytes.toString(Charsets.UTF_8)
                    when(index){0->WebSearchBackends.parseBingRss(raw);1->WebSearchBackends.parseBraveHtml(raw);else->WebSearchBackends.parseMojeekHtml(raw)}
                }
            }catch(e:Exception){if(e is CancellationException)throw e;errors+="${base.toHttpUrl().host}: ${e.message}";emptyList()}
        }}.awaitAll()
        val hits=WebSearchBackends.independentHosts(WebSearchBackends.mergeRoundRobin(batches,16),10)
        if(hits.isEmpty())throw java.io.IOException("Поиск не вернул результатов. "+errors.joinToString("; "))
        val rows=JSONArray();hits.forEach{rows.put(JSONObject().put("title",it.title).put("url",it.url).put("snippet",it.snippet).put("engine",it.backend))}
        JSONObject().put("query",q).put("fetched_at",System.currentTimeMillis()).put("partial",errors.isNotEmpty()).put("results",rows).put("errors",JSONArray(errors))
    }
}
