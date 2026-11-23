package app.githubble.mobile.repository;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.mirror.MirrorPolicy;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubSession;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Restores a portable ZIP into a NEW, private GitHub repository only.
 *
 * The source repo may be deleted: the runner reads the ZIP staged as a temporary
 * private Release asset on the destination. Nothing in an existing user repo
 * is overwritten. Remote mutations are never automatically retried.
 */
public final class FullRepositoryRestoreController {
    private static final String API="https://api.github.com/repos/";
    private static final long MAX_UPLOAD=1_950_000_000L;
    private final Context context;
    private final GitHubAccountStore accounts;
    private final SharedPreferences prefs;
    public interface Progress {void onProgress(int percentage,String message);}

    public FullRepositoryRestoreController(Context c,GitHubAccountStore accounts){
        context=c.getApplicationContext();this.accounts=accounts;
        prefs=context.getSharedPreferences("githubble_full_restore",Context.MODE_PRIVATE);
    }

    public static final class Job {
        public String id="",target="",accountId="",source="",sourceBranch="",sourceSha="";
        public String bootstrapBranch="",bootstrapSha="",archiveSha="";
        public String state="preparing",stage="Сверка информации",error="",runUrl="";
        public int percent;
        public long runId,assetId,releaseId;
        public JSONObject json()throws Exception {
            return new JSONObject().put("id",id).put("target",target)
                    .put("accountId",accountId).put("source",source)
                    .put("sourceBranch",sourceBranch).put("sourceSha",sourceSha)
                    .put("bootstrapBranch",bootstrapBranch).put("bootstrapSha",bootstrapSha)
                    .put("archiveSha",archiveSha).put("state",state)
                    .put("stage",stage).put("error",error).put("runUrl",runUrl)
                    .put("percent",percent).put("runId",runId)
                    .put("assetId",assetId).put("releaseId",releaseId);
        }
        static Job from(JSONObject v){
            Job j=new Job();
            j.id=v.optString("id");j.target=v.optString("target");
            j.accountId=v.optString("accountId");j.source=v.optString("source");
            j.sourceBranch=v.optString("sourceBranch");j.sourceSha=v.optString("sourceSha");
            j.bootstrapBranch=v.optString("bootstrapBranch");j.bootstrapSha=v.optString("bootstrapSha");
            j.archiveSha=v.optString("archiveSha");
            j.state=v.optString("state");j.stage=v.optString("stage");
            j.error=v.optString("error");j.runUrl=v.optString("runUrl");
            j.percent=v.optInt("percent");j.runId=v.optLong("runId");
            j.assetId=v.optLong("assetId");j.releaseId=v.optLong("releaseId");
            return j;
        }
    }

    private static String hex(byte[] bytes){
        StringBuilder s=new StringBuilder();
        for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
    private void save(Job j)throws Exception {
        if(!prefs.edit().putString("job_"+j.target.toLowerCase(Locale.ROOT),j.json().toString()).commit())
            throw new IllegalStateException("Состояние восстановления не сохранено");
    }
    public Job latest(String target){
        try{return Job.from(new JSONObject(prefs.getString("job_"+target.toLowerCase(Locale.ROOT),"{}")));}
        catch(Exception ignored){return null;}
    }

    private GitHubAccountStore.Account account(String id){
        for(GitHubAccountStore.Account a:accounts.listAccounts())
            if(a.id.equals(id))return a;
        throw new IllegalArgumentException("Аккаунт назначения не подключён");
    }
    private GitHubApiClient client(String accountId)throws Exception {
        GitHubAccountStore.Account a=account(accountId);
        String token=accounts.tokenForAccount(accountId);
        if(token.isEmpty())throw new IllegalStateException("Повторно войдите в GitHub");
        GitHubSession session=new GitHubSession(token,a.login,0L);
        GitHubApiClient gh=new GitHubApiClient(new GitHubSession.Source(){
            public GitHubSession capture(){return session;}
            public boolean isCurrent(GitHubSession item){return item==session;}
            public void reauthorization(GitHubSession item,boolean flag){}
        },null);
        JSONObject who=gh.getJsonFresh("https://api.github.com/user");
        if(!a.login.equalsIgnoreCase(who.optString("login","")))
            throw new SecurityException("Аккаунт авторизации изменился");
        return gh;
    }

    private static String head(GitHubApiClient gh,String repo,String branch)throws Exception {
        String escaped=java.net.URLEncoder.encode(branch,"UTF-8").replace("+","%20");
        JSONObject ref=gh.getJsonFresh(API+repo+"/git/ref/heads/"+escaped);
        JSONObject object=ref.optJSONObject("object");
        String sha=object==null?"":object.optString("sha","");
        if(!sha.matches("[a-fA-F0-9]{40}"))
            throw new SecurityException("Не удалось проверить HEAD назначения");
        return sha;
    }

    private long size(ContentResolver resolver,Uri selected)throws Exception {
        try(Cursor cursor=resolver.query(selected,new String[]{OpenableColumns.SIZE},null,null,null)){
            if(cursor==null||!cursor.moveToFirst()||cursor.isNull(0))
                throw new IllegalStateException("Хранилище не сообщило размер ZIP; выберите локальный файл");
            long bytes=cursor.getLong(0);
            if(bytes<=0||bytes>MAX_UPLOAD)
                throw new IllegalStateException("Восстановление поддерживает ZIP не более 1,95 ГБ");
            return bytes;
        }
    }

    /** One attempt, fixed length and no redirects. Ambiguous uploads must be inspected remotely. */
    private String upload(Uri archive,String target,long releaseId,String requestId,
                          String accountId,Progress progress)throws Exception {
        ContentResolver resolver=context.getContentResolver();
        long length=size(resolver,archive);
        String token=accounts.tokenForAccount(accountId);
        if(token.isEmpty())throw new SecurityException("GitHub-токен не найден");
        String filename="githubble-restore-"+requestId+".zip";
        URL url=new URL("https://uploads.github.com/repos/"+target+"/releases/"+releaseId+
                "/assets?name="+filename);
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setRequestMethod("POST");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(180000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(length);
        connection.setRequestProperty("Authorization","Bearer "+token);
        connection.setRequestProperty("Accept","application/vnd.github+json");
        connection.setRequestProperty("Content-Type","application/zip");
        connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        connection.setRequestProperty("User-Agent","GitHubble-Android");
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        try{
            long count=0,nextMark=4*1024*1024;
            try(InputStream in=resolver.openInputStream(archive);
                OutputStream out=connection.getOutputStream()){
                if(in==null)throw new IllegalStateException("Не удалось повторно прочитать ZIP");
                byte[] buf=new byte[128*1024];int n;
                while((n=in.read(buf))!=-1){
                    if(n==0)continue;
                    count+=n;
                    if(count>length)throw new SecurityException("Размер ZIP изменился");
                    sha.update(buf,0,n);out.write(buf,0,n);
                    if(count>=nextMark){
                        if(progress!=null)progress.onProgress(15+(int)(30*count/length),
                                "Передача ZIP в приватный репозиторий · "+(count/(1024*1024))+" МиБ");
                        nextMark=count+4*1024*1024;
                    }
                }
                if(count!=length)throw new SecurityException("ZIP был изменён во время загрузки");
            }
            int status=connection.getResponseCode();
            if(status!=201)throw new IllegalStateException("GitHub Release Upload: HTTP "+status);
            return hex(sha.digest());
        }finally{connection.disconnect();}
    }

    /**
     * The destination is always newly created and private, preventing exposure of
     * ZIPs and accidental overwrites of existing Git history.
     */
    public Job begin(Uri archive,PortableRepositoryArchive.Inspection inspected,
                     String destinationAccountId,String newName,Progress progress)throws Exception {
        if(archive==null||inspected==null)throw new IllegalArgumentException("Сначала выберите архив");
        MirrorPolicy.requireName(newName);
        GitHubAccountStore.Account owner=account(destinationAccountId);
        String destination=owner.login+"/"+newName;
        MirrorPolicy.requireRepository(destination);
        GitHubApiClient gh=client(destinationAccountId);
        try{
            gh.getJsonFresh(API+destination);
            throw new IllegalStateException("Репозиторий "+destination+" существует. "
                    +"Для безопасности восстановление допускается только в новый репозиторий.");
        }catch(GitHubApiClient.ApiException absent){if(absent.statusCode!=404)throw absent;}
        Job j=new Job();
        j.id=UUID.randomUUID().toString().replace("-","");
        j.target=destination;j.accountId=destinationAccountId;
        j.source=inspected.source;j.sourceSha=inspected.sha;
        j.sourceBranch=inspected.branch;
        j.percent=6;j.state="preparing";j.stage="Сверка информации · архив прошёл SHA-256";
        j.runUrl="https://github.com/"+destination+"/actions";
        save(j);
        if(progress!=null)progress.onProgress(6,j.stage);
        try {
            JSONObject created=gh.postJson("https://api.github.com/user/repos",
                    new JSONObject().put("name",newName).put("private",true)
                            .put("auto_init",true)
                            .put("description","GitHubble restore from portable full backup"),true);
            if(!destination.equalsIgnoreCase(created.optString("full_name",""))
                    ||!created.optBoolean("private")||created.optBoolean("fork"))
                throw new SecurityException("GitHub создал репозиторий с неожиданными параметрами");
            j.bootstrapBranch=created.optString("default_branch","main");
            j.percent=11;j.stage="Создан приватный репозиторий назначения";save(j);
            if(progress!=null)progress.onProgress(11,j.stage);
            // A private draft Release holds the archive temporarily. The source repo
            // may already be deleted; no source credentials are required.
            JSONObject release=gh.postJson(API+destination+"/releases",
                    new JSONObject().put("tag_name","githubble-restore-"+j.id)
                            .put("target_commitish",j.bootstrapBranch)
                            .put("name","GitHubble restore staging "+j.id)
                            .put("body","Temporary private staging. Do not publish.")
                            .put("draft",true),true);
            j.releaseId=release.getLong("id");save(j);
            if(progress!=null)progress.onProgress(14,"Передача ZIP в приватное хранилище GitHub");
            j.archiveSha=upload(archive,destination,j.releaseId,j.id,destinationAccountId,progress);
            JSONArray assets=gh.getJsonFresh(API+destination+"/releases/"+j.releaseId+"/assets?per_page=100")
                    .optJSONArray("assets");
            if(assets==null||assets.length()!=1)throw new SecurityException("Не подтверждена загрузка ZIP");
            JSONObject asset=assets.getJSONObject(0);
            if(!("githubble-restore-"+j.id+".zip").equals(asset.optString("name","")))
                throw new SecurityException("Неожиданное имя загруженного ZIP");
            j.assetId=asset.getLong("id");j.percent=50;
            j.stage="ZIP загружен · установка проверенного восстановителя";
            save(j);if(progress!=null)progress.onProgress(50,j.stage);
            JSONObject workflow=new JSONObject()
                    .put("message","chore(restore): bootstrap guarded GitHubble recovery")
                    .put("branch",j.bootstrapBranch)
                    .put("content",Base64.getEncoder().encodeToString(
                            FullRepositoryRestoreWorkflow.yaml().getBytes(StandardCharsets.UTF_8)));
            gh.putJson(API+destination+"/contents/"+FullRepositoryRestoreWorkflow.PATH,workflow);
            j.bootstrapSha=head(gh,destination,j.bootstrapBranch);
            save(j);
            FullBackupSecrets.put(gh,destination,j.id,accounts.tokenForAccount(destinationAccountId));
            JSONObject inputs=new JSONObject()
                    .put("request_id",j.id).put("archive_sha256",j.archiveSha)
                    .put("asset_id",String.valueOf(j.assetId))
                    .put("bootstrap_branch",j.bootstrapBranch)
                    .put("bootstrap_sha",j.bootstrapSha)
                    .put("source_sha",j.sourceSha);
            JSONObject request=new JSONObject().put("ref",j.bootstrapBranch).put("inputs",inputs);
            gh.executeText("POST",API+destination+"/actions/workflows/"
                    +FullRepositoryRestoreWorkflow.FILE+"/dispatches",request.toString(),true);
            j.state="running";j.percent=57;
            j.stage="Восстановление · ожидаем GitHub Actions";
            save(j);if(progress!=null)progress.onProgress(57,j.stage);
            return j;
        }catch(Exception e){
            // Do not destroy a possibly valid target or retry a potentially accepted
            // API mutation: an owner must inspect the new private repository first.
            j.state="unknown";j.error=e.getMessage()==null?"Результат операции неизвестен":e.getMessage();
            j.stage="Проверьте созданный репозиторий; автоматический повтор запрещён";
            save(j);
            throw new IllegalStateException("Восстановление не подтверждено. "+j.stage+
                    ". Назначение: "+destination+". Причина: "+j.error,e);
        }
    }

    public Job refresh(String destination)throws Exception {
        Job j=latest(destination);
        if(j==null||j.id.isEmpty())throw new IllegalStateException("Нет задания восстановления");
        if("done".equals(j.state))return j;
        GitHubApiClient gh=client(j.accountId);
        JSONObject history=gh.getJsonFresh(API+destination+
                "/actions/runs?event=workflow_dispatch&per_page=100");
        JSONArray runs=history.optJSONArray("workflow_runs");
        JSONObject match=null;
        for(int i=0;runs!=null&&i<runs.length();i++){
            JSONObject run=runs.optJSONObject(i);
            if(run!=null&&("GitHubble Full Restore "+j.id).equals(run.optString("display_title",""))
                    &&run.optString("path","").startsWith(FullRepositoryRestoreWorkflow.PATH)) {
                match=run;break;
            }
        }
        if(match==null){
            j.stage="Сверка информации · ожидаем запуск восстановления";save(j);return j;
        }
        j.runId=match.optLong("id");
        j.runUrl=match.optString("html_url",j.runUrl);
        if(!"completed".equals(match.optString("status"))){
            j.state="running";
            JSONArray jobs=gh.getJsonFresh(API+destination+"/actions/runs/"+j.runId+
                    "/jobs?per_page=100").optJSONArray("jobs");
            JSONObject task=jobs==null||jobs.length()==0?null:jobs.optJSONObject(0);
            JSONArray steps=task==null?null:task.optJSONArray("steps");
            int successful=0;
            for(int i=0;steps!=null&&i<steps.length();i++){
                JSONObject step=steps.optJSONObject(i);
                if(step==null)continue;
                if("success".equals(step.optString("conclusion")))successful++;
                if("in_progress".equals(step.optString("status")))
                    j.stage=step.optString("name","Восстановление");
            }
            j.percent=Math.min(96,Math.max(j.percent,57+successful*5));
            save(j);return j;
        }
        if(!"success".equals(match.optString("conclusion"))){
            j.state="failed";j.error="GitHub Actions: "+match.optString("conclusion");
            j.stage="Восстановление прервано · проверьте Actions и данные назначения";
            try {FullBackupSecrets.delete(gh,destination,j.id);}
            catch(Exception cleanup){j.error+="; не удалось удалить временный секрет: "+
                    cleanup.getClass().getSimpleName();}
            save(j);return j;
        }
        j.state="validating";j.percent=97;j.stage="Архив применён · сверка Git SHA назначения";save(j);
        if(!j.sourceSha.equalsIgnoreCase(head(gh,destination,j.sourceBranch)))
            throw new SecurityException("Основная ветка отличается от исходного архива");
        JSONObject current=gh.getJsonFresh(API+destination);
        if(!j.sourceBranch.equals(current.optString("default_branch","")))
            gh.patchJson(API+destination,new JSONObject().put("default_branch",j.sourceBranch));
        j.percent=100;j.state="done";
        j.stage="Архив применён и валидирован · восстановление завершено";
        save(j);
        // Cleanup is deliberately non-blocking. The user can retry cleanup
        // separately; the source repo is never touched by this operation.
        try {FullBackupSecrets.delete(gh,destination,j.id);} catch(Exception ignored){}
        try {gh.delete(API+destination+"/releases/"+j.releaseId);} catch(Exception ignored){}
        return j;
    }
}
