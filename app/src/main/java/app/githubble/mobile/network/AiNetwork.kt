package app.githubble.mobile.network

import android.content.Context
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Single routing/DNS boundary for API keys and every subscription transport. */
object AiNetwork {
    @JvmStatic fun initialize(context: Context) {
        NetworkDnsPolicy.initialize(context)
        DnsRegistryManager.initialize(context)
        DnsRouteStore.initialize(context)
        InAppVlessRouter.initialize(context)
        InAppVlessRouteRestorer.start(context.applicationContext as android.app.Application)
        DnsHealthEngine.initialize(context)
    }
    @JvmStatic fun client(): OkHttpClient = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns()).proxySelector(InAppVlessRouter.proxySelector())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS)
        .build()
    @JvmStatic fun open(url: String): HttpURLConnection = AiHttpConnection(URL(url))
    @JvmStatic fun settings(): String = JSONObject().put("mode",NetworkDnsPolicy.current().mode)
        .put("endpoint",NetworkDnsPolicy.current().endpoint).toString()
    @JvmStatic fun save(mode: String, endpoint: String) { NetworkDnsPolicy.save(mode,endpoint) }
    @JvmStatic fun probe(host: String): String = NetworkDnsPolicy.probe(host).toString()
    @JvmStatic fun registry(): String {
        val s=DnsRegistryManager.snapshot();val a=JSONArray()
        s.allProfiles.forEach { p -> a.put(JSONObject().put("id",p.id).put("name",p.name).put("mode",p.mode)
            .put("endpoint",p.endpoint).put("domains",JSONArray(p.domains)).put("capabilities",JSONArray(p.capabilities))
            .put("source",p.source).put("health",DnsRegistryManager.health(p.id)?.toString() ?: "Не проверен")) }
        return JSONObject().put("url",s.remoteUrl).put("updated",s.lastUpdatedAt).put("error",s.lastError ?: "")
            .put("profiles",a).put("routes",DnsHealthEngine.status.value.toString()).toString()
    }
    @JvmStatic fun refreshRegistry(url: String): String {
        DnsRegistryManager.setRemoteRegistryUrl(url);DnsRegistryManager.refreshRemoteRegistry()
        return registry()
    }
    @JvmStatic fun addDns(name: String,mode: String,endpoint: String,domains: String,services: String) {
        DnsRegistryManager.addLocalProfile(name,mode,endpoint,domains,services)
    }
    @JvmStatic fun removeDns(id: String) { DnsRegistryManager.removeLocalProfile(id) }
    @JvmStatic fun sweep() { kotlinx.coroutines.runBlocking { DnsHealthEngine.runSweep() } }
    @JvmStatic fun profiles(): String {
        val a=JSONArray();val state=InAppVlessRouter.status()
        InAppVlessRouter.profiles().forEach { a.put(JSONObject().put("id",it.id).put("name",it.name)
            .put("active",state.enabled && it.id==state.activeProfileId)) }
        return JSONObject().put("profiles",a).put("enabled",state.enabled).put("error",state.error ?: "").toString()
    }
    @JvmStatic fun importProfile(name: String,raw: String) {
        val text=raw.trim()
        if(text.startsWith("{"))InAppVlessRouter.upsert(VlessProfile(name=name,uri=text))
        else text.lines().filter { it.isNotBlank() }.forEachIndexed { i,line ->
            InAppVlessRouter.upsert(VlessProfile(name=if(i==0)name else "$name ${i+1}",uri=line.trim())) }
    }
    @JvmStatic fun enable(id: String) { InAppVlessRouter.enable(id) }
    @JvmStatic fun disable() { InAppVlessRouter.disable() }
    @JvmStatic fun removeProfile(id: String) { InAppVlessRouter.remove(id) }
}
