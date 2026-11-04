package app.githubble.mobile.network

import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/** Account OAuth routes from mallstorm OmniChat, isolated from manual API profiles. */
object NativeAccountOAuth {
    data class Session(val provider:String,val url:String,val code:String="",val verifier:String="",val state:String="",val server:ServerSocket?=null,val deadline:Long=System.currentTimeMillis()+600000)
    private val http=AiNetwork.client().newBuilder().followRedirects(false).followSslRedirects(false).build()
    private fun token():String=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also{SecureRandom().nextBytes(it)})
    private fun request(req:Request):JSONObject=http.newCall(req).execute().use{r->
        val body=r.body?.string().orEmpty();if(!r.isSuccessful)throw ApiFailure("OAuth: HTTP ${r.code}",r.code);JSONObject(body)
    }
    fun begin(p:String):Session {
        if(p=="kilo-account"){
            val j=request(Request.Builder().url("https://api.kilo.ai/api/device-auth/codes").post("{}".toRequestBody("application/json".toMediaType())).build())
            val url=j.getString("verificationUrl").toHttpUrl();require(url.scheme=="https"&&(url.host=="kilo.ai"||url.host.endsWith(".kilo.ai"))){"Неизвестный адрес входа Kilo"}
            return Session(p,url.toString(),j.getString("code"),deadline=System.currentTimeMillis()+j.optInt("expiresIn",600).coerceIn(30,900)*1000L)
        }
        require(p=="openrouter-account")
        val verifier=token();val state=token();val challenge=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val server=ServerSocket(0,2,InetAddress.getByName("127.0.0.1")).apply{soTimeout=1000}
        val url="https://openrouter.ai/auth".toHttpUrl().newBuilder().addQueryParameter("callback_url","http://127.0.0.1:${server.localPort}/callback")
            .addQueryParameter("code_challenge",challenge).addQueryParameter("code_challenge_method","S256").addQueryParameter("state",state).build()
        return Session(p,url.toString(),verifier=verifier,state=state,server=server)
    }
    suspend fun await(s:Session,cancelled:()->Boolean):JSONObject {
        fun active(){if(cancelled()||Thread.currentThread().isInterrupted)throw InterruptedException("Вход отменён")}
        if(s.provider=="kilo-account"){
            while(System.currentTimeMillis()<s.deadline){
                active()
                val url="https://api.kilo.ai/api/device-auth/codes".toHttpUrl().newBuilder().addPathSegment(s.code).build()
                http.newCall(Request.Builder().url(url).get().build()).execute().use{r->
                    when(r.code){202->Unit;in 200..299->{val j=JSONObject(r.body?.string().orEmpty());require(j.optString("token").isNotBlank()){"Kilo не вернул токен"};active();return credentials(j.getString("token"),j.optString("userEmail"))};403->error("Вход Kilo отклонён");410->error("Код Kilo истёк");else->throw ApiFailure("Kilo OAuth: HTTP ${r.code}",r.code)}
                };delay(3000)
            };error("Время входа Kilo истекло")
        }
        val server=s.server?:error("Нет callback listener")
        try{
            while(System.currentTimeMillis()<s.deadline){
                active()
                try{server.accept().use { socket->
                    socket.soTimeout=3000
                    val reader=socket.getInputStream().bufferedReader();val line=StringBuilder()
                    while(line.length<8192){val ch=reader.read();if(ch<0||ch==10)break;line.append(ch.toChar())}
                    val parts=line.toString().trim().split(' ');val uri=("http://127.0.0.1"+(parts.getOrNull(1)?:"/")).toHttpUrl()
                    val valid=parts.firstOrNull()=="GET"&&uri.encodedPath=="/callback"&&MessageDigest.isEqual(s.state.toByteArray(),uri.queryParameter("state").orEmpty().toByteArray())
                    val code=uri.queryParameter("code").orEmpty()
                    val text=if(valid&&code.isNotBlank())"GitHubble: вход подтверждён. Вернитесь в приложение." else "GitHubble: неверный callback. Повторите вход."
                    val body=text.toByteArray();socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()+body)
                    if(valid&&code.isNotBlank()){
                        active();val req=JSONObject().put("code",code).put("code_verifier",s.verifier).put("code_challenge_method","S256")
                        val j=request(Request.Builder().url("https://openrouter.ai/api/v1/auth/keys").post(req.toString().toRequestBody("application/json".toMediaType())).build())
                        val key=j.optString("key").ifBlank{j.optString("api_key")};require(key.isNotBlank()){"OpenRouter не вернул ключ"};active();return credentials(key,"")
                    }
                }}catch(_:SocketTimeoutException){}
            };error("Время входа OpenRouter истекло")
        }finally{server.close()}
    }
    fun models(p:String,access:String):List<String>{
        val base=if(p=="kilo-account")"https://api.kilo.ai/api/openrouter" else "https://openrouter.ai/api/v1"
        val j=request(Request.Builder().url("$base/models").header("Authorization","Bearer $access").build())
        val data=j.optJSONArray("data")?:error("Сервер не вернул каталог моделей");val result=linkedSetOf<String>()
        for(i in 0 until data.length()){val id=data.optJSONObject(i)?.optString("id").orEmpty();if(id.isNotBlank()&&id.length<=160)result.add(id)}
        require(result.isNotEmpty()){"Каталог моделей пуст"};return result.sorted()
    }
    private fun credentials(key:String,email:String)=JSONObject().put("access_token",key).put("refresh_token","").put("expires_at",Long.MAX_VALUE).put("identity",email)
}
