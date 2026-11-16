package app.githubble.mobile.mirror;

import android.accounts.Account;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.common.api.Scope;
import app.githubble.mobile.agent.ConnectionsCloud;
import app.githubble.mobile.agent.ConnectionsSnapshot;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native Google Drive OAuth storage for linked repository metadata.
 * Creates an ordinary, visible My Drive/GitHubble/mirror-registry.json.
 * Uses the least-privilege drive.file scope; no token is persisted on disk.
 */
public final class GoogleMirrorCloudStore {
    private static final String PREFS="githubble_mirror_google_v1";
    private static final String KEY_SUB="google_sub";
    private static final String KEY_EMAIL="google_email";
    private static final String KEY_FOLDER="folder_id";
    private static final String KEY_FILE="file_id";
    private static final String KEY_SYNC="last_sync";
    private static final String KEY_PENDING="pending";
    private static final String KEY_ERROR="last_error";
    private static final String PERIODIC="githubble-mirror-google-periodic";
    private static final String ONCE="githubble-mirror-google-once";
    private static final String GOOGLE_DRIVE="https://www.googleapis.com/drive/v3/files";
    private static final String GOOGLE_UPLOAD="https://www.googleapis.com/upload/drive/v3/files";
    private static final String USERINFO="https://www.googleapis.com/oauth2/v3/userinfo";
    private static final String FOLDER="GitHubble";
    private static final String REGISTRY="mirror-registry.json";
    private static final String SETTINGS="settings.json";
    private static final String KEY_SETTINGS_ERROR="settings_error";
    private static final int MAX_BYTES=2*1024*1024;
    private static final MediaType JSON=MediaType.parse("application/json; charset=utf-8");

    private final Context app;
    private final SharedPreferences prefs;
    private final MirrorLinkRegistry registry;
    private final OkHttpClient http=new OkHttpClient.Builder()
            .connectTimeout(20,TimeUnit.SECONDS)
            .readTimeout(30,TimeUnit.SECONDS)
            .writeTimeout(30,TimeUnit.SECONDS).build();

    public static final class Result {
        public String email="";
        public int total, imported;
        public boolean uploaded;
        public long synchronizedAt;
        /** What happened to settings.json (connections), in words. */
        public String settings="";
    }

    private static final class User {
        String sub,email;
        User(String sub,String email){this.sub=sub;this.email=email;}
    }

    public GoogleMirrorCloudStore(Context context){
        app=context.getApplicationContext();
        prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        registry=new MirrorLinkRegistry(app);
    }

    public static AuthorizationRequest request(boolean chooseAccount,String accountEmail){
        AuthorizationRequest.Builder b=AuthorizationRequest.builder()
                .setRequestedScopes(Arrays.asList(
                        new Scope("https://www.googleapis.com/auth/drive.file"),
                        new Scope("https://www.googleapis.com/auth/userinfo.email")));
        if(chooseAccount)b.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT);
        else if(accountEmail!=null&&!accountEmail.trim().isEmpty())
            b.setAccount(new Account(accountEmail,"com.google"));
        return b.build();
    }

    public boolean connected(){
        return !prefs.getString(KEY_SUB,"").isEmpty()
                && !prefs.getString(KEY_EMAIL,"").isEmpty();
    }
    public String email(){return prefs.getString(KEY_EMAIL,"");}
    public long lastSync(){return prefs.getLong(KEY_SYNC,0);}
    public boolean pending(){return prefs.getBoolean(KEY_PENDING,false);}
    public String error(){return prefs.getString(KEY_ERROR,"");}
    public String settingsError(){return prefs.getString(KEY_SETTINGS_ERROR,"");}
    public String folderLink(){
        String id=prefs.getString(KEY_FOLDER,"");
        return id.isEmpty()?"":"https://drive.google.com/drive/folders/"+id;
    }
    public void needsAuthorization(){
        prefs.edit().putBoolean(KEY_PENDING,true)
                .putString(KEY_ERROR,"Google требует повторного подтверждения доступа").apply();
    }

    private static String enc(String raw){
        try{return URLEncoder.encode(raw,"UTF-8");}
        catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);}
    }
    private String request(String method,String url,String token,String body)throws Exception{
        if(token==null||token.trim().isEmpty())throw new SecurityException("Нет Google OAuth access token");
        Request.Builder builder=new Request.Builder().url(url)
                .header("Authorization","Bearer "+token)
                .header("Accept","application/json");
        if("POST".equals(method))builder.post(RequestBody.create(body,JSON));
        else if("PATCH".equals(method))builder.patch(RequestBody.create(body,JSON));
        else builder.get();
        try(Response response=http.newCall(builder.build()).execute()){
            if(!response.isSuccessful()){
                int status=response.code();
                if(status==401||status==403)
                    throw new SecurityException("Google Drive HTTP "+status
                            +": проверь разрешение Google, Drive API и OAuth-клиент GitHubble");
                if(status==404)throw new IOException("Файл Google Drive не найден (404)");
                throw new IOException("Google Drive HTTP "+status+" — операция "+method+" не завершена");
            }
            if(response.body()==null)return "";
            String result=response.body().string();
            if(result.length()>MAX_BYTES)
                throw new IOException("Ответ Google Drive превышает 2 МБ");
            return result;
        }
    }
    private JSONObject json(String method,String url,String token,String body)throws Exception{
        return new JSONObject(request(method,url,token,body));
    }

    private User user(String token)throws Exception{
        JSONObject who=json("GET",USERINFO,token,null);
        String sub=who.optString("sub","");
        String email=who.optString("email","");
        if(!sub.matches("[0-9]{5,64}")||email.isEmpty())
            throw new SecurityException("Google не предоставил подтверждённую личность аккаунта");
        if(!who.optBoolean("email_verified",false))
            throw new SecurityException("Google-аккаунт не подтвердил электронную почту");
        return new User(sub,email);
    }

    private String find(String token,String parent,String name,String mime)throws Exception{
        String q="name = '"+name.replace("'","\\'")+"' and mimeType = '"+mime
                +"' and '"+parent+"' in parents and trashed = false";
        String url=GOOGLE_DRIVE+"?q="+enc(q)+"&fields="
                +enc("nextPageToken,files(id,name,mimeType,parents),incompleteSearch")
                +"&pageSize=100";
        JSONObject found=json("GET",url,token,null);
        JSONArray files=found.optJSONArray("files");
        if(found.optBoolean("incompleteSearch",false)||found.has("nextPageToken"))
            throw new IOException("Поиск в Google Drive неполный; повторите подключение");
        if(files==null||files.length()==0)return "";
        if(files.length()!=1)
            throw new IOException("Несколько файлов GitHubble с одинаковым именем в Google Drive. "
                    +"Удалите дубликаты вручную, чтобы исключить неправильную синхронизацию.");
        return files.getJSONObject(0).getString("id");
    }
    private String create(String token,JSONObject metadata)throws Exception{
        JSONObject result=json("POST",GOOGLE_DRIVE+"?fields=id",token,metadata.toString());
        String id=result.optString("id","");
        if(id.isEmpty())throw new IOException("Google Drive не вернул ID созданного файла");
        return id;
    }
    private String folder(String token)throws Exception{
        String id=find(token,"root",FOLDER,"application/vnd.google-apps.folder");
        if(!id.isEmpty())return id;
        return create(token,new JSONObject()
                .put("name",FOLDER)
                .put("mimeType","application/vnd.google-apps.folder")
                .put("parents",new JSONArray().put("root"))
                .put("appProperties",new JSONObject().put("application","GitHubble")));
    }
    private String file(String token,String folderId)throws Exception{
        String id=find(token,folderId,REGISTRY,"application/json");
        if(!id.isEmpty())return id;
        return create(token,new JSONObject()
                .put("name",REGISTRY)
                .put("mimeType","application/json")
                .put("parents",new JSONArray().put(folderId))
                .put("appProperties",new JSONObject().put("application","GitHubble")
                        .put("type","mirror-links")));
    }
    private List<MirrorLinkRegistry.Link> remote(String token,String fileId)throws Exception{
        String body=request("GET",GOOGLE_DRIVE+"/"+enc(fileId)+"?alt=media",token,null);
        return MirrorCloudStore.decode(body);
    }
    private void upload(String token,String fileId,List<MirrorLinkRegistry.Link> links)throws Exception{
        String payload=MirrorCloudStore.encode(links);
        if(payload.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IOException("Реестр зеркал превысил 2 МБ");
        request("PATCH",GOOGLE_UPLOAD+"/"+enc(fileId)+"?uploadType=media&fields=id",
                token,payload);
    }

    private String settingsFile(String token,String folderId)throws Exception{
        String id=find(token,folderId,SETTINGS,"application/json");
        if(!id.isEmpty())return id;
        return create(token,new JSONObject()
                .put("name",SETTINGS)
                .put("mimeType","application/json")
                .put("parents",new JSONArray().put(folderId))
                .put("appProperties",new JSONObject().put("application","GitHubble")
                        .put("type","settings")));
    }

    /**
     * Keeps connections (services, endpoints, models, favourites) in Drive/GitHubble/settings.json.
     * API keys never enter the file. A failure here is reported but never breaks the mirror registry sync.
     */
    private String syncSettings(String token,String folderId,boolean first){
        try{
            String fileId=settingsFile(token,folderId);
            String url=GOOGLE_DRIVE+"/"+enc(fileId)+"?alt=media";
            ConnectionsCloud connections=new ConnectionsCloud(app);
            ConnectionsCloud.Plan plan=connections.reconcile(request("GET",url,token,null),first);
            if(plan.payload!=null){
                request("PATCH",GOOGLE_UPLOAD+"/"+enc(fileId)+"?uploadType=media&fields=id",token,plan.payload);
                ConnectionsSnapshot back=ConnectionsSnapshot.parse(request("GET",url,token,null));
                if(back==null||!back.sameContent(ConnectionsSnapshot.parse(plan.payload)))
                    throw new IOException("Google Drive не подтвердил запись settings.json");
            }
            connections.commit(plan);
            prefs.edit().remove(KEY_SETTINGS_ERROR).apply();
            return connections.lastNote();
        }catch(IllegalArgumentException foreign){
            String message=foreign.getMessage()==null?"Некорректный settings.json":foreign.getMessage();
            prefs.edit().putString(KEY_SETTINGS_ERROR,message).apply();
            return message;
        }catch(Exception problem){
            String message="Настройки не синхронизированы: "+(problem instanceof SecurityException
                    ?"нет доступа к Google Drive":"ошибка сети или Google Drive");
            prefs.edit().putString(KEY_SETTINGS_ERROR,message).apply();
            return message;
        }
    }

    /** Queues a background run only when connections changed since the last upload. */
    public void queueIfSettingsChanged(){
        if(!connected())return;
        try{if(new ConnectionsCloud(app).dirty())queue();}catch(RuntimeException ignored){}
    }

    /** Triggered by a user-consented Play Services authorization response. */
    public synchronized Result connect(String token)throws Exception{
        User selected=user(token);
        // Do not replace an existing account until the selected Google Drive is fully ready.
        try{
            Result result=syncWith(token,selected);
            String folder=resultHolderFolder;
            String file=resultHolderFile;
            if(!prefs.edit().putString(KEY_SUB,selected.sub)
                    .putString(KEY_EMAIL,selected.email)
                    .putString(KEY_FOLDER,folder)
                    .putString(KEY_FILE,file)
                    .putLong(KEY_SYNC,result.synchronizedAt)
                    .putBoolean(KEY_PENDING,false)
                    .putString(KEY_ERROR,"").commit())
                throw new IOException("Не удалось сохранить настройки Google Drive");
            schedule();
            result.settings=syncSettings(token,folder,true);
            return result;
        }catch(Exception error){
            remember(error);
            throw error;
        }
    }

    // Used only while holding the synchronized instance lock.
    private String resultHolderFolder="",resultHolderFile="";

    private Result syncWith(String token,User selected)throws Exception{
        String folderId=folder(token);
        String fileId=file(token,folderId);
        String remoteText=request("GET",GOOGLE_DRIVE+"/"+enc(fileId)+"?alt=media",token,null);
        boolean uninitialized=remoteText.trim().isEmpty();
        List<MirrorLinkRegistry.Link> before=MirrorCloudStore.decode(remoteText);
        Result outcome=new Result();
        outcome.email=selected.email;
        outcome.imported=registry.mergeRemote(before);
        List<MirrorLinkRegistry.Link> local=registry.list();
        outcome.total=local.size();
        if(!MirrorCloudStore.equalRegistry(local,before)||uninitialized){
            // Read again to narrow the concurrent-writer window.
            List<MirrorLinkRegistry.Link> newer=remote(token,fileId);
            if(!MirrorCloudStore.equalRegistry(newer,before)){
                outcome.imported+=registry.mergeRemote(newer);
                local=registry.list();
                outcome.total=local.size();
            }
            // Empty registries still initialize the visible JSON document.
            upload(token,fileId,local);
            if(!MirrorCloudStore.equalRegistry(local,remote(token,fileId)))
                throw new IOException("Google Drive не подтвердил запись реестра");
            outcome.uploaded=true;
        }
        resultHolderFolder=folderId;
        resultHolderFile=fileId;
        outcome.synchronizedAt=System.currentTimeMillis();
        return outcome;
    }

    public synchronized Result sync(String token)throws Exception{
        if(!connected())throw new IllegalStateException("Google Drive ещё не подключён");
        try{
            User current=user(token);
            if(!prefs.getString(KEY_SUB,"").equals(current.sub))
                throw new SecurityException("Получен Google-токен ДРУГОГО аккаунта. "
                        +"Открой настройки Mirror Manager и выбери подключённый Google-аккаунт.");
            Result outcome=syncWith(token,current);
            prefs.edit().putString(KEY_FOLDER,resultHolderFolder)
                    .putString(KEY_FILE,resultHolderFile)
                    .putLong(KEY_SYNC,outcome.synchronizedAt)
                    .putBoolean(KEY_PENDING,false).putString(KEY_ERROR,"").commit();
            outcome.settings=syncSettings(token,resultHolderFolder,false);
            return outcome;
        }catch(Exception error){
            remember(error);throw error;
        }
    }

    public void queue(){
        if(!connected())return;
        prefs.edit().putBoolean(KEY_PENDING,true).apply();
        try{
            Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(GoogleMirrorCloudWorker.class)
                    .setConstraints(c).setInitialDelay(15,TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
            WorkManager.getInstance(app).enqueueUniqueWork(ONCE,ExistingWorkPolicy.REPLACE,work);
        }catch(RuntimeException ignored){}
    }
    public void schedule(){
        if(!connected())return;
        try{
            Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest work=new PeriodicWorkRequest.Builder(
                    GoogleMirrorCloudWorker.class,1,TimeUnit.HOURS)
                    .setConstraints(c).build();
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                    PERIODIC,ExistingPeriodicWorkPolicy.KEEP,work);
        }catch(RuntimeException ignored){}
    }
    public synchronized void disconnect(){
        try{
            WorkManager.getInstance(app).cancelUniqueWork(ONCE);
            WorkManager.getInstance(app).cancelUniqueWork(PERIODIC);
        }catch(RuntimeException ignored){}
        prefs.edit().clear().commit();
        // Don't revoke Google's unrelated OAuth scopes or delete the user's Drive folder.
    }
    private void remember(Exception error){
        String message=error instanceof SecurityException
                ?"Повторно разрешите доступ GitHubble к выбранному Google Drive"
                :"Сбой синхронизации Google Drive. Проверьте подключение и доступ к файлу.";
        prefs.edit().putBoolean(KEY_PENDING,true).putString(KEY_ERROR,message).apply();
    }
}
