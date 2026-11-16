package app.githubble.mobile.mirror;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import androidx.work.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cloud-neutral registry synchronization over Android's Storage Access Framework.
 *
 * The USER chooses one JSON file from any installed DocumentsProvider (Google Drive,
 * OneDrive, and other providers where supported). No Google/Microsoft/Yandex OAuth
 * credentials, API keys, repo contents or GitHub tokens are transferred by this layer.
 */
public final class MirrorCloudStore {
    private static final String PREFS="githubble_mirror_cloud";
    private static final String KEY_URI="document_uri";
    private static final String KEY_LAST="last_sync";
    private static final String KEY_ERROR="last_error";
    private static final String KEY_PENDING="pending";
    private static final String FORMAT="githubble-mirror-links";
    private static final int MAX_BYTES=2*1024*1024;
    private static final int MAX_LINKS=10000;
    private static final String PERIODIC="githubble-mirror-cloud-periodic";
    private static final String IMMEDIATE="githubble-mirror-cloud-now";
    private final Context app;
    private final ContentResolver resolver;
    private final SharedPreferences prefs;
    private final MirrorLinkRegistry registry;

    public static final class Result {
        public int imported,exported,merged;
        public boolean wrote;
        public long synchronizedAt;
    }

    public MirrorCloudStore(Context context){
        app=context.getApplicationContext();
        resolver=app.getContentResolver();
        prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        registry=new MirrorLinkRegistry(app);
    }
    public boolean connected(){return !prefs.getString(KEY_URI,"").isEmpty();}
    public String location(){
        if(!connected())return "";
        try{
            Uri uri=Uri.parse(prefs.getString(KEY_URI,""));
            return uri.getAuthority()==null?"Облачный документ":uri.getAuthority();
        }catch(Exception ignored){return "Облачный документ";}
    }
    public long lastSync(){return prefs.getLong(KEY_LAST,0L);}
    public String lastError(){return prefs.getString(KEY_ERROR,"");}
    public boolean pending(){return prefs.getBoolean(KEY_PENDING,false);}

    public static List<MirrorLinkRegistry.Link> decode(String input)throws Exception{
        if(input==null||input.trim().isEmpty())return new ArrayList<>();
        JSONObject root=new JSONObject(input);
        if(!FORMAT.equals(root.optString("format","")) ||root.optInt("schema",-1)!=1)
            throw new IllegalArgumentException("Выбранный документ не является реестром GitHubble Mirror Manager v1");
        JSONArray entries=root.optJSONArray("links");
        if(entries==null||entries.length()>MAX_LINKS)
            throw new IllegalArgumentException("Повреждённый или слишком большой облачный реестр");
        List<MirrorLinkRegistry.Link> links=new ArrayList<>();
        java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int i=0;i<entries.length();i++){
            JSONObject item=entries.optJSONObject(i);
            if(item==null)throw new IllegalArgumentException("Повреждённая запись облачного реестра");
            MirrorLinkRegistry.Link link=MirrorLinkRegistry.Link.fromJson(item);
            if(!MirrorLinkRegistry.pairId(link.left,link.right).equals(link.id)
                    ||(!link.left.equalsIgnoreCase(link.original)
                        &&!link.right.equalsIgnoreCase(link.original))
                    ||!ids.add(link.id))
                throw new IllegalArgumentException("Некорректная или повторяющаяся связанная пара");
            links.add(link);
        }
        return links;
    }

    public static String encode(List<MirrorLinkRegistry.Link> links){
        JSONArray array=new JSONArray();
        ArrayList<MirrorLinkRegistry.Link> ordered=new ArrayList<>(links);
        ordered.sort((a,b)->a.id.compareTo(b.id));
        for(MirrorLinkRegistry.Link link:ordered)array.put(link.json());
        JSONObject root=new JSONObject();
        try{
            root.put("format",FORMAT);
            root.put("schema",1);
            root.put("exportedAt",System.currentTimeMillis());
            root.put("links",array);
        }catch(Exception e){throw new IllegalStateException(e);}
        return root.toString();
    }

    public static boolean equalRegistry(List<MirrorLinkRegistry.Link> a,List<MirrorLinkRegistry.Link> b){
        if(a.size()!=b.size())return false;
        Map<String,MirrorLinkRegistry.Link> map=new HashMap<>();
        for(MirrorLinkRegistry.Link link:a)map.put(link.id,link);
        for(MirrorLinkRegistry.Link link:b){
            MirrorLinkRegistry.Link x=map.get(link.id);
            if(x==null||!x.left.equalsIgnoreCase(link.left)
                    ||!x.right.equalsIgnoreCase(link.right)
                    ||!x.original.equalsIgnoreCase(link.original)
                    ||!x.copyJobId.equals(link.copyJobId)
                    ||x.createdAt!=link.createdAt
                    ||x.updatedAt!=link.updatedAt)return false;
        }
        return true;
    }

    private List<MirrorLinkRegistry.Link> read(Uri uri)throws Exception{
        try(InputStream input=resolver.openInputStream(uri)){
            if(input==null)throw new java.io.IOException("Провайдер не разрешает чтение облачного документа");
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int size=0,n;
            while((n=input.read(buffer))!=-1){
                size+=n;
                if(size>MAX_BYTES)throw new java.io.IOException("Облачный файл больше 2 МБ");
                output.write(buffer,0,n);
            }
            return decode(output.toString(StandardCharsets.UTF_8.name()));
        }
    }

    private void write(Uri uri,List<MirrorLinkRegistry.Link> links)throws Exception{
        byte[] bytes=encode(links).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new java.io.IOException("Реестр превышает 2 МБ");
        // "wt" requests truncation, avoiding trailing bytes from an older larger document.
        try(OutputStream out=resolver.openOutputStream(uri,"wt")){
            if(out==null)throw new java.io.IOException("Провайдер не разрешает запись облачного документа");
            out.write(bytes);out.flush();
        }
    }

    private boolean hasPersistentPermission(Uri uri){
        for(UriPermission permission:resolver.getPersistedUriPermissions())
            if(uri.equals(permission.getUri())
                    &&permission.isReadPermission()&&permission.isWritePermission())
                return true;
        return false;
    }

    /** Called only after explicit ACTION_CREATE_DOCUMENT or ACTION_OPEN_DOCUMENT selection. */
    public synchronized Result attach(Uri uri,int resultFlags)throws Exception{
        if(uri==null||!"content".equals(uri.getScheme()))
            throw new IllegalArgumentException("Выберите документ через системный проводник Android");
        int allowed=resultFlags&(Intent.FLAG_GRANT_READ_URI_PERMISSION
                |Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if((allowed&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                !=(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            throw new SecurityException("Облачный провайдер должен разрешить чтение И запись документа");
        resolver.takePersistableUriPermission(uri,allowed);
        if(!hasPersistentPermission(uri))
            throw new SecurityException("Провайдер не выдал постоянное разрешение на чтение и запись");
        Result result;
        try{result=syncUri(uri);}
        catch(Exception error){
            rememberError(error);
            if(!uri.toString().equals(prefs.getString(KEY_URI,"")))release(uri);
            throw error; // keep previous working connection intact
        }
        String previous=prefs.getString(KEY_URI,"");
        prefs.edit().putString(KEY_URI,uri.toString()).putBoolean(KEY_PENDING,false)
                .putLong(KEY_LAST,result.synchronizedAt).putString(KEY_ERROR,"").commit();
        if(!previous.isEmpty()&&!previous.equals(uri.toString()))
            release(Uri.parse(previous));
        schedulePeriodic();
        return result;
    }

    private void release(Uri uri){
        try{
            resolver.releasePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }catch(Exception ignored){}
    }

    public synchronized Result sync()throws Exception{
        String location=prefs.getString(KEY_URI,"");
        if(location.isEmpty())throw new IllegalStateException("Сначала подключите облачный JSON-файл");
        try{
            Uri uri=Uri.parse(location);
            if(!hasPersistentPermission(uri))throw new SecurityException("Облачный документ недоступен. Подключите его заново.");
            Result done=syncUri(uri);
            prefs.edit().putBoolean(KEY_PENDING,false).putLong(KEY_LAST,done.synchronizedAt)
                    .putString(KEY_ERROR,"").commit();
            return done;
        }catch(Exception error){
            rememberError(error);throw error;
        }
    }

    /**
     * Union merge, no deletions. SAF provider writes cannot offer cross-device compare-and-swap;
     * any concurrent editors must eventually resync. Read-before-write narrows this window.
     */
    private Result syncUri(Uri uri)throws Exception{
        List<MirrorLinkRegistry.Link> remote=read(uri);
        Result result=new Result();
        result.imported=registry.mergeRemote(remote);
        List<MirrorLinkRegistry.Link> merged=registry.list();
        result.merged=merged.size();
        result.exported=Math.max(0,merged.size()-remote.size());
        if(!equalRegistry(merged,remote)){
            // Incorporate changes arriving between first read and writing.
            List<MirrorLinkRegistry.Link> latest=read(uri);
            if(!equalRegistry(latest,remote)){
                result.imported+=registry.mergeRemote(latest);
                merged=registry.list();
                result.merged=merged.size();
            }
            write(uri,merged);
            // Confirm that the provider can read back the newly written content.
            List<MirrorLinkRegistry.Link> verify=read(uri);
            if(!equalRegistry(verify,merged))
                throw new java.io.IOException("Не удалось проверить запись облачного реестра");
            result.wrote=true;
        }
        result.synchronizedAt=System.currentTimeMillis();
        return result;
    }

    public void queue(){
        if(!connected())return;
        prefs.edit().putBoolean(KEY_PENDING,true).apply();
        try{
            Constraints constraints=new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build();
            OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(MirrorCloudWorker.class)
                    .setConstraints(constraints)
                    .setInitialDelay(10,TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
                    .build();
            WorkManager.getInstance(app).enqueueUniqueWork(
                    IMMEDIATE,ExistingWorkPolicy.REPLACE,work);
        }catch(RuntimeException ignored){
            // The local registry is still saved; manual cloud sync remains available.
        }
    }

    public void schedulePeriodic(){
        if(!connected())return;
        try{
            Constraints constraints=new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest work=new PeriodicWorkRequest.Builder(
                    MirrorCloudWorker.class,6,TimeUnit.HOURS)
                    .setConstraints(constraints).build();
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                    PERIODIC,ExistingPeriodicWorkPolicy.KEEP,work);
        }catch(RuntimeException ignored){
            // No fatal effect on local mirroring if background work is unavailable.
        }
    }

    public synchronized void disconnect(){
        String location=prefs.getString(KEY_URI,"");
        WorkManager wm=WorkManager.getInstance(app);
        wm.cancelUniqueWork(IMMEDIATE);wm.cancelUniqueWork(PERIODIC);
        prefs.edit().remove(KEY_URI).remove(KEY_LAST).remove(KEY_ERROR)
                .remove(KEY_PENDING).commit();
        if(!location.isEmpty())release(Uri.parse(location));
    }

    private void rememberError(Exception e){
        String type=e instanceof SecurityException?"Доступ к облачному документу отклонён":
                e instanceof java.io.IOException?"Ошибка чтения или записи облачного документа":
                "Проверьте формат облачного реестра";
        prefs.edit().putString(KEY_ERROR,type).putBoolean(KEY_PENDING,true).commit();
    }
}
