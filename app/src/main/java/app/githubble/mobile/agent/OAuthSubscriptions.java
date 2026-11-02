package app.githubble.mobile.agent;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Native port of OmniChat's OpenAI Codex, Cline/WorkOS and global Kimi and xAI/Grok device flows.
 * All network destinations are fixed; account tokens never enter ordinary preferences or history.
 */
public final class OAuthSubscriptions {
    public static final String OPENAI="openai-subscription",CLINE="cline-subscription",KIMI="kimi-subscription",GROK="grok-subscription";
    private static final String GROK_CLIENT="b1a00492-073a-47ea-816f-4c329264a828";
    private static final String GROK_BASE="https://cli-chat-proxy.grok.com/v1";
    private static final String OPENAI_CLIENT="app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String CLINE_CLIENT="client_01K3A541FN8TA3EPPHTD2325AR";
    private static final String KIMI_CLIENT="17e5f671-d194-4dfb-9706-5516cb48c098";
    private final OmniChatConnectionStore store;
    private final String device;
    public interface Transport { Reply send(String url,String method,String body,boolean form,Map<String,String> headers)throws Exception; }
    public static final class Reply {
        public final int status;public final String body;
        public Reply(int status,String body){this.status=status;this.body=body;}
        public JSONObject json()throws Exception{return new JSONObject(body);}
    }
    public static final class DeviceLogin {
        public final String provider,deviceCode,userCode,url,tokenEndpoint;
        public final long expires;
        public long interval;
        DeviceLogin(String provider,String deviceCode,String userCode,String url,long expires,long interval){
            this(provider,deviceCode,userCode,url,expires,interval,"");
        }
        DeviceLogin(String provider,String deviceCode,String userCode,String url,long expires,long interval,String tokenEndpoint){
            this.provider=provider;this.deviceCode=deviceCode;this.userCode=userCode;this.url=url;this.expires=expires;this.interval=interval;this.tokenEndpoint=tokenEndpoint;
        }
    }
    private final Transport transport;
    public OAuthSubscriptions(Context context,OmniChatConnectionStore store){
        this.store=store;
        android.content.SharedPreferences prefs=context.getSharedPreferences("git_agent_oauth_device",0);
        String id=prefs.getString("id","");if(id.isEmpty()){id=UUID.randomUUID().toString();prefs.edit().putString("id",id).apply();}
        device=id;transport=OAuthSubscriptions::http;
    }
    /** Pure protocol fixture entry point: tests never connect to real accounts. */
    public OAuthSubscriptions(Transport transport){this.store=null;this.device="test-device";this.transport=transport;}
    public static boolean supports(String id){return OPENAI.equals(id)||CLINE.equals(id)||KIMI.equals(id)||GROK.equals(id)||app.githubble.mobile.network.BrowserSubscriptions.supports(id);}
    private Map<String,String> headers(String provider){
        Map<String,String> h=new LinkedHashMap<>();h.put("User-Agent","GitHubble-GitAgent/1.0");
        if(KIMI.equals(provider)){
            h.put("X-Msh-Platform","omnichat_android");h.put("X-Msh-Version","1.0.1");h.put("X-Msh-Device-Id",device);
            h.put("X-Msh-Device-Name",Build.MODEL==null?"Android":Build.MODEL);
            h.put("X-Msh-Device-Model",(Build.MANUFACTURER==null?"":Build.MANUFACTURER)+" "+(Build.MODEL==null?"Android":Build.MODEL));
            h.put("X-Msh-Os-Version","Android "+(Build.VERSION.RELEASE==null?"":Build.VERSION.RELEASE));
        }
        if(CLINE.equals(provider)){h.put("X-CLIENT-TYPE","githubble");h.put("X-Title","Git Agent");}
        return h;
    }
    private Reply call(String provider,String url,String method,JSONObject data,boolean form,JSONObject session)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Запрос отменён");
        validateUrl(url);
        if(GROK.equals(provider))validateGrokDestination(url,session!=null);
        Map<String,String> h=headers(provider);
        if(session!=null){
            String token=required(session,"access_token");
            if(CLINE.equals(provider)&&!token.startsWith("workos:"))token="workos:"+token;
            h.put("Authorization","Bearer "+token);
            if(GROK.equals(provider)){
                h.put("X-XAI-Token-Auth","xai-grok-cli");h.put("x-grok-client-version","1.0.45");
                h.put("User-Agent","xai-grok-workspace/1.0.45");h.put("x-grok-client-identifier","grok-shell");
                h.put("x-authenticateresponse","authenticate-response");
                h.put("Accept","text/event-stream, application/json");
            }
            if(OPENAI.equals(provider)){
                String account=session.optString("account_id");if(!account.isEmpty())h.put("ChatGPT-Account-ID",account);
                h.put("originator","githubble");h.put("session_id",UUID.randomUUID().toString());
                h.put("Accept","text/event-stream, application/json");
            }
        }
        return transport.send(url,method,data==null?null:form?form(data):data.toString(),form,h);
    }
    private JSONObject success(Reply reply,String provider)throws Exception{
        if(reply.status<200||reply.status>=300)throw new IOException(provider+" HTTP "+reply.status+" · "+errorCode(reply.body));
        return reply.json();
    }
    public DeviceLogin begin(String provider)throws Exception{
        String url,tokenEndpoint="";JSONObject body=new JSONObject();boolean form=true;
        if(OPENAI.equals(provider)){url="https://auth.openai.com/api/accounts/deviceauth/usercode";body.put("client_id",OPENAI_CLIENT);form=false;}
        else if(CLINE.equals(provider)){url="https://api.workos.com/user_management/authorize/device";body.put("client_id",CLINE_CLIENT);}
        else if(KIMI.equals(provider)){url="https://auth.kimi.ai/api/oauth/device_authorization";body.put("client_id",KIMI_CLIENT);}
        else if(GROK.equals(provider)){
            JSONObject discovery=success(call(provider,"https://auth.x.ai/.well-known/openid-configuration","GET",null,false,null),provider);
            url=xaiAuthEndpoint(required(discovery,"device_authorization_endpoint"));
            tokenEndpoint=xaiAuthEndpoint(required(discovery,"token_endpoint"));
            body.put("client_id",GROK_CLIENT).put("scope","openid profile email offline_access grok-cli:access api:access");
        }
        else throw new IllegalArgumentException("Неизвестное OAuth-подключение");
        JSONObject j=success(call(provider,url,"POST",body,form,null),provider);
        String code=j.optString("user_code",j.optString("usercode")),id=required(j,OPENAI.equals(provider)?"device_auth_id":"device_code");
        if(code.isEmpty())throw new IOException("OAuth не вернул пользовательский код");
        String browser=OPENAI.equals(provider)?"https://auth.openai.com/codex/device":j.optString("verification_uri_complete",j.optString("verification_uri"));
        validateBrowserUrl(browser);
        if(GROK.equals(provider))xaiAuthEndpoint(browser);
        return new DeviceLogin(provider,id,code,browser,System.currentTimeMillis()+Math.max(30,Math.min(900,j.optLong("expires_in",900)))*1000,
                Math.max(GROK.equals(provider)?3:2,Math.min(30,j.optLong("interval",5)))*1000,tokenEndpoint);
    }
    /** A null result means pending, never connected; slow_down increases the next interval. */
    public JSONObject poll(DeviceLogin login)throws Exception{
        if(System.currentTimeMillis()>=login.expires)throw new IOException("Код входа истёк. Начните вход заново.");
        String p=login.provider,url;
        if(!supports(p))throw new IllegalArgumentException("Неизвестное OAuth-подключение");
        boolean form=!OPENAI.equals(p);JSONObject b=new JSONObject();
        if(OPENAI.equals(p)){url="https://auth.openai.com/api/accounts/deviceauth/token";b.put("device_auth_id",login.deviceCode).put("user_code",login.userCode);}
        else if(GROK.equals(p)){url=xaiAuthEndpoint(login.tokenEndpoint);
            b.put("client_id",GROK_CLIENT).put("device_code",login.deviceCode).put("grant_type","urn:ietf:params:oauth:grant-type:device_code");}
        else{url=CLINE.equals(p)?"https://api.workos.com/user_management/authenticate":"https://auth.kimi.ai/api/oauth/token";
            b.put("client_id",CLINE.equals(p)?CLINE_CLIENT:KIMI_CLIENT).put("device_code",login.deviceCode).put("grant_type","urn:ietf:params:oauth:grant-type:device_code");}
        Reply r=call(p,url,"POST",b,form,null);JSONObject j;
        try{j=r.json();}catch(Exception invalid){if(OPENAI.equals(p)&&(r.status==403||r.status==404))return null;throw new IOException("Некорректный ответ OAuth · HTTP "+r.status);}
        String error=j.optString("error");
        if("access_denied".equals(error)||"expired_token".equals(error))throw new IOException("OAuth: "+error);
        if("slow_down".equals(error)){login.interval=Math.min(60000,login.interval+5000);return null;}
        if("authorization_pending".equals(error)||OPENAI.equals(p)&&(r.status==403||r.status==404)||KIMI.equals(p)&&r.status==428)return null;
        if(r.status<200||r.status>=300)throw new IOException("Вход отклонён · "+errorCode(r.body)+" (HTTP "+r.status+")");
        if(OPENAI.equals(p)){
            String verifier=required(j,"code_verifier"),challenge=required(j,"code_challenge");
            if(!pkceMatches(verifier,challenge))throw new IOException("OpenAI вернул несогласованные PKCE-данные");
            JSONObject token=new JSONObject().put("client_id",OPENAI_CLIENT).put("grant_type","authorization_code")
                .put("code",required(j,"authorization_code")).put("code_verifier",verifier).put("redirect_uri","https://auth.openai.com/deviceauth/callback");
            j=success(call(p,"https://auth.openai.com/oauth/token","POST",token,true,null),p);
        }else if(CLINE.equals(p)){
            JSONObject token=new JSONObject().put("accessToken",required(j,"access_token")).put("refreshToken",required(j,"refresh_token"));
            j=success(call(p,"https://api.cline.bot/api/v1/auth/register","POST",token,false,null),p);
        }
        JSONObject normalized=normalize(p,j,null);
        if(GROK.equals(p))normalized.put("token_endpoint",xaiAuthEndpoint(login.tokenEndpoint));
        return normalized;
    }
    public JSONObject refresh(String p,JSONObject old)throws Exception{
        if(!supports(p)||!p.equals(old.optString("provider")))throw new IOException("Сессия другого провайдера");
        if(old.optString("refresh_token").isEmpty())throw new IOException("Сессия не поддерживает обновление. Войдите заново.");
        JSONObject b=new JSONObject();String url;boolean form=true;
        if(CLINE.equals(p)){url="https://api.cline.bot/api/v1/auth/refresh";form=false;b.put("refreshToken",required(old,"refresh_token")).put("grantType","refresh_token");}
        else if(GROK.equals(p)){
            url=xaiAuthEndpoint(required(old,"token_endpoint"));
            b.put("client_id",GROK_CLIENT).put("grant_type","refresh_token").put("refresh_token",required(old,"refresh_token"));
        }
        else{url=OPENAI.equals(p)?"https://auth.openai.com/oauth/token":"https://auth.kimi.ai/api/oauth/token";
            b.put("client_id",OPENAI.equals(p)?OPENAI_CLIENT:KIMI_CLIENT).put("grant_type","refresh_token").put("refresh_token",required(old,"refresh_token"));}
        return normalize(p,success(call(p,url,"POST",b,form,null),p),old);
    }
    private JSONObject current(String p,String raw)throws Exception{
        if(!supports(p))throw new IllegalArgumentException("Неизвестное OAuth-подключение");
        JSONObject old=new JSONObject(raw);
        if(!p.equals(old.optString("provider")))throw new IOException("Сессия другого провайдера");
        if(old.optLong("expires_at")>System.currentTimeMillis()+60000)return old;
        JSONObject fresh=refresh(p,old);
        if(store!=null)store.replaceSubscription(old.optString("owner"),p,raw,fresh);
        return fresh;
    }
    public List<String> discover(String p,String raw)throws Exception{
        if(app.githubble.mobile.network.BrowserSubscriptions.supports(p))return app.githubble.mobile.network.BrowserSubscriptions.discover(p,raw,store);
        JSONObject session=current(p,raw);LinkedHashSet<String> ids=new LinkedHashSet<>();
        String url=OPENAI.equals(p)?"https://chatgpt.com/backend-api/codex/models?client_version=1.0.1":
            CLINE.equals(p)?"https://api.cline.bot/api/v1/models":GROK.equals(p)?GROK_BASE+"/models":"https://api.kimi.ai/coding/v1/models";
        if(CLINE.equals(p)){
            Object generic=null;JSONObject recommended=null;Exception failure=null;
            try{generic=success(call(p,url,"GET",null,false,session),p);}catch(InterruptedException e){throw e;}catch(Exception e){failure=e;}
            try{recommended=success(call(p,"https://api.cline.bot/api/v1/ai/cline/recommended-models","GET",null,false,session),p);}
            catch(InterruptedException e){throw e;}catch(Exception e){failure=e;}
            JSONObject groups=ClineModelGroups.classify(generic,recommended);
            for(String group:new String[]{"Free","Pass","Billing"})ClineModelGroups.extract(groups.opt(group),ids);
            if(store!=null&&!session.optString("owner").isEmpty())store.cacheModelGroups(session.getString("owner"),p,groups);
            if(ids.isEmpty()&&failure!=null)throw failure;
        }else extractModels(success(call(p,url,"GET",null,false,session),p),ids);
        if(GROK.equals(p))ids.removeIf(id->!id.toLowerCase(Locale.ROOT).startsWith("grok"));
        if(ids.isEmpty())throw new IOException("Сервер не вернул доступные модели. Подключение не объявлено готовым.");
        return new ArrayList<>(ids);
    }
    public String complete(String p,String model,String raw,JSONArray history,String project,String repository)throws Exception{
        if(app.githubble.mobile.network.BrowserSubscriptions.supports(p))return app.githubble.mobile.network.BrowserSubscriptions.complete(p,model,raw,history,project,repository,store);
        if(model==null||model.isBlank())throw new IOException("Не выбрана модель");
        JSONObject session=current(p,raw);JSONArray input=new JSONArray();
        for(int i=Math.max(0,history.length()-28);i<history.length();i++){
            JSONObject e=history.optJSONObject(i);if(e==null)continue;String role=e.optString("role");
            if(!"user".equals(role)&&!"agent".equals(role))continue;
            input.put(new JSONObject().put("role","agent".equals(role)?"assistant":"user").put("content",e.optString("body")));
        }
        String instructions="Вы помощник Git Agent. Отвечайте на языке пользователя. Не утверждайте, что GitHub-операция выполнена без подтверждения инструментов. Проект: "+project+". Репозиторий: "+repository;
        boolean responses=OPENAI.equals(p)||GROK.equals(p);
        JSONObject body=new JSONObject().put("model",model).put("stream",responses);String url;
        if(responses){url=GROK.equals(p)?GROK_BASE+"/responses":"https://chatgpt.com/backend-api/codex/responses";body.put("input",input).put("instructions",instructions).put("store",false);}
        else{url=CLINE.equals(p)?"https://api.cline.bot/api/v1/chat/completions":"https://api.kimi.ai/coding/v1/chat/completions";
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content",instructions));
            for(int i=0;i<input.length();i++)messages.put(input.opt(i));body.put("messages",messages);}
        Reply reply=call(p,url,"POST",body,false,session);
        if(reply.status<200||reply.status>=300)throw new IOException("Подписка HTTP "+reply.status+" · "+errorCode(reply.body));
        String text=responses?parseResponses(reply.body):OmniChatChatClient.chatOutput(reply.json());
        if(text.isBlank())throw new IOException("Модель подписки не вернула текст");return text;
    }
    public static JSONObject normalize(String p,JSONObject payload,JSONObject previous)throws Exception{
        JSONObject j=CLINE.equals(p)&&payload.optJSONObject("data")!=null?payload.getJSONObject("data"):payload;
        JSONObject out=previous==null?new JSONObject():new JSONObject(previous.toString());
        String access=j.optString(CLINE.equals(p)?"accessToken":"access_token");
        String refresh=j.optString(CLINE.equals(p)?"refreshToken":"refresh_token",out.optString("refresh_token"));
        if(!supports(p))throw new IOException("Неизвестный провайдер сессии");
        if(access.isEmpty()||!GROK.equals(p)&&refresh.isEmpty())throw new IOException("OAuth не вернул полный набор токенов");
        out.put("provider",p).put("access_token",access).put("refresh_token",refresh);
        long expires=System.currentTimeMillis()+Math.max(1,j.optLong("expires_in",3600))*1000;
        if(CLINE.equals(p)&&!j.optString("expiresAt").isEmpty())expires=java.time.Instant.parse(j.getString("expiresAt")).toEpochMilli();
        if(OPENAI.equals(p)){
            JSONObject claims=jwt(j.optString("id_token")),accessClaims=jwt(access);
            JSONObject auth=claims.optJSONObject("https://api.openai.com/auth");
            if(auth==null)auth=accessClaims.optJSONObject("https://api.openai.com/auth");
            if(auth!=null){out.put("account_id",auth.optString("chatgpt_account_id",out.optString("account_id")));out.put("plan",auth.optString("chatgpt_plan_type",out.optString("plan")));}
            out.put("identity",claims.optString("email",out.optString("identity")));
            if(accessClaims.optLong("exp")>0)expires=accessClaims.getLong("exp")*1000;
        }
        if(GROK.equals(p)){
            JSONObject identity=jwt(j.optString("id_token"));
            out.put("identity",identity.optString("email",identity.optString("sub",out.optString("identity"))));
        }
        if(CLINE.equals(p)&&j.optJSONObject("userInfo")!=null)out.put("identity",j.getJSONObject("userInfo").optString("email"));
        return out.put("expires_at",expires);
    }
    public static void extractModels(Object value,Set<String> ids){
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)extractModels(a.opt(i),ids);}
        else if(value instanceof JSONObject){JSONObject j=(JSONObject)value;
            if(j.has("data")||j.has("models")){extractModels(j.opt("data"),ids);extractModels(j.opt("models"),ids);return;}
            if(!j.optBoolean("supported_in_api",true)||"hide".equals(j.optString("visibility"))||"none".equals(j.optString("visibility")))return;
            String id=j.optString("slug",j.optString("id",j.optString("model",j.optString("name"))));
            if(!id.isEmpty()&&id.length()<=160)ids.add(id);
        }else if(value instanceof String&&!((String)value).isBlank()&&((String)value).length()<=160)ids.add((String)value);
    }
    public static String parseResponses(String raw)throws Exception{
        if(raw.trim().startsWith("{")){
            JSONObject result=new JSONObject(raw);String status=result.optString("status");
            if(result.has("error")&&!result.isNull("error")||!status.isEmpty()&&!"completed".equals(status))
                throw new IOException("Ответ подписки не завершён успешно");
            String text=OmniChatChatClient.responseOutput(result);if(!text.isEmpty())return text;
        }
        StringBuilder deltas=new StringBuilder();JSONObject completed=null;
        String normalized=raw.replace("\r\n","\n");
        for(String frame:normalized.split("\n\n")){
            StringBuilder data=new StringBuilder();for(String line:frame.split("\n"))if(line.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(line.substring(5).trim());}
            if(data.length()==0||"[DONE]".contentEquals(data))continue;
            JSONObject event=new JSONObject(data.toString());String type=event.optString("type");
            if("error".equals(type)||"response.failed".equals(type)||"response.incomplete".equals(type))throw new IOException("Ответ подписки завершился ошибкой");
            if("response.output_text.delta".equals(type))deltas.append(event.optString("delta"));
            if("response.completed".equals(type))completed=event.optJSONObject("response");
        }
        if(completed==null)throw new IOException("Поток подписки прерван до response.completed");
        if(completed.has("error")&&!completed.isNull("error")||
                !completed.optString("status").isEmpty()&&!"completed".equals(completed.optString("status")))
            throw new IOException("Ответ подписки не завершён успешно");
        String result=OmniChatChatClient.responseOutput(completed);if(result.isEmpty())result=deltas.toString();
        if(result.isEmpty())throw new IOException("Подписка не вернула текст");return result;
    }
    public static boolean pkceMatches(String verifier,String expected)throws Exception{
        String actual=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII));
    }
    public static void validateUrl(String url)throws Exception{
        URI uri=new URI(url);String host=uri.getHost();
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443||
                !Arrays.asList("auth.openai.com","chatgpt.com","api.workos.com","api.cline.bot","auth.kimi.ai","api.kimi.ai","auth.x.ai","cli-chat-proxy.grok.com").contains(host))
            throw new IOException("Запрещённый адрес OAuth/подписки");
    }
    public static void validateBrowserUrl(String url)throws Exception{
        URI uri=new URI(url);String h=uri.getHost();
        if(!"https".equals(uri.getScheme())||h==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443||
                !(h.equals("auth.x.ai")||h.equals("auth.openai.com")||h.endsWith(".workos.com")||h.endsWith(".authkit.app")||h.endsWith(".cline.bot")||h.equals("cline.bot")||h.equals("auth.kimi.ai")||h.endsWith(".kimi.ai")||h.equals("kimi.com")||h.endsWith(".kimi.com")))
            throw new IOException("OAuth вернул неизвестный адрес подтверждения");
    }
    /** Discovery cannot redirect a code or refresh token to another provider's allowed host. */
    private static String xaiAuthEndpoint(String url)throws Exception{
        validateUrl(url);
        if(!"auth.x.ai".equals(new URI(url).getHost()))throw new IOException("Неизвестный адрес авторизации xAI");
        return url;
    }
    private static void validateGrokDestination(String url,boolean authenticated)throws Exception{
        String host=new URI(url).getHost();
        if(!(authenticated?"cli-chat-proxy.grok.com":"auth.x.ai").equals(host))
            throw new IOException("Адрес не соответствует подключению Grok");
    }
    private static JSONObject jwt(String token){try{return new JSONObject(new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),StandardCharsets.UTF_8));}catch(Exception e){return new JSONObject();}}
    private static String required(JSONObject j,String field)throws IOException{String s=j.optString(field);if(s.isEmpty())throw new IOException("В ответе OAuth нет "+field);return s;}
    private static String form(JSONObject j)throws Exception{StringBuilder s=new StringBuilder();Iterator<String> keys=j.keys();while(keys.hasNext()){String k=keys.next();if(s.length()>0)s.append('&');s.append(URLEncoder.encode(k,"UTF-8")).append('=').append(URLEncoder.encode(j.getString(k),"UTF-8"));}return s.toString();}
    private static String errorCode(String raw){try{JSONObject j=new JSONObject(raw);Object e=j.opt("error");String code=e instanceof JSONObject?((JSONObject)e).optString("code"):e instanceof String?(String)e:"request_failed";return code.matches("[a-zA-Z0-9_:-]{1,80}")?code:"request_failed";}catch(Exception e){return "request_failed";}}
    private static Reply http(String url,String method,String body,boolean form,Map<String,String> headers)throws Exception{
        validateUrl(url);HttpURLConnection c=app.githubble.mobile.network.AiNetwork.open(url);
        c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(90000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        c.setRequestProperty("Accept","application/json");for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
        try{
            if(body!=null){byte[] b=body.getBytes(StandardCharsets.UTF_8);if(b.length>160*1024)throw new IOException("Слишком большой контекст");c.setDoOutput(true);c.setRequestProperty("Content-Type",form?"application/x-www-form-urlencoded":"application/json; charset=utf-8");c.setFixedLengthStreamingMode(b.length);try(OutputStream out=c.getOutputStream()){out.write(b);}}
            int status=c.getResponseCode();ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=status>=200&&status<300?c.getInputStream():c.getErrorStream()){
                if(in!=null){byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(bytes.size()+n>4*1024*1024)throw new IOException("Слишком большой ответ");bytes.write(b,0,n);}}}
            return new Reply(status,bytes.toString("UTF-8"));
        }finally{c.disconnect();}
    }
}

