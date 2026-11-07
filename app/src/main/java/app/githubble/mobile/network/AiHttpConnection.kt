package app.githubble.mobile.network

import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Keeps Java transports on their existing URLConnection API while DNS, TLS/SNI and proxying use OkHttp. */
internal class AiHttpConnection(url: URL): HttpURLConnection(url) {
    private val headers=linkedMapOf<String,String>()
    private val output=ByteArrayOutputStream()
    private var response: Response?=null
    private var call: Call?=null
    override fun setRequestProperty(name: String,value: String) {
        check(!connected) { "Request already connected" }
        headers.keys.firstOrNull { it.equals(name,true) }?.let { headers.remove(it) };headers[name]=value
    }
    override fun getRequestProperty(name: String): String? = headers.entries.firstOrNull { it.key.equals(name,true) }?.value
    override fun getOutputStream(): OutputStream { check(!connected);return output }
    override fun connect() {
        if(connected)return
        val client=AiNetwork.client().newBuilder().connectTimeout(connectTimeout.toLong(),TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toLong(),TimeUnit.MILLISECONDS).followRedirects(instanceFollowRedirects).followSslRedirects(false).build()
        val builder=Request.Builder().url(url)
        headers.forEach { (name,value)->builder.header(name,value) }
        val body=if(doOutput||method !in listOf("GET","HEAD"))output.toByteArray().toRequestBody(getRequestProperty("Content-Type")?.toMediaTypeOrNull()) else null
        val pending=client.newCall(builder.method(method,body).build());call=pending
        response=pending.execute();connected=true
    }
    override fun getResponseCode(): Int { connect();return response!!.code }
    override fun getInputStream(): InputStream { connect();return response!!.body?.byteStream() ?: java.io.ByteArrayInputStream(byteArrayOf()) }
    override fun getErrorStream(): InputStream? = response?.body?.byteStream()
    override fun getHeaderField(name: String?): String? { connect();return name?.let { response!!.header(it) } }
    override fun disconnect() { response?.close();call?.cancel();response=null;connected=false }
    override fun usingProxy(): Boolean = InAppVlessRouter.status().enabled
}
