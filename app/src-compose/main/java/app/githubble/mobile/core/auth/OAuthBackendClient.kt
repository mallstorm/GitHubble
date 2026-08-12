package app.githubble.mobile.core.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
class OAuthBackendClient(private val endpoint:()->String){ suspend fun exchange(code:String,verifier:String,redirectUri:String):String=withContext(Dispatchers.IO){ val base=endpoint().trimEnd('/'); require(base.isNotBlank()){ "OAuth backend не настроен" }; val c=URL("$base/oauth/github/exchange").openConnection() as HttpURLConnection;c.requestMethod="POST";c.doOutput=true;c.connectTimeout=15000;c.readTimeout=30000;c.setRequestProperty("Content-Type","application/json"); val payload="""{"code":"${esc(code)}","code_verifier":"${esc(verifier)}","redirect_uri":"${esc(redirectUri)}"}""";c.outputStream.use{it.write(payload.toByteArray())};val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};if(c.responseCode !in 200..299)error("OAuth backend HTTP ${c.responseCode}: ${raw.take(200)}");Json.parseToJsonElement(raw).jsonObject["access_token"]?.jsonPrimitive?.content?:error("Backend не вернул access_token") } ; private fun esc(v:String)=v.replace("\\","\\\\").replace("\"","\\\"") }
