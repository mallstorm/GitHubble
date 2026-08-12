package app.githubble.mobile.core.translation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
/** OpenAI-compatible translation provider. Disabled until endpoint/model/key are configured by the user. */
class HttpTranslationProvider(private val endpoint:()->String,private val apiKey:()->String,private val model:()->String):TranslationProvider{
 override suspend fun translate(text:String,sourceLanguage:String?,targetLanguage:String)=withContext(Dispatchers.IO){
  val base=endpoint().trimEnd('/'); require(base.isNotBlank()){"Провайдер перевода не настроен"}; val key=apiKey(); require(key.isNotBlank()){"API-ключ перевода не указан"}
  val body=buildJsonObject{put("model",model());putJsonArray("messages"){addJsonObject{put("role","system");put("content","Переводи естественный язык на русский. Не изменяй код, команды, URL, пути, имена файлов, идентификаторы и Markdown-разметку. Не добавляй пояснений.")};addJsonObject{put("role","user");put("content",text)}};put("temperature",0)}.toString()
  val c=URL("$base/chat/completions").openConnection() as HttpURLConnection;c.requestMethod="POST";c.doOutput=true;c.connectTimeout=20000;c.readTimeout=60000;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer $key");c.outputStream.use{it.write(body.toByteArray())};val code=c.responseCode;val raw=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};if(code !in 200..299)error("Translation HTTP $code: ${raw.take(250)}");Json.parseToJsonElement(raw).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
 }
}
