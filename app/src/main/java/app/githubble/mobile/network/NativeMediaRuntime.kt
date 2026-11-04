package app.githubble.mobile.network

import android.content.Context
import android.graphics.BitmapFactory
import app.githubble.mobile.agent.OmniChatConnectionStore
import app.githubble.mobile.data.MessageEntity
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real image-generation transports and private attachment results, never synthetic success. */
object NativeMediaRuntime {
    @JvmStatic fun defaultModel(store:OmniChatConnectionStore,owner:String,p:String):String{
        val selected=store.model(owner,p)
        if(NativeSubscriptionRuntime.base(p)==NativeSubscriptionRuntime.CHATGPT)return selected
        return store.models(owner,p).firstOrNull{it.contains("image",true)||it.contains("imagine",true)||it.contains("art-",true)}
            ?:when(p){"openai"->"gpt-image-2.5-flare";"xai","grok-subscription","proxy-grok"->"grok-imagine-image";else->selected}
    }
    @JvmStatic fun generate(context:Context,store:OmniChatConnectionStore,owner:String,p:String,model:String,prompt:String,size:String):JSONObject=runBlocking {
        require(prompt.trim().length in 2..6000){"Описание изображения: 2–6000 символов"}
        require(size in setOf("1024x1024","1536x1024","1024x1536")){"Неверный размер"}
        val raw=store.credential(owner,p);require(raw.isNotEmpty()){ "Подключение отключено" }
        val runtime=NativeProviderRuntime(context,store);val (entity,key)=runtime.entity(p,model,raw,owner)
        val blobs=mutableListOf<Pair<ByteArray,String>>()
        when {
            NativeSubscriptionRuntime.base(p)==NativeSubscriptionRuntime.CHATGPT->{
                val r=runtime.gateway().generateOpenAiResponsesImage(entity,key,prompt,requestedSize=size);blobs+=r.bytes to r.mime
            }
            NativeSubscriptionRuntime.base(p)=="gemini-antigravity"->{
                val r=runtime.gateway().generateSubscriptionProxyGeminiImage(entity,key,listOf(MessageEntity(chatId=1,role="user",content=prompt)),when(size){"1536x1024"->"3:2";"1024x1536"->"2:3";else->"1:1"})
                r.images.forEach{blobs+=it.bytes to it.mime}
            }
            NativeSubscriptionRuntime.base(p)=="grok-subscription"->{
                val session=NativeSubscriptionRuntime.current(p,raw,store)
                val r=SubscriptionProxyGrokClient().generateImagine(NativeSubscriptionRuntime.grok(session),prompt,when(size){"1536x1024"->"3:2";"1024x1536"->"2:3";else->"1:1"})
                blobs+=r.bytes to r.mime
            }
            p=="yandex"->blobs+=runtime.gateway().generateImage(entity,key,prompt,size) to "image/png"
            p=="anthropic"||NativeSubscriptionRuntime.base(p)=="claude-subscription"->error("Claude не предоставляет генерацию изображений. Выберите подключение OpenAI, Gemini, Grok или совместимый image API.")
            else->{
                val body:JSONObject;val target:okhttp3.HttpUrl
                if(entity.protocol==ApiGateway.GEMINI){
                    target=ApiGateway.endpoint(entity.baseUrl,ApiGateway.GEMINI,"models","${model.removePrefix("models/")}:generateContent")
                    body=JSONObject().put("contents",JSONArray().put(JSONObject().put("role","user").put("parts",JSONArray().put(JSONObject().put("text",prompt)))))
                        .put("generationConfig",JSONObject().put("responseModalities",JSONArray().put("TEXT").put("IMAGE")))
                }else if(p=="openrouter"||NativeSubscriptionRuntime.base(p) in setOf("openrouter-account","kilo-account")){
                    target=ApiGateway.endpoint(entity.baseUrl,ApiGateway.CHAT,"chat","completions")
                    body=JSONObject().put("model",model).put("modalities",JSONArray().put("image").put("text"))
                        .put("messages",JSONArray().put(JSONObject().put("role","user").put("content",prompt)))
                }else{
                    target=ApiGateway.endpoint(entity.baseUrl,ApiGateway.CHAT,"images","generations")
                    body=JSONObject().put("model",model).put("prompt",prompt).put("size",size).put("n",1)
                    if(!model.startsWith("gpt-image"))body.put("response_format","b64_json")
                }
                val request=Request.Builder().url(target).post(body.toString().toRequestBody("application/json".toMediaType()))
                if(entity.protocol==ApiGateway.GEMINI)request.header("x-goog-api-key",key)
                else if(key.isNotBlank())request.header("Authorization","Bearer $key")
                val json=AiNetwork.client().newCall(request.build()).execute().use{r->
                    val text=r.body?.byteStream()?.use{bounded(it,48*1024*1024).toString(Charsets.UTF_8)}.orEmpty()
                    if(!r.isSuccessful)throw ApiFailure("Генерация: HTTP ${r.code}; проверьте модель, доступ и баланс",r.code)
                    JSONObject(text)
                }
                fun data(encoded:String,mime:String="image/png"){if(encoded.isNotBlank())blobs+=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT) to mime}
                fun imageUrl(url:String){
                    if(url.startsWith("data:image/"))data(url.substringAfter("base64,"),url.substringAfter("data:").substringBefore(';'))
                    else if(url.isNotBlank())blobs+=download(url) to "image/png"
                }
                val entries=json.optJSONArray("data");for(i in 0 until (entries?.length()?:0)){
                    val image=entries!!.getJSONObject(i);if(image.optString("b64_json").isNotBlank())data(image.getString("b64_json")) else imageUrl(image.optString("url"))
                }
                val candidates=json.optJSONArray("candidates");for(i in 0 until (candidates?.length()?:0)){
                    val parts=candidates!!.getJSONObject(i).optJSONObject("content")?.optJSONArray("parts")?:continue
                    for(n in 0 until parts.length()){val inline=parts.getJSONObject(n).optJSONObject("inlineData")?:parts.getJSONObject(n).optJSONObject("inline_data")?:continue;data(inline.optString("data"),inline.optString("mimeType",inline.optString("mime_type","image/png")))}
                }
                val choices=json.optJSONArray("choices");for(i in 0 until (choices?.length()?:0)){
                    val images=choices!!.getJSONObject(i).optJSONObject("message")?.optJSONArray("images")?:continue
                    for(n in 0 until images.length())imageUrl(images.getJSONObject(n).optJSONObject("image_url")?.optString("url").orEmpty())
                }
            }
        }
        require(!Thread.currentThread().isInterrupted){"Генерация отменена"}
        require(blobs.isNotEmpty()){ "Сервер завершил запрос без изображения; выберите модель генерации" }
        require(store.credential(owner,p).isNotEmpty()){ "Подключение отключено во время генерации" }
        val dir=File(context.filesDir,"attachments").apply{mkdirs()};val attachments=JSONArray()
        blobs.take(4).forEach { (bytes,mime)->
            require(bytes.size in 1..40*1024*1024){"Неверный размер изображения"}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<80000000){"Некорректное изображение"}
            val name="generated-${UUID.randomUUID()}."+if(mime=="image/jpeg")"jpg" else if(mime=="image/webp")"webp" else "png"
            val file=File(dir,name);file.writeBytes(bytes)
            attachments.put(LocalAttachment.generated(name,mime,file.path,file.length(),"Созданное изображение",prompt).toJson())
        }
        JSONObject().put("provider",p).put("model",model).put("attachments",attachments)
    }
    private fun bounded(input:java.io.InputStream,max:Int):ByteArray{
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>max)error("Ответ слишком большой");out.write(buffer,0,n)};return out.toByteArray()
    }
    private fun download(raw:String):ByteArray{
        var url=raw.toHttpUrl();val client=okhttp3.OkHttpClient.Builder().dns(NetworkDnsPolicy.dns(publicOnly=true)).followRedirects(false).followSslRedirects(false).build()
        repeat(5){
            require(url.scheme=="https"&&url.username.isEmpty()&&url.password.isEmpty()){ "Небезопасный адрес изображения" }
            client.newCall(Request.Builder().url(url).get().build()).execute().use{r->
                if(r.code in listOf(301,302,303,307,308)){url=url.resolve(r.header("Location")?:error("Нет redirect"))?:error("Неверный redirect")}
                else{require(r.isSuccessful){"Загрузка изображения: HTTP ${r.code}"};return r.body?.byteStream()?.use{bounded(it,40*1024*1024)}?:error("Нет изображения")}
            }
        };error("Слишком много redirect")
    }
}
